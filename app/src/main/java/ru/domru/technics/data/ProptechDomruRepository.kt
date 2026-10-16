package ru.domru.technics.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.domru.technics.domain.AddressSearch
import ru.domru.technics.domain.isVideoRequestRetryable
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode
import java.util.concurrent.ConcurrentHashMap

/** Соединяет настоящий служебный портал, защищённые сессии и экран приложения. */
class ProptechDomruRepository internal constructor(
    private val sessionStore: SessionStore,
    private val oidcClient: OidcSessionClient,
) : DomruRepository {
    constructor(context: Context) : this(SecureSessionStore(context), DomruOidcClient())

    private val demoRepository = PrototypeDomruRepository()
    private val sessionProvider = DomruSessionProvider(sessionStore, oidcClient)
    private val apiClient = ProptechApiClient(sessionProvider)
    private val catalogs = ConcurrentHashMap<String, RemoteCatalog>()
    private val catalogLocks = ConcurrentHashMap<String, Mutex>()

    /** Превращает сохранённые сессии в безопасные карточки без токенов. */
    override fun savedAccounts(): List<AccountProfile> = sessionStore.loadAll().map { session ->
        session.toProfile()
    }

    /** Возвращает вымышленные аккаунты только для отладки интерфейса. */
    override fun demoAccounts(): List<AccountProfile> = demoRepository.demoAccounts()

    /** Забывает старые списки, чтобы кнопка «Обновить» заново спросила сервер. */
    override fun clearCachedCatalogs() {
        catalogs.clear()
    }

    override suspend fun signIn(login: String, password: String): AccountProfile {
        val authorized = oidcClient.signIn(login, password)
        return saveAuthorizedSession(authorized, password = password)
    }

    /**
     * Проверяет и сохраняет пароль аккаунта из старой версии приложения.
     * Старый пароль прочитать невозможно: прежняя версия его действительно не записывала.
     */
    override suspend fun savePasswordForAutomaticLogin(
        accountId: String,
        password: String,
    ): AccountProfile {
        val oldSession = sessionStore.find(accountId)
            ?: throw DomruAuthenticationException(
                "Сохранённый логин не найден",
                AuthenticationFailure.SESSION_EXPIRED,
            )
        val authorized = oidcClient.signIn(oldSession.login, password)
        val updated = saveAuthorizedSession(
            authorized = authorized,
            password = password,
            createdAtMillis = oldSession.createdAtMillis,
        )
        if (updated.id != accountId && sessionStore.deleteIfUnchanged(oldSession)) {
            // Убираем прежний номер только если за время входа его запись не заменили.
            catalogs.remove(accountId)
        }
        return updated
    }

    /** Проверяет сессию настоящим запросом профиля, не сохраняя и не спрашивая пароль. */
    override suspend fun checkAccess(accountId: String): AccountAccessStatus {
        if (isDemoAccount(accountId)) return AccountAccessStatus.ACTIVE
        return try {
            apiClient.loadSpecifications(accountId)
            AccountAccessStatus.ACTIVE
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            when {
                error is DomruAuthenticationException && error.failure in setOf(
                    AuthenticationFailure.INVALID_CREDENTIALS,
                    AuthenticationFailure.SESSION_EXPIRED,
                ) -> AccountAccessStatus.NEEDS_PASSWORD
                error is DomruAuthenticationException &&
                    error.failure == AuthenticationFailure.CLIENT_REJECTED ->
                    AccountAccessStatus.ACCESS_DENIED
                error is PortalRequestException && error.statusCode == 401 ->
                    AccountAccessStatus.NEEDS_PASSWORD
                error is PortalRequestException && error.statusCode == 403 ->
                    AccountAccessStatus.ACCESS_DENIED
                else -> AccountAccessStatus.UNAVAILABLE
            }
        }
    }

    /**
     * Получает новую сессию для уже сохранённого логина.
     * Рабочую сессию этот метод менять отказывается, даже если экран ошибся со статусом.
     */
    override suspend fun renewPassword(accountId: String, newPassword: String): AccountProfile {
        val oldSession = sessionStore.find(accountId)
            ?: throw DomruAuthenticationException(
                "Сохранённый логин не найден",
                AuthenticationFailure.SESSION_EXPIRED,
            )
        when (checkAccess(accountId)) {
            AccountAccessStatus.ACTIVE -> throw ActiveSessionPasswordProtectedException()
            AccountAccessStatus.NEEDS_PASSWORD -> Unit
            AccountAccessStatus.ACCESS_DENIED -> throw PasswordRenewalUnavailableException(
                "Пароль не поможет: сервер запретил доступ аккаунту",
            )
            AccountAccessStatus.CHECKING,
            AccountAccessStatus.UNAVAILABLE,
            -> throw PasswordRenewalUnavailableException(
                "Сначала нужно подтвердить, что прежняя сессия действительно закончилась",
            )
        }

        val authorized = oidcClient.signIn(oldSession.login, newPassword)
        val updated = saveAuthorizedSession(
            authorized = authorized,
            password = newPassword,
            createdAtMillis = oldSession.createdAtMillis,
        )
        if (updated.id != accountId && sessionStore.deleteIfUnchanged(oldSession)) {
            // Новый вход по прежнему номеру не должен исчезнуть вместе со старой записью.
            catalogs.remove(accountId)
        }
        return updated
    }

    override suspend fun signOut(accountId: String) {
        if (isDemoAccount(accountId)) {
            demoRepository.signOut(accountId)
            return
        }

        // Запоминаем запись до проверки: её ответ и выход могут прийти уже после нового входа.
        val oldSession = sessionStore.find(accountId)
            ?: throw PasswordRenewalUnavailableException("Сохранённый логин не найден")
        when (checkAccess(accountId)) {
            AccountAccessStatus.NEEDS_PASSWORD -> Unit
            AccountAccessStatus.ACTIVE -> throw PasswordRenewalUnavailableException(
                "Рабочую учётку удалить нельзя",
            )
            else -> throw PasswordRenewalUnavailableException(
                "Удаление доступно только после подтверждённой потери входа",
            )
        }

        val refreshToken = oldSession.refreshToken
        var removed = false
        try {
            if (refreshToken != null) oidcClient.signOut(refreshToken)
        } catch (error: Throwable) {
            // Выход на телефоне должен сработать даже при пропавшем интернете.
            if (error is CancellationException) throw error
        } finally {
            removed = sessionStore.deleteIfUnchanged(oldSession)
            if (removed) catalogs.remove(accountId)
        }
        // Проверяем после finally, чтобы не скрыть отмену корутины ошибкой конфликта.
        if (!removed) {
            throw PasswordRenewalUnavailableException("Запись аккаунта изменилась. Удаление отменено")
        }
    }

    override suspend fun loadStreets(accountId: String): List<Street> =
        if (isDemoAccount(accountId)) demoRepository.loadStreets(accountId)
        else catalog(accountId).streets

    override suspend fun loadHouses(accountId: String, streetId: String): List<House> =
        if (isDemoAccount(accountId)) demoRepository.loadHouses(accountId, streetId)
        else catalog(accountId).housesByStreetId[streetId].orEmpty()

    override suspend fun loadEntrances(accountId: String, houseId: String): List<Entrance> =
        if (isDemoAccount(accountId)) demoRepository.loadEntrances(accountId, houseId)
        else catalog(accountId).entrancesByHouseId[houseId].orEmpty()

    override suspend fun search(accountId: String, query: String): List<AddressSearchResult> {
        if (isDemoAccount(accountId)) return demoRepository.search(accountId, query)
        val catalog = catalog(accountId)
        return catalog.streets.flatMap { street ->
            catalog.housesByStreetId[street.id].orEmpty().mapNotNull { house ->
                if (AddressSearch.matches(street.locality.name, street.name, house.label, query)) {
                    AddressSearchResult(
                        street,
                        house,
                        "${street.locality.name} · ${street.name} · ${house.label}",
                    )
                } else {
                    null
                }
            }
        }.take(30)
    }

    override suspend fun openDoor(accountId: String, entranceId: String): DoorOpenResult {
        if (isDemoAccount(accountId)) return demoRepository.openDoor(accountId, entranceId)
        val door = catalog(accountId).doorsByEntranceId[entranceId]
            ?: return DoorOpenResult.Rejected("Данные этой двери устарели. Обнови список")
        return apiClient.openDoor(accountId, door)
    }

    override suspend fun getTemporalCode(accountId: String, entranceId: String): TemporalCode? {
        if (isDemoAccount(accountId)) return demoRepository.getTemporalCode(accountId, entranceId)
        val door = catalog(accountId).doorsByEntranceId[entranceId] ?: return null
        return apiClient.getCode(accountId, door)
    }

    override suspend fun getCameraStream(accountId: String, entranceId: String): CameraStream? {
        if (isDemoAccount(accountId)) return demoRepository.getCameraStream(accountId, entranceId)
        val door = catalog(accountId).doorsByEntranceId[entranceId] ?: return null
        if (!door.cameraAvailable) return null
        return apiClient.getCameraStream(accountId, door)
    }

    /** Для одного аккаунта разрешает только одну одновременную загрузку каталога. */
    @Synchronized
    private fun lockFor(accountId: String): Mutex = catalogLocks.getOrPut(accountId) { Mutex() }

    /** Возвращает готовый каталог из памяти или один раз загружает его с портала. */
    private suspend fun catalog(accountId: String): RemoteCatalog {
        catalogs[accountId]?.let { return it }
        return lockFor(accountId).withLock {
            catalogs[accountId] ?: loadCatalog(accountId).also { loaded ->
                if (loaded.complete) catalogs[accountId] = loaded
            }
        }
    }

    /** Вспомогательное название не должно ломать весь каталог при временной ошибке. */
    private suspend fun loadCompanyLocalitySafely(
        accountId: String,
        specification: PortalSpecification,
    ): String? = try {
        apiClient.loadCompanyLocality(accountId, specification)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }

    /** Связывает дома, подъезды и устройства в единое дерево одного аккаунта. */
    private suspend fun loadCatalog(accountId: String): RemoteCatalog {
        val specifications = apiClient.loadSpecifications(accountId)
        if (specifications.isEmpty()) {
            throw PortalProtocolException("У аккаунта нет доступной служебной компании")
        }

        val builder = RemoteCatalogBuilder()

        var successfulSpecifications = 0
        val failures = mutableListOf<Exception>()
        specifications.forEach { specification ->
            try {
                // В полном адресе город бывает не всегда, поэтому заранее читаем карточку компании.
                val companyLocality = specification.localityName
                    ?: loadCompanyLocalitySafely(accountId, specification)
                val portalHouses = apiClient.loadHouses(accountId, specification)
                val porches = apiClient.loadPorches(accountId, specification)
                val devices = apiClient.loadIntercomDevices(accountId, specification)
                builder.addSpecification(specification, companyLocality, portalHouses, porches, devices)
                successfulSpecifications++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Неудача компании не скрывает двери, полученные из другой компании.
                if (error !is PortalRequestException && error !is DomruAuthenticationException &&
                    error !is PortalProtocolException && error !is SessionStorageException &&
                    error !is java.io.IOException) throw error
                failures += error
            }
        }

        if (successfulSpecifications == 0 && failures.isNotEmpty()) {
            throw failures.lastOrNull { isVideoRequestRetryable(it) }
                ?: failures.last()
        }

        return builder.build(complete = failures.isEmpty())
    }

    /** Отделяет безопасный демонстрационный режим от настоящего портала. */
    private fun isDemoAccount(accountId: String): Boolean = demoRepository.demoAccounts().any {
        it.id == accountId
    }

    /** Шифрует новую сессию и сохраняет прежнюю дату добавления аккаунта. */
    private fun saveAuthorizedSession(
        authorized: AuthorizedSession,
        password: String,
        createdAtMillis: Long? = null,
    ): AccountProfile {
        val oldSession = sessionStore.find(authorized.accountId)
        val stored = StoredSession(
            accountId = authorized.accountId,
            title = authorized.title,
            login = authorized.login,
            accessToken = authorized.accessToken,
            refreshToken = authorized.refreshToken,
            password = password,
            accessTokenExpiresAtMillis = authorized.accessTokenExpiresAtMillis,
            createdAtMillis = createdAtMillis
                ?: oldSession?.createdAtMillis
                ?: System.currentTimeMillis(),
        )
        sessionStore.save(stored)
        catalogs.remove(stored.accountId)
        return stored.toProfile()
    }

    /** Убирает секретные поля перед передачей данных на экран. */
    private fun StoredSession.toProfile(): AccountProfile = AccountProfile(
        id = accountId,
        title = title,
        loginHint = login,
        hasSavedPassword = !password.isNullOrBlank(),
    )
}
