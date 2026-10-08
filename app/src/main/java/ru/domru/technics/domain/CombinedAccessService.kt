package ru.domru.technics.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import ru.domru.technics.data.DomruRepository
import ru.domru.technics.data.DomruAuthenticationException
import ru.domru.technics.data.AuthenticationFailure
import ru.domru.technics.data.PortalProtocolException
import ru.domru.technics.data.PortalRequestException
import ru.domru.technics.data.SessionStorageException
import ru.domru.technics.model.AccessSource
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode
import java.io.IOException

/**
 * Собирает двери из всех добавленных аккаунтов в один список.
 * Экрану не нужно знать, сколько серверных запросов для этого понадобилось.
 */
class CombinedAccessService(
    private val repository: DomruRepository,
    private val retryPolicy: RetryPolicy,
) {
    /** Параллельно читает улицы действующих аккаунтов и убирает дубли. */
    suspend fun loadStreets(accounts: List<AccountProfile>): List<Street> {
        val loaded = readAvailableSources(accounts) { account ->
            repository.loadStreets(account.id).map { street ->
                street.withSourceIfMissing(account.id)
            }
        }
        return AddressHierarchyMerger.streets(loaded)
    }

    /** Читает дома через каждый аккаунт, который видит выбранную улицу. */
    suspend fun loadHouses(street: Street): List<House> {
        val loaded = readAvailableSources(street.sources) { source ->
            repository.loadHouses(source.accountId, source.remoteId).map { house ->
                house.withSourceIfMissing(source.accountId)
            }
        }
        return AddressHierarchyMerger.houses(street, loaded)
    }

    /** Читает подъезды через каждый доступный источник выбранного дома. */
    suspend fun loadEntrances(house: House): List<Entrance> {
        val loaded = readAvailableSources(house.sources) { source ->
            repository.loadEntrances(source.accountId, source.remoteId).map { entrance ->
                entrance.withSourceIfMissing(source.accountId)
            }
        }
        return AddressHierarchyMerger.entrances(house, loaded)
    }

    /** Ищет сразу во всех действующих аккаунтах и возвращает общий список. */
    suspend fun search(
        accounts: List<AccountProfile>,
        query: String,
    ): List<AddressSearchResult> {
        val loaded = readAvailableSources(accounts) { account ->
            repository.search(account.id, query).map { result ->
                result.copy(
                    street = result.street.withSourceIfMissing(account.id),
                    house = result.house.withSourceIfMissing(account.id),
                )
            }
        }
        return AddressHierarchyMerger.searchResults(loaded)
    }

    /** Выбирает один подходящий аккаунт и никогда не дублирует команду открытия. */
    suspend fun openDoor(
        accounts: List<AccountProfile>,
        entrance: Entrance,
    ): DoorOpenResult {
        val source = preferredSource(accounts, entrance.sources)
            ?: return DoorOpenResult.Rejected("Для этой двери нет действующего аккаунта")

        // Даже если аккаунтов несколько, команда отправляется только через один из них.
        return retryPolicy.execute(RequestKind.DOOR_OPEN) {
            repository.openDoor(source.accountId, source.remoteId)
        }
    }

    /** Запрашивает код через первый действующий доступ к подъезду. */
    suspend fun getTemporalCode(
        accounts: List<AccountProfile>,
        entrance: Entrance,
    ): TemporalCode? {
        val source = preferredSource(accounts, entrance.sources) ?: return null
        return retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
            repository.getTemporalCode(source.accountId, source.remoteId)
        }
    }

    /** Пробует только разрешённые видеоисточники той же физической двери. */
    suspend fun getCameraStream(
        accounts: List<AccountProfile>,
        entrance: Entrance,
        failedSource: AccessSource? = null,
    ): CameraStream? {
        if (!entrance.cameraAvailable) return null
        val authorized = accounts.flatMap { account ->
            entrance.sources.filter { it.accountId == account.id && it.canVideo }
        }.distinctBy { it.accountId to it.remoteId }
        val failedIndex = authorized.indexOfFirst { source ->
            source.accountId == failedSource?.accountId && source.remoteId == failedSource?.remoteId
        }
        // После ошибки воспроизведения переходим к следующему разрешённому источнику.
        // Обычная загрузка сохраняет порядок аккаунтов и не расширяет доступ к двери.
        val sources = if (failedIndex >= 0) authorized.drop(failedIndex + 1) + authorized.take(failedIndex + 1)
            else authorized
        val failures = mutableListOf<Exception>()
        for (source in sources) {
            try {
                val stream = retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
                    repository.getCameraStream(source.accountId, source.remoteId)
                }
                if (stream != null) return stream.copy(source = source)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isSourceUnavailable(error)) throw error
                failures += error
            }
        }
        preferredFailure(failures)?.let { throw it }
        return null
    }

    /** Сбой одного аккаунта не скрывает объекты, полученные из остальных аккаунтов. */
    private suspend fun <S, T> readAvailableSources(
        sources: List<S>,
        read: suspend (S) -> List<T>,
    ): List<T> = coroutineScope {
        val results = sources.map { source ->
            async {
                try {
                    Result.success(retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) { read(source) })
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!isSourceUnavailable(error)) throw error
                    Result.failure<List<T>>(error)
                }
            }
        }.awaitAll()
        if (results.isNotEmpty() && results.none { it.isSuccess }) {
            throw checkNotNull(preferredFailure(results.mapNotNull { it.exceptionOrNull() as? Exception }))
        }
        results.flatMap { it.getOrNull().orEmpty() }
    }

    private fun isSourceUnavailable(error: Exception): Boolean = error is IOException ||
        error is PortalRequestException || error is DomruAuthenticationException || error is PortalProtocolException ||
        error is SessionStorageException

    /** Ответ 403 другого источника не скрывает временный сбой доступной камеры. */
    private fun preferredFailure(failures: List<Exception>): Exception? =
        failures.lastOrNull(::isVideoRequestRetryable) ?: failures.lastOrNull()

    /**
     * Повторяем только временные неполадки.
     * Например, запрет доступа повторять бессмысленно, а обрыв интернета — можно.
     */
    private fun isSafeReadRetryable(error: Throwable): Boolean = when (error) {
        is IOException -> true
        is SessionStorageException -> true
        is DomruAuthenticationException -> error.failure == AuthenticationFailure.SERVICE_UNAVAILABLE
        is PortalRequestException -> error.statusCode == 408 ||
            error.statusCode == 425 ||
            error.statusCode == 429 ||
            error.statusCode in 500..599
        else -> false
    }

    /** Выбирает источник в том же порядке, в котором аккаунты видит человек. */
    private fun preferredSource(
        accounts: List<AccountProfile>,
        sources: List<AccessSource>,
        allowed: (AccessSource) -> Boolean = { true },
    ): AccessSource? {
        // Порядок аккаунтов виден человеку. Первый подходящий аккаунт и используем.
        return accounts.firstNotNullOfOrNull { account ->
            sources.firstOrNull { it.accountId == account.id && allowed(it) }
        }
    }

    /** Подписывает сырую улицу аккаунтом, из которого она пришла. */
    private fun Street.withSourceIfMissing(accountId: String): Street =
        if (sources.isEmpty()) copy(sources = listOf(AccessSource(accountId, id))) else this

    /** Подписывает сырой дом аккаунтом, из которого он пришёл. */
    private fun House.withSourceIfMissing(accountId: String): House =
        if (sources.isEmpty()) copy(sources = listOf(AccessSource(accountId, id))) else this

    /** Подписывает сырой подъезд аккаунтом, из которого он пришёл. */
    private fun Entrance.withSourceIfMissing(accountId: String): Entrance =
        if (sources.isEmpty()) copy(sources = listOf(AccessSource(accountId, id, canVideo = cameraAvailable))) else this
}
