package ru.domru.technics.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.domru.technics.domain.AddressSearch
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode
import ru.domru.technics.model.UNKNOWN_LOCALITY_ID
import ru.domru.technics.model.UNKNOWN_LOCALITY_NAME
import java.util.concurrent.ConcurrentHashMap

/** Соединяет настоящий служебный портал, защищённые сессии и экран приложения. */
class ProptechDomruRepository(context: Context) : DomruRepository {
    private val demoRepository = PrototypeDomruRepository()
    private val sessionStore = SecureSessionStore(context)
    private val oidcClient = DomruOidcClient()
    private val sessionProvider = DomruSessionProvider(sessionStore, oidcClient)
    private val apiClient = ProptechApiClient(sessionProvider)
    private val catalogs = ConcurrentHashMap<String, RemoteCatalog>()
    private val catalogLocks = ConcurrentHashMap<String, Mutex>()

    /** Превращает сохранённые сессии в безопасные карточки без токенов. */
    fun savedAccounts(): List<AccountProfile> = sessionStore.loadAll().map { session ->
        session.toProfile()
    }

    /** Возвращает вымышленные аккаунты только для отладки интерфейса. */
    fun demoAccounts(): List<AccountProfile> = demoRepository.demoAccounts()

    /** Забывает старые списки, чтобы кнопка «Обновить» заново спросила сервер. */
    fun clearCachedCatalogs() {
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
    suspend fun savePasswordForAutomaticLogin(
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
        if (updated.id != accountId) {
            // Сервер сменил внутренний номер: старая запись больше не должна путать экран.
            sessionStore.delete(accountId)
            catalogs.remove(accountId)
        }
        return updated
    }

    /** Проверяет сессию настоящим запросом профиля, не сохраняя и не спрашивая пароль. */
    suspend fun checkAccess(accountId: String): AccountAccessStatus {
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
    suspend fun renewPassword(accountId: String, newPassword: String): AccountProfile {
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
        if (updated.id != accountId) {
            // Если сервер сменил внутренний идентификатор, старую битую запись больше не держим.
            sessionStore.delete(accountId)
            catalogs.remove(accountId)
        }
        return updated
    }

    override suspend fun signOut(accountId: String) {
        if (isDemoAccount(accountId)) {
            demoRepository.signOut(accountId)
            return
        }

        when (checkAccess(accountId)) {
            AccountAccessStatus.NEEDS_PASSWORD -> Unit
            AccountAccessStatus.ACTIVE -> throw PasswordRenewalUnavailableException(
                "Рабочую учётку удалить нельзя",
            )
            else -> throw PasswordRenewalUnavailableException(
                "Удаление доступно только после подтверждённой потери входа",
            )
        }

        val refreshToken = sessionStore.find(accountId)?.refreshToken
        try {
            if (refreshToken != null) oidcClient.signOut(refreshToken)
        } catch (error: Throwable) {
            // Выход на телефоне должен сработать даже при пропавшем интернете.
            if (error is CancellationException) throw error
        } finally {
            sessionStore.delete(accountId)
            catalogs.remove(accountId)
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
                catalogs[accountId] = loaded
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

        val streets = mutableListOf<Street>()
        val housesByStreet = mutableMapOf<String, MutableList<House>>()
        val entrancesByHouse = mutableMapOf<String, List<Entrance>>()
        val doorsByEntrance = mutableMapOf<String, RemoteDoor>()

        specifications.forEach { specification ->
            // В полном адресе город бывает не всегда, поэтому заранее читаем карточку компании.
            val companyLocality = specification.localityName
                ?: loadCompanyLocalitySafely(accountId, specification)
            val portalHouses = apiClient.loadHouses(accountId, specification)
            val porches = apiClient.loadPorches(accountId, specification)
            val devices = apiClient.loadIntercomDevices(accountId, specification)
            val usedDeviceIds = mutableSetOf<String>()

            portalHouses.forEach { portalHouse ->
                val address = RussianAddressParser.parse(portalHouse.address)
                // Если город есть в полном адресе, он становится отдельной верхней ступенью.
                val localityName = address.locality ?: companyLocality ?: UNKNOWN_LOCALITY_NAME
                val localityKey = if (localityName == UNKNOWN_LOCALITY_NAME) {
                    "unknown"
                } else {
                    AddressSearch.canonicalLocality(localityName)
                }
                val localityId = remoteId(specification.id, "locality", localityKey)
                val streetKey = "$localityKey|${AddressSearch.canonical(address.street)}"
                val streetId = remoteId(specification.id, "street", streetKey)
                val houseId = remoteId(specification.id, "house", portalHouse.houseId)
                val houseRoute = HouseRoute.decode(portalHouse.route)
                val housePorches = porches.filter { porch ->
                    porch.parentRoute == portalHouse.route ||
                        HouseRoute.decode(porch.route)?.houseId in setOf(
                            portalHouse.houseId,
                            houseRoute?.houseId,
                        )
                }
                val houseDevices = devices.filter { device ->
                    val route = HouseRoute.decode(device.route)
                    val matches = route?.houseId in setOf(portalHouse.houseId, houseRoute?.houseId) ||
                        portalHouse.route.isNotBlank() && device.route.startsWith(portalHouse.route)
                    if (matches) usedDeviceIds += device.id
                    matches
                }
                val entrances = houseDevices.map { device ->
                    val route = HouseRoute.decode(device.route)
                    val porch = housePorches.firstOrNull { candidate ->
                        candidate.route == device.route ||
                            route?.porch in setOf(candidate.porchId, candidate.number)
                    }
                    device.toEntrance(specification.id, houseId, porch).also { entrance ->
                        doorsByEntrance[entrance.id] = device.toRemoteDoor(specification.id)
                    }
                }

                if (streets.none { it.id == streetId }) {
                    streets += Street(
                        id = streetId,
                        name = address.street,
                        locality = Locality(id = localityId, name = localityName),
                    )
                }
                housesByStreet.getOrPut(streetId, ::mutableListOf) += House(
                    id = houseId,
                    streetId = streetId,
                    label = address.house,
                    entranceCount = entrances.size,
                )
                entrancesByHouse[houseId] = entrances
            }

            val unusedDevices = devices.filterNot { it.id in usedDeviceIds }
            if (unusedDevices.isNotEmpty()) {
                addUnassignedDevices(
                    specification = specification,
                    devices = unusedDevices,
                    streets = streets,
                    housesByStreet = housesByStreet,
                    entrancesByHouse = entrancesByHouse,
                    doorsByEntrance = doorsByEntrance,
                )
            }
        }

        val countedStreets = streets.map { street ->
            street.copy(houseCount = housesByStreet[street.id].orEmpty().size)
        }.sortedWith(
            compareBy(
                { AddressSearch.canonical(it.locality.name) },
                { AddressSearch.canonical(it.name) },
            ),
        )
        return RemoteCatalog(
            streets = countedStreets,
            housesByStreetId = housesByStreet,
            entrancesByHouseId = entrancesByHouse,
            doorsByEntranceId = doorsByEntrance,
        )
    }

    /** Не теряет устройства, которые сервер не смог связать с конкретным домом. */
    private fun addUnassignedDevices(
        specification: PortalSpecification,
        devices: List<PortalDevice>,
        streets: MutableList<Street>,
        housesByStreet: MutableMap<String, MutableList<House>>,
        entrancesByHouse: MutableMap<String, List<Entrance>>,
        doorsByEntrance: MutableMap<String, RemoteDoor>,
    ) {
        val streetId = remoteId(specification.id, "street", "other")
        val houseId = remoteId(specification.id, "house", "other")
        if (streets.none { it.id == streetId }) {
            streets += Street(
                id = streetId,
                name = "Другие объекты",
                locality = Locality(UNKNOWN_LOCALITY_ID, UNKNOWN_LOCALITY_NAME),
            )
        }
        val entrances = devices.map { device ->
            device.toEntrance(specification.id, houseId, porch = null).also { entrance ->
                doorsByEntrance[entrance.id] = device.toRemoteDoor(specification.id)
            }
        }
        housesByStreet.getOrPut(streetId, ::mutableListOf) += House(
            id = houseId,
            streetId = streetId,
            label = "Без адресной привязки",
            entranceCount = entrances.size,
        )
        entrancesByHouse[houseId] = entrances
    }

    /** Превращает техническую запись устройства в понятную строку подъезда. */
    private fun PortalDevice.toEntrance(
        specificationId: String,
        houseId: String,
        porch: PortalPorch?,
    ): Entrance = Entrance(
        id = remoteId(specificationId, "door", id),
        houseId = houseId,
        label = porch?.number?.let { "Подъезд $it" } ?: name,
        cameraAvailable = cameraAvailable,
    )

    /** Оставляет серверные номера, нужные только для команд двери. */
    private fun PortalDevice.toRemoteDoor(specificationId: String): RemoteDoor = RemoteDoor(
        specificationId = specificationId,
        deviceId = id,
        accessControlId = accessControlId,
    )

    /** Добавляет компанию к номеру объекта, чтобы два аккаунта не перепутали записи. */
    private fun remoteId(specificationId: String, kind: String, value: String): String =
        "$specificationId::$kind::$value"

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
