package ru.domru.technics.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.domru.technics.data.DomruRepository
import ru.domru.technics.data.PortalRequestException
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
    suspend fun loadStreets(accounts: List<AccountProfile>): List<Street> = coroutineScope {
        val loaded = accounts.map { account ->
            async {
                retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
                    repository.loadStreets(account.id).map { street ->
                        street.withSourceIfMissing(account.id)
                    }
                }
            }
        }.awaitAll().flatten()
        AddressHierarchyMerger.streets(loaded)
    }

    /** Читает дома через каждый аккаунт, который видит выбранную улицу. */
    suspend fun loadHouses(street: Street): List<House> = coroutineScope {
        val loaded = street.sources.map { source ->
            async {
                retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
                    repository.loadHouses(source.accountId, source.remoteId).map { house ->
                        house.withSourceIfMissing(source.accountId)
                    }
                }
            }
        }.awaitAll().flatten()
        AddressHierarchyMerger.houses(street, loaded)
    }

    /** Читает подъезды через каждый доступный источник выбранного дома. */
    suspend fun loadEntrances(house: House): List<Entrance> = coroutineScope {
        val loaded = house.sources.map { source ->
            async {
                retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
                    repository.loadEntrances(source.accountId, source.remoteId).map { entrance ->
                        entrance.withSourceIfMissing(source.accountId)
                    }
                }
            }
        }.awaitAll().flatten()
        AddressHierarchyMerger.entrances(house, loaded)
    }

    /** Ищет сразу во всех действующих аккаунтах и возвращает общий список. */
    suspend fun search(
        accounts: List<AccountProfile>,
        query: String,
    ): List<AddressSearchResult> = coroutineScope {
        val loaded = accounts.map { account ->
            async {
                retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
                    repository.search(account.id, query).map { result ->
                        result.copy(
                            street = result.street.withSourceIfMissing(account.id),
                            house = result.house.withSourceIfMissing(account.id),
                        )
                    }
                }
            }
        }.awaitAll().flatten()
        AddressHierarchyMerger.searchResults(loaded)
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

    /** Получает видео через первый действующий доступ к подъезду. */
    suspend fun getCameraStream(
        accounts: List<AccountProfile>,
        entrance: Entrance,
    ): CameraStream? {
        val source = preferredSource(accounts, entrance.sources) ?: return null
        return retryPolicy.execute(RequestKind.SAFE_READ, ::isSafeReadRetryable) {
            repository.getCameraStream(source.accountId, source.remoteId)
        }
    }

    /**
     * Повторяем только временные неполадки.
     * Например, запрет доступа повторять бессмысленно, а обрыв интернета — можно.
     */
    private fun isSafeReadRetryable(error: Throwable): Boolean = when (error) {
        is IOException -> true
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
    ): AccessSource? {
        // Порядок аккаунтов виден человеку. Первый подходящий аккаунт и используем.
        return accounts.firstNotNullOfOrNull { account ->
            sources.firstOrNull { it.accountId == account.id }
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
        if (sources.isEmpty()) copy(sources = listOf(AccessSource(accountId, id))) else this
}
