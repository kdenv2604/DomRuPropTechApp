package ru.domru.technics.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
    private val catalogStore: CatalogDiskStore? = null,
) : DomruRepository {
    constructor(context: Context) : this(SecureSessionStore(context), DomruOidcClient(),
        CatalogDiskStore(File(context.noBackupFilesDir, "access-catalogs"), SecureSessionStore(context)))

    private val demoRepository = PrototypeDomruRepository()
    private val sessionProvider = DomruSessionProvider(sessionStore, oidcClient)
    private val apiClient = ProptechApiClient(sessionProvider)
    private val catalogs = ConcurrentHashMap<String, RemoteCatalog>()
    private val catalogLocks = ConcurrentHashMap<String, Mutex>()
    private val catalogStateLock = Any()
    private val generations = mutableMapOf<String, Long>()

    override fun cachedAccountIds(): Set<String> = catalogs.keys.toSet()

    override suspend fun restoreCatalogs() = withContext(Dispatchers.IO) {
        sessionStore.loadAll().forEach { session ->
            val generation = synchronized(catalogStateLock) { generations[session.accountId] ?: 0L }
            val saved = catalogStore?.read(session.accountId, session.catalogBinding()) ?: return@forEach
            currentCoroutineContext().ensureActive()
            synchronized(catalogStateLock) {
                if ((generations[session.accountId] ?: 0L) == generation &&
                    sessionStore.find(session.accountId)?.catalogBinding() == session.catalogBinding()) {
                    catalogs.putIfAbsent(session.accountId, saved)
                }
            }
        }
    }

    override suspend fun refreshCatalog(accountId: String): Boolean = withContext(Dispatchers.IO) {
        if (isDemoAccount(accountId)) return@withContext true
        lockFor(accountId).withLock { fetchCatalog(accountId).complete }
    }

    override suspend fun forgetCatalog(accountId: String) = withContext(Dispatchers.IO) {
        invalidateCatalog(accountId)
    }

    private fun StoredSession.catalogBinding(): String = "$accountId|$login|$createdAtMillis"

    private fun invalidateCatalog(accountId: String) = synchronized(catalogStateLock) {
        generations[accountId] = (generations[accountId] ?: 0L) + 1L
        catalogs.remove(accountId)
        catalogStore?.remove(accountId)
        Unit
    }

    private suspend fun fetchCatalog(accountId: String): RemoteCatalog {
        val generation = synchronized(catalogStateLock) { generations[accountId] ?: 0L }
        val binding = sessionStore.find(accountId)?.catalogBinding()
            ?: throw DomruAuthenticationException("Сессия не найдена", AuthenticationFailure.SESSION_EXPIRED)
        val loaded = loadCatalog(accountId)
        currentCoroutineContext().ensureActive()
        // Удаление/новый вход меняют поколение. Поздний ответ не воскрешает старые доступы.
        synchronized(catalogStateLock) {
            if ((generations[accountId] ?: 0L) != generation ||
                sessionStore.find(accountId)?.catalogBinding() != binding) throw CancellationException()
            catalogs[accountId] = loaded
            try { catalogStore?.write(accountId, binding, loaded) }
            catch (_: Exception) { /* Ошибка хранения не отменяет полученный каталог в памяти. */ }
        }
        return loaded
    }

    /** Превращает сохранённые сессии в безопасные карточки без токенов. */
    override fun savedAccounts(): List<AccountProfile> = sessionStore.loadAll().map { session ->
        session.toProfile()
    }

    /** Возвращает вымышленные аккаунты только для отладки интерфейса. */
    override fun demoAccounts(): List<AccountProfile> = demoRepository.demoAccounts()

    /** Забывает старые списки, чтобы кнопка «Обновить» заново спросила сервер. */
    override fun clearCachedCatalogs() {
        synchronized(catalogStateLock) {
            (catalogs.keys + generations.keys).forEach { generations[it] = (generations[it] ?: 0L) + 1L }
            catalogs.clear()
        }
    }

    override suspend fun signIn(login: String, password: String): AccountProfile {
        val authorized = oidcClient.signIn(login, password)
        return withContext(Dispatchers.IO) { saveAuthorizedSession(authorized, password = password) }
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
            invalidateCatalog(accountId)
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
            invalidateCatalog(accountId)
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
            if (removed) invalidateCatalog(accountId)
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
        return withContext(Dispatchers.IO) {
            lockFor(accountId).withLock { catalogs[accountId] ?: fetchCatalog(accountId) }
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
        val builder = RemoteCatalogBuilder()
        val retained = mutableListOf<RemoteCatalog>()

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
                if ((error is PortalRequestException && error.statusCode == 401) ||
                    (error is DomruAuthenticationException && error.failure in setOf(
                        AuthenticationFailure.INVALID_CREDENTIALS,
                        AuthenticationFailure.SESSION_EXPIRED,
                    ))) {
                    // 401 относится ко всей учётке. Не показываем её сохранённые двери как действующие.
                    throw error
                }
                failures += error
                // Полный список компаний авторитетен. Сохраняем старые данные только для
                // компании с временным сбоем; 403/404 означают подтверждённую потерю доступа.
                if (error !is PortalRequestException || error.statusCode !in setOf(403, 404)) {
                    catalogs[accountId]?.forSpecification(specification.id)?.let(retained::add)
                }
            }
        }

        if (successfulSpecifications == 0 && retained.isEmpty() && failures.isNotEmpty() &&
            failures.any { it !is PortalRequestException || it.statusCode !in setOf(403, 404) }) {
            throw failures.lastOrNull { isVideoRequestRetryable(it) }
                ?: failures.last()
        }

        val fresh = builder.build(complete = failures.isEmpty())
        return retained.fold(fresh) { result, previous ->
            RemoteCatalog(result.streets + previous.streets,
                result.housesByStreetId + previous.housesByStreetId,
                result.entrancesByHouseId + previous.entrancesByHouseId,
                result.doorsByEntranceId + previous.doorsByEntranceId, complete = false)
        }
    }

    private fun RemoteCatalog.forSpecification(id: String): RemoteCatalog = RemoteCatalog(
        streets.filter { it.id.startsWith("$id::") },
        housesByStreetId.filterKeys { it.startsWith("$id::") },
        entrancesByHouseId.filterKeys { it.startsWith("$id::") },
        doorsByEntranceId.filterValues { it.specificationId == id }, complete = false,
    )

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
        invalidateCatalog(stored.accountId)
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
