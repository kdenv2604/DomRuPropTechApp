package ru.domru.technics.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.domru.technics.BuildConfig
import ru.domru.technics.data.AppPreferences
import ru.domru.technics.data.ActiveSessionPasswordProtectedException
import ru.domru.technics.data.DomruAuthenticationException
import ru.domru.technics.data.PortalProtocolException
import ru.domru.technics.data.PortalRequestException
import ru.domru.technics.data.PasswordRenewalUnavailableException
import ru.domru.technics.data.ProptechDomruRepository
import ru.domru.technics.data.SessionStorageException
import ru.domru.technics.domain.AddressSearch
import ru.domru.technics.domain.CombinedAccessService
import ru.domru.technics.domain.RequestKind
import ru.domru.technics.domain.RetryPolicy
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.allowsAccountRecoveryActions
import ru.domru.technics.model.AccordionSelection
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CameraState
import ru.domru.technics.model.CodeActionState
import ru.domru.technics.model.DoorActionState
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode
import ru.domru.technics.model.ThemeMode

/** Всё, что сейчас видно на экране, хранится в одном понятном наборе данных. */
data class AppUiState(
    val accounts: List<AccountProfile> = emptyList(),
    val showLogin: Boolean = true,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val accountStatuses: Map<String, AccountAccessStatus> = emptyMap(),
    val passwordRenewalAccountId: String? = null,
    val passwordRenewalBusy: Boolean = false,
    val passwordRenewalError: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val streets: List<Street> = emptyList(),
    val streetsLoading: Boolean = false,
    val housesByStreet: Map<String, List<House>> = emptyMap(),
    val loadingStreetIds: Set<String> = emptySet(),
    val entrancesByHouse: Map<String, List<Entrance>> = emptyMap(),
    val loadingHouseIds: Set<String> = emptySet(),
    val selection: AccordionSelection = AccordionSelection(),
    val searchQuery: String = "",
    val searchResults: List<AddressSearchResult> = emptyList(),
    val searchBusy: Boolean = false,
    val doorActions: Map<String, DoorActionState> = emptyMap(),
    val codeActions: Map<String, CodeActionState> = emptyMap(),
    val cameraStates: Map<String, CameraState> = emptyMap(),
    val shownCode: TemporalCode? = null,
)

/** Одноразовое событие, которое не надо хранить в постоянном состоянии экрана. */
sealed interface AppEvent {
    /** Короткий текст для нижнего всплывающего сообщения. */
    data class Message(val text: String) : AppEvent
}

/**
 * Получает нажатия с экрана и меняет общее состояние приложения.
 * Экран сам не открывает двери и поэтому не сможет случайно повторить запрос.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProptechDomruRepository(application)
    private val retryPolicy = RetryPolicy()
    private val combinedAccess = CombinedAccessService(repository, retryPolicy)
    private val preferences = AppPreferences(application)
    private val savedAccounts = repository.savedAccounts()

    private val mutableState = MutableStateFlow(
        AppUiState(
            accounts = savedAccounts,
            showLogin = savedAccounts.isEmpty(),
            themeMode = preferences.loadThemeMode(),
            accountStatuses = savedAccounts.associate { account ->
                account.id to if (account.isDemo) {
                    AccountAccessStatus.ACTIVE
                } else {
                    AccountAccessStatus.CHECKING
                }
            },
        ),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<AppEvent>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var treeLoadingJob: Job? = null
    private var searchJob: Job? = null
    private var cameraJob: Job? = null
    private var accountCheckJob: Job? = null

    init {
        // Сначала проверяем каждый логин. Нерабочий аккаунт не должен ломать общий список.
        if (savedAccounts.isNotEmpty()) checkAccountAccesses(loadAddressesAfter = true)
    }

    /** Выполняет один вход, сохраняет сессию и обновляет общий список адресов. */
    fun addLoginAndPassword(login: String, password: String) {
        if (mutableState.value.loginBusy) return
        if (login.isBlank() || password.isBlank()) {
            mutableState.update { it.copy(loginError = "Заполни логин и пароль") }
            return
        }

        // Сразу запрещаем второе нажатие, чтобы сервер получил только одну попытку входа.
        mutableState.update { it.copy(loginBusy = true, loginError = null) }
        viewModelScope.launch {
            try {
                // Вход выполняется один раз. Это защищает учётку от быстрых повторов.
                val account = retryPolicy.execute(RequestKind.LOGIN) {
                    repository.signIn(login.trim(), password)
                }
                // Повторный вход тем же логином обновляет его карточку, но не создаёт дубль.
                val accounts = mutableState.value.accounts
                    .filterNot { it.id == account.id || it.isDemo } + account
                mutableState.update {
                    it.copy(
                        accounts = accounts,
                        showLogin = false,
                        loginBusy = false,
                        accountStatuses = it.accountStatuses
                            .filterKeys { id -> accounts.any { account -> account.id == id } } +
                            (account.id to AccountAccessStatus.ACTIVE),
                    )
                        .withEmptyAddressTree()
                }
                loadCombinedStreetList()
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                mutableState.update {
                    it.copy(loginBusy = false, loginError = safeLoginMessage(error))
                }
            }
        }
    }

    /** Заменяет настоящие аккаунты вымышленными данными для проверки интерфейса. */
    fun openDemo() {
        if (!BuildConfig.DEBUG) return
        mutableState.update {
            it.copy(
                accounts = repository.demoAccounts(),
                showLogin = false,
                loginError = null,
                accountStatuses = repository.demoAccounts().associate { account ->
                    account.id to AccountAccessStatus.ACTIVE
                },
            ).withEmptyAddressTree()
        }
        loadCombinedStreetList()
    }

    /** Открывает форму добавления ещё одного логина. */
    fun showAddLoginAndPassword() {
        mutableState.update { it.copy(showLogin = true, loginError = null) }
    }

    /** Закрывает форму, если в приложении уже есть хотя бы один аккаунт. */
    fun cancelAddingLogin() {
        if (mutableState.value.accounts.isNotEmpty()) {
            mutableState.update { it.copy(showLogin = false, loginError = null) }
        }
    }

    /** Удаляет только аккаунт с подтверждённо закончившейся сессией. */
    fun removeAccount(accountId: String) {
        val account = mutableState.value.accounts.firstOrNull { it.id == accountId } ?: return
        if (!account.isDemo &&
            mutableState.value.accountStatuses[accountId]
                ?.allowsAccountRecoveryActions() != true
        ) {
            eventChannel.trySend(AppEvent.Message("Удаление доступно только для потерянного доступа"))
            return
        }
        if (!account.isDemo) {
            // Скрываем действия сразу, чтобы второе быстрое нажатие не запустило удаление ещё раз.
            mutableState.update {
                it.copy(
                    accountStatuses = it.accountStatuses +
                        (accountId to AccountAccessStatus.CHECKING),
                )
            }
        }
        viewModelScope.launch {
            if (!account.isDemo) {
                val latestStatus = repository.checkAccess(accountId)
                if (latestStatus != AccountAccessStatus.NEEDS_PASSWORD) {
                    mutableState.update {
                        it.copy(accountStatuses = it.accountStatuses + (accountId to latestStatus))
                    }
                    eventChannel.send(
                        AppEvent.Message("Учётка снова отвечает. Удаление отменено"),
                    )
                    return@launch
                }
            }
            // Репозиторий ещё раз проверяет защиту прямо перед удалением.
            val removed = runCatching { repository.signOut(accountId) }.isSuccess
            if (!removed) {
                val latestStatus = repository.checkAccess(accountId)
                mutableState.update {
                    it.copy(accountStatuses = it.accountStatuses + (accountId to latestStatus))
                }
                eventChannel.send(AppEvent.Message("Удаление отменено: доступ не потерян"))
                return@launch
            }
            val remainingAccounts = mutableState.value.accounts.filterNot { it.id == accountId }
            mutableState.update {
                it.copy(
                    accounts = remainingAccounts,
                    showLogin = remainingAccounts.isEmpty(),
                    accountStatuses = it.accountStatuses - accountId,
                    passwordRenewalAccountId = if (it.passwordRenewalAccountId == accountId) {
                        null
                    } else {
                        it.passwordRenewalAccountId
                    },
                    passwordRenewalBusy = false,
                    passwordRenewalError = null,
                ).withEmptyAddressTree()
            }
            if (remainingAccounts.isNotEmpty()) loadCombinedStreetList()
        }
    }

    /** Сохраняет тему и сразу применяет её ко всему приложению. */
    fun setThemeMode(mode: ThemeMode) {
        // Переключатель этой настройки показывается только на экране «Настройки».
        preferences.saveThemeMode(mode)
        mutableState.update { it.copy(themeMode = mode) }
    }

    /** Забывает старый каталог и заново читает доступы с сервера. */
    fun refreshAddresses() {
        if (mutableState.value.streetsLoading || mutableState.value.accounts.isEmpty()) return
        // Очищаем старую копию, иначе кнопка показала бы тот же список из памяти.
        repository.clearCachedCatalogs()
        mutableState.update { it.withEmptyAddressTree() }
        checkAccountAccesses(loadAddressesAfter = true)
    }

    /** Повторно проверяет состояние всех сохранённых логинов. */
    fun refreshAccountAccesses() {
        checkAccountAccesses(loadAddressesAfter = true)
    }

    /** Открывает ввод пароля для старой записи или после окончательной потери доступа. */
    fun beginPasswordRenewal(accountId: String) {
        val account = mutableState.value.accounts.firstOrNull { it.id == accountId } ?: return
        val maySaveOldAccountPassword = !account.isDemo && !account.hasSavedPassword
        val mayReplaceRejectedPassword = mutableState.value.accountStatuses[accountId]
            ?.allowsAccountRecoveryActions() == true
        if (!maySaveOldAccountPassword && !mayReplaceRejectedPassword) {
            eventChannel.trySend(AppEvent.Message("Сохранённый пароль ещё действует"))
            return
        }
        mutableState.update {
            it.copy(
                passwordRenewalAccountId = accountId,
                passwordRenewalBusy = false,
                passwordRenewalError = null,
            )
        }
    }

    /** Закрывает окно нового пароля, пока запрос ещё не начался. */
    fun cancelPasswordRenewal() {
        if (mutableState.value.passwordRenewalBusy) return
        mutableState.update {
            it.copy(passwordRenewalAccountId = null, passwordRenewalError = null)
        }
    }

    /** Проверяет пароль, шифрует его и получает совершенно новую серверную сессию. */
    fun renewPassword(newPassword: String) {
        val current = mutableState.value
        val accountId = current.passwordRenewalAccountId ?: return
        val account = current.accounts.firstOrNull { it.id == accountId } ?: return
        if (current.passwordRenewalBusy) return
        if (newPassword.isBlank()) {
            mutableState.update { it.copy(passwordRenewalError = "Введи новый пароль") }
            return
        }
        val firstPasswordSave = !account.hasSavedPassword
        if (!firstPasswordSave &&
            current.accountStatuses[accountId]?.allowsAccountRecoveryActions() != true
        ) {
            mutableState.update {
                it.copy(passwordRenewalError = "Доступ уже действует. Менять пароль нельзя")
            }
            return
        }

        mutableState.update { it.copy(passwordRenewalBusy = true, passwordRenewalError = null) }
        viewModelScope.launch {
            try {
                val updated = retryPolicy.execute(RequestKind.LOGIN) {
                    if (firstPasswordSave) {
                        repository.savePasswordForAutomaticLogin(account.id, newPassword)
                    } else {
                        repository.renewPassword(account.id, newPassword)
                    }
                }
                val accounts = mutableState.value.accounts.map { saved ->
                    if (saved.id == accountId) updated else saved
                }.distinctBy(AccountProfile::id)
                mutableState.update {
                    it.copy(
                        accounts = accounts,
                        accountStatuses = (it.accountStatuses - accountId) +
                            (updated.id to AccountAccessStatus.ACTIVE),
                        passwordRenewalAccountId = null,
                        passwordRenewalBusy = false,
                        passwordRenewalError = null,
                    ).withEmptyAddressTree()
                }
                loadCombinedStreetList()
                eventChannel.send(AppEvent.Message("Доступ для логина обновлён"))
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                val becameActive = error is ActiveSessionPasswordProtectedException
                mutableState.update {
                    it.copy(
                        accountStatuses = if (becameActive) {
                            it.accountStatuses + (accountId to AccountAccessStatus.ACTIVE)
                        } else {
                            it.accountStatuses
                        },
                        passwordRenewalBusy = false,
                        passwordRenewalError = safePasswordRenewalMessage(error),
                    )
                }
            }
        }
    }

    /** Открывает город или посёлок и закрывает старую адресную ветку. */
    fun toggleLocality(locality: Locality) {
        if (mutableState.value.selection.localityId != locality.id) loadLocalityHouses(locality.id)
        val selection = mutableState.value.selection.toggleLocality(locality.id)
        cameraJob?.cancel()
        preferences.saveAddressSelection(selection)
        mutableState.update {
            it.copy(
                selection = selection,
                cameraStates = emptyMap(),
                shownCode = null,
            )
        }
    }

    /** Повторяет загрузку адресов улицы после ошибки без лишнего уровня дерева. */
    fun toggleStreet(street: Street) {
        if (mutableState.value.housesByStreet[street.id] == null) loadCombinedHouseList(street)
    }

    /** Раскрывает дом и при первом открытии загружает его подъезды. */
    fun toggleHouse(streetId: String, house: House) {
        val opening = mutableState.value.selection.houseId != house.id
        // Улица уже загружена, поэтому по ней без нового запроса находим родительский город.
        val localityId = mutableState.value.streets.firstOrNull { it.id == streetId }
            ?.locality?.id ?: return
        val selection = mutableState.value.selection.toggleHouse(
            localityId,
            streetId,
            house.id,
        )
        cameraJob?.cancel()
        preferences.saveAddressSelection(selection)
        mutableState.update {
            it.copy(
                selection = selection,
                cameraStates = emptyMap(),
                shownCode = null,
            )
        }
        if (
            opening &&
            mutableState.value.entrancesByHouse[house.id] == null &&
            house.id !in mutableState.value.loadingHouseIds
        ) {
            loadCombinedEntranceList(house)
        }
    }

    /** Раскрывает подъезд и запускает камеру, если она разрешена. */
    fun toggleEntrance(streetId: String, houseId: String, entrance: Entrance) {
        val opening = mutableState.value.selection.entranceId != entrance.id
        val localityId = mutableState.value.streets.firstOrNull { it.id == streetId }
            ?.locality?.id ?: return
        val selection = mutableState.value.selection.toggleEntrance(
            localityId,
            streetId,
            houseId,
            entrance.id,
        )
        cameraJob?.cancel()
        preferences.saveAddressSelection(selection)
        mutableState.update {
            it.copy(
                selection = selection,
                cameraStates = emptyMap(),
                shownCode = null,
            )
        }
        if (opening && entrance.cameraAvailable) loadCameraStream(entrance)
    }

    /** Ждёт окончания набора и запускает один общий поиск по аккаунтам. */
    fun updateSearchQuery(query: String) {
        mutableState.update {
            it.copy(searchQuery = query, searchResults = emptyList(), searchBusy = query.isNotBlank())
        }
        searchJob?.cancel()
        if (query.isBlank()) return

        searchJob = viewModelScope.launch {
            // Ждём, пока человек допечатает слово, и только потом обращаемся к серверу.
            delay(240)
            try {
                val results = combinedAccess.search(activeAccounts(), query)
                if (mutableState.value.searchQuery == query) {
                    mutableState.update { it.copy(searchResults = results, searchBusy = false) }
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                mutableState.update { it.copy(searchBusy = false) }
                eventChannel.send(AppEvent.Message("Поиск сейчас недоступен"))
            }
        }
    }

    /** Загружает найденный адрес и раскрывает его до уровня дома. */
    fun openSearchResult(result: AddressSearchResult) {
        viewModelScope.launch {
            mutableState.update { it.copy(searchQuery = "", searchResults = emptyList()) }
            try {
                val houses = combinedAccess.loadHouses(result.street)
                val selectedHouse = houses.firstOrNull {
                    AddressSearch.canonical(it.label) == AddressSearch.canonical(result.house.label)
                } ?: result.house
                val entrances = combinedAccess.loadEntrances(selectedHouse)
                val selection = AccordionSelection(
                    streetId = result.street.id,
                    houseId = selectedHouse.id,
                    localityId = result.street.locality.id,
                )
                preferences.saveAddressSelection(selection)

                mutableState.update {
                    it.copy(
                        streets = mergeMissingStreet(it.streets, result.street),
                        housesByStreet = it.housesByStreet + (result.street.id to houses),
                        entrancesByHouse = it.entrancesByHouse + (selectedHouse.id to entrances),
                        selection = selection,
                        cameraStates = emptyMap(),
                    )
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                eventChannel.send(AppEvent.Message("Не удалось открыть найденный адрес"))
            }
        }
    }

    /** Блокирует повторное нажатие и отправляет ровно одну команду двери. */
    fun openDoor(entrance: Entrance) {
        val doorState = mutableState.value.doorActions[entrance.id]
        val codeState = mutableState.value.codeActions[entrance.id]
        if (doorState == DoorActionState.Sending || codeState == CodeActionState.Loading) return

        // Блокируем кнопку ещё до запуска фоновой работы: быстрое второе нажатие уже не пройдёт.
        mutableState.update {
            it.copy(doorActions = it.doorActions + (entrance.id to DoorActionState.Sending))
        }
        viewModelScope.launch {
            try {
                val result = combinedAccess.openDoor(activeAccounts(), entrance)
                applyDoorResult(entrance.id, result)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                // Не знаем, дошла ли команда. Поэтому сами второй раз её не отправляем.
                mutableState.update {
                    it.copy(
                        doorActions = it.doorActions +
                            (entrance.id to DoorActionState.Uncertain("Ответ сервера не получен")),
                    )
                }
            }
        }
    }

    /** Читает код выбранного подъезда, пока соседняя команда двери не выполняется. */
    fun requestTemporalCode(entrance: Entrance) {
        val doorState = mutableState.value.doorActions[entrance.id]
        val codeState = mutableState.value.codeActions[entrance.id]
        if (doorState == DoorActionState.Sending || codeState == CodeActionState.Loading) return

        // Код тоже запрашиваем только один раз, даже если человек быстро нажал дважды.
        mutableState.update {
            it.copy(codeActions = it.codeActions + (entrance.id to CodeActionState.Loading))
        }
        viewModelScope.launch {
            try {
                val code = combinedAccess.getTemporalCode(activeAccounts(), entrance)
                if (code == null) {
                    setCodeError(entrance.id, "Код недоступен")
                } else {
                    mutableState.update {
                        it.copy(
                            codeActions = it.codeActions + (entrance.id to CodeActionState.Idle),
                            shownCode = code,
                        )
                    }
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                setCodeError(entrance.id, safeCodeMessage(error))
            }
        }
    }

    /** Закрывает окно и сразу забывает показанный код в оперативной памяти. */
    fun closeTemporalCode() {
        // Код больше не нужен на экране, поэтому сразу забываем его в памяти.
        mutableState.update { it.copy(shownCode = null) }
    }

    /** Повторно получает ссылку только для всё ещё раскрытого подъезда. */
    fun retryCamera(entrance: Entrance) {
        if (mutableState.value.selection.entranceId == entrance.id) loadCameraStream(entrance)
    }

    /** Останавливает видео и убирает код, когда приложение свернули. */
    fun onAppBackgrounded() {
        // Поток и показанный код исчезают, когда приложение ушло с экрана.
        cameraJob?.cancel()
        mutableState.update { it.copy(cameraStates = emptyMap(), shownCode = null) }
    }

    /** После возврата на экран заново запускает ранее открытую камеру. */
    fun onAppForegrounded() {
        val entranceId = mutableState.value.selection.entranceId ?: return
        val entrance = mutableState.value.entrancesByHouse.values
            .flatten()
            .firstOrNull { it.id == entranceId && it.cameraAvailable }
            ?: return
        if (mutableState.value.cameraStates[entranceId] == null) loadCameraStream(entrance)
    }

    /** Загружает верхнюю часть общего адресного дерева. */
    private fun loadCombinedStreetList() {
        treeLoadingJob?.cancel()
        treeLoadingJob = viewModelScope.launch {
            mutableState.update { it.copy(streetsLoading = true) }
            try {
                val accounts = activeAccounts()
                if (accounts.isEmpty()) {
                    mutableState.update { it.copy(streets = emptyList(), streetsLoading = false) }
                    return@launch
                }
                val streets = combinedAccess.loadStreets(accounts)
                mutableState.update { it.copy(streets = streets, streetsLoading = false) }
                restoreSavedAddressSelection(streets)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                mutableState.update { it.copy(streetsLoading = false) }
                eventChannel.send(AppEvent.Message(safeAddressLoadMessage(error)))
            }
        }
    }

    /** Загружает и кладёт в состояние дома одной общей улицы. */
    private fun loadCombinedHouseList(street: Street) {
        if (street.id in mutableState.value.loadingStreetIds) return
        mutableState.update { it.copy(loadingStreetIds = it.loadingStreetIds + street.id) }
        viewModelScope.launch {
            try {
                val houses = combinedAccess.loadHouses(street)
                mutableState.update {
                    it.copy(
                        housesByStreet = it.housesByStreet + (street.id to houses),
                        loadingStreetIds = it.loadingStreetIds - street.id,
                    )
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                mutableState.update { it.copy(loadingStreetIds = it.loadingStreetIds - street.id) }
                eventChannel.send(AppEvent.Message("Не удалось загрузить дома"))
            }
        }
    }

    /** Город сразу показывает все свои дома; отдельного раскрытия улицы больше нет. */
    private fun loadLocalityHouses(localityId: String, excludeStreet: String? = null) {
        mutableState.value.streets.filter { it.locality.id == localityId && it.id != excludeStreet &&
            mutableState.value.housesByStreet[it.id] == null }.forEach(::loadCombinedHouseList)
    }

    /** Загружает и кладёт в состояние подъезды одного общего дома. */
    private fun loadCombinedEntranceList(house: House) {
        viewModelScope.launch {
            mutableState.update { it.copy(loadingHouseIds = it.loadingHouseIds + house.id) }
            try {
                val entrances = combinedAccess.loadEntrances(house)
                mutableState.update {
                    it.copy(
                        entrancesByHouse = it.entrancesByHouse + (house.id to entrances),
                        loadingHouseIds = it.loadingHouseIds - house.id,
                    )
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                mutableState.update { it.copy(loadingHouseIds = it.loadingHouseIds - house.id) }
                eventChannel.send(AppEvent.Message("Не удалось загрузить подъезды"))
            }
        }
    }

    /** Получает свежую ссылку камеры и игнорирует результат уже закрытой карточки. */
    private fun loadCameraStream(entrance: Entrance) {
        cameraJob?.cancel()
        mutableState.update {
            it.copy(cameraStates = mapOf(entrance.id to CameraState.Loading))
        }
        cameraJob = viewModelScope.launch {
            try {
                val stream = combinedAccess.getCameraStream(activeAccounts(), entrance)
                if (mutableState.value.selection.entranceId != entrance.id) return@launch
                val state = stream?.let(CameraState::Ready)
                    ?: CameraState.Failed("У этой двери нет видеопотока")
                mutableState.update { it.copy(cameraStates = mapOf(entrance.id to state)) }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                if (mutableState.value.selection.entranceId == entrance.id) {
                    mutableState.update {
                        it.copy(
                            cameraStates = mapOf(
                                entrance.id to CameraState.Failed(safeCameraMessage(error)),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Загружает сохранённую ветку сверху вниз, потому что каждый следующий список зависит от неё. */
    private suspend fun restoreSavedAddressSelection(streets: List<Street>) {
        val saved = preferences.loadAddressSelection()
        // В старых версиях город ещё не сохранялся. Тогда узнаём его по сохранённой улице.
        val street = streets.findSavedStreet(saved.streetId)
        val localityId = saved.localityId ?: street?.locality?.id ?: return
        if (streets.none { it.locality.id == localityId }) return
        mutableState.update {
            it.copy(selection = AccordionSelection(localityId = localityId))
        }
        loadLocalityHouses(localityId, street?.id?.takeIf { saved.mustLoadHouses() })
        if (street == null || !saved.mustLoadHouses()) return
        mutableState.update {
            it.copy(
                selection = AccordionSelection(
                    streetId = street.id,
                    localityId = street.locality.id,
                ),
            )
        }

        // Раскрытая улица обязана восстановить список домов, даже если дом не был выбран.
        val houses = combinedAccess.loadHouses(street)
        mutableState.update { it.copy(housesByStreet = it.housesByStreet + (street.id to houses)) }
        if (saved.houseId == null) return
        // Конец идентификатора содержит понятную подпись. Это помогает пережить смену схемы адреса.
        val house = houses.findSavedItem(saved.houseId, House::id, House::label) ?: return
        mutableState.update {
            it.copy(
                selection = AccordionSelection(
                    streetId = street.id,
                    houseId = house.id,
                    localityId = street.locality.id,
                ),
            )
        }
        if (!saved.mustLoadEntrances()) return

        // Раскрытый дом так же всегда восстанавливает список подъездов.
        val entrances = combinedAccess.loadEntrances(house)
        mutableState.update {
            it.copy(entrancesByHouse = it.entrancesByHouse + (house.id to entrances))
        }
        if (saved.entranceId == null) return
        val entrance = entrances.findSavedItem(
            saved.entranceId,
            Entrance::id,
            Entrance::label,
        ) ?: return
        mutableState.update {
            it.copy(
                selection = AccordionSelection(
                    streetId = street.id,
                    houseId = house.id,
                    entranceId = entrance.id,
                    localityId = street.locality.id,
                ),
            )
        }
        if (entrance.cameraAvailable) loadCameraStream(entrance)
    }

    /** Находит улицу и из новой версии, и из старого сохранённого списка без города. */
    private fun List<Street>.findSavedStreet(savedId: String?): Street? {
        if (savedId == null) return null
        firstOrNull { it.id == savedId }?.let { return it }
        val oldStreetName = savedId.removePrefix("street:")
        return firstOrNull {
            val currentName = AddressSearch.canonical(it.name)
            oldStreetName == currentName || oldStreetName.endsWith(currentName)
        }
    }

    /** Сначала сравнивает полный номер, а затем его понятный хвост: дом или подъезд. */
    private fun <T> List<T>.findSavedItem(
        savedId: String,
        idOf: (T) -> String,
        labelOf: (T) -> String,
    ): T? = firstOrNull { idOf(it) == savedId } ?: firstOrNull {
        savedId.endsWith(":" + AddressSearch.canonical(labelOf(it)))
    }

    /** Переводит ответ двери в цвет и текст кнопки на экране. */
    private suspend fun applyDoorResult(entranceId: String, result: DoorOpenResult) {
        when (result) {
            DoorOpenResult.Confirmed -> {
                mutableState.update {
                    it.copy(doorActions = it.doorActions + (entranceId to DoorActionState.Opened))
                }
                delay(1_800)
                mutableState.update {
                    if (it.doorActions[entranceId] == DoorActionState.Opened) {
                        it.copy(doorActions = it.doorActions + (entranceId to DoorActionState.Idle))
                    } else {
                        it
                    }
                }
            }
            is DoorOpenResult.Rejected -> mutableState.update {
                it.copy(
                    doorActions = it.doorActions +
                        (entranceId to DoorActionState.Failed(result.message)),
                )
            }
            is DoorOpenResult.Uncertain -> mutableState.update {
                it.copy(
                    doorActions = it.doorActions +
                        (entranceId to DoorActionState.Uncertain(result.message)),
                )
            }
        }
    }

    /** Сохраняет безопасное сообщение рядом с кнопкой кода. */
    private fun setCodeError(entranceId: String, message: String) {
        mutableState.update {
            it.copy(
                codeActions = it.codeActions +
                    (entranceId to CodeActionState.Failed(message)),
            )
        }
    }

    /** Убирает технические подробности из ошибки входа. */
    private fun safeLoginMessage(error: Throwable): String = when (error) {
        is DomruAuthenticationException -> error.message ?: "Сервер не разрешил вход"
        is SessionStorageException -> "Вход выполнен, но телефон не смог сохранить сессию"
        else -> "Добавить логин не удалось. Проверь интернет и данные"
    }

    /** Переводит ошибку загрузки адресов в понятный текст. */
    private fun safeAddressLoadMessage(error: Throwable): String = when (error) {
        is DomruAuthenticationException -> error.message ?: "Нужно снова войти в аккаунт"
        is PortalRequestException -> error.message ?: "Портал не отдал список объектов"
        is PortalProtocolException -> error.message ?: "Ответ портала не удалось прочитать"
        else -> "Не удалось загрузить улицы"
    }

    /** Переводит ошибку кода в понятный текст. */
    private fun safeCodeMessage(error: Throwable): String = when (error) {
        is DomruAuthenticationException -> error.message ?: "Нужно снова войти в аккаунт"
        is PortalRequestException -> error.message ?: "Код для этой двери недоступен"
        is PortalProtocolException -> error.message ?: "Ответ с кодом не удалось прочитать"
        else -> "Код получить не удалось. Проверь интернет"
    }

    /** Переводит ошибку нового пароля в понятный текст. */
    private fun safePasswordRenewalMessage(error: Throwable): String = when (error) {
        is ActiveSessionPasswordProtectedException -> error.message.orEmpty()
        is PasswordRenewalUnavailableException -> error.message.orEmpty()
        is DomruAuthenticationException -> error.message ?: "Новый пароль не принят"
        else -> "Обновить пароль не удалось. Проверь интернет"
    }

    /** Возвращает только аккаунты, запросы через которые сейчас разрешены. */
    private fun activeAccounts(): List<AccountProfile> = mutableState.value.accounts.filter {
        it.isDemo || mutableState.value.accountStatuses[it.id] == AccountAccessStatus.ACTIVE
    }

    /** Проверяет логины параллельно и по желанию обновляет адресное дерево. */
    private fun checkAccountAccesses(loadAddressesAfter: Boolean) {
        if (accountCheckJob?.isActive == true) return
        val accounts = mutableState.value.accounts
        if (accounts.isEmpty()) return
        mutableState.update { state ->
            state.copy(
                accountStatuses = state.accountStatuses + accounts.associate { account ->
                    account.id to if (account.isDemo) {
                        AccountAccessStatus.ACTIVE
                    } else {
                        AccountAccessStatus.CHECKING
                    }
                },
            )
        }
        accountCheckJob = viewModelScope.launch {
            val statuses = coroutineScope {
                accounts.map { account ->
                    async { account.id to repository.checkAccess(account.id) }
                }.awaitAll().toMap()
            }
            mutableState.update { it.copy(accountStatuses = it.accountStatuses + statuses) }
            if (loadAddressesAfter) {
                repository.clearCachedCatalogs()
                mutableState.update { it.withEmptyAddressTree() }
                loadCombinedStreetList()
            }
        }
    }

    /** Переводит ошибку камеры в понятный текст. */
    private fun safeCameraMessage(error: Throwable): String = when (error) {
        is DomruAuthenticationException -> error.message ?: "Нужно снова войти в аккаунт"
        is PortalRequestException -> error.message ?: "Сервер не отдал видеопоток"
        is PortalProtocolException -> error.message ?: "Ответ камеры не удалось прочитать"
        else -> "Видео получить не удалось. Проверь интернет"
    }

    /** Добавляет улицу из поиска, если общего списка ещё не было. */
    private fun mergeMissingStreet(streets: List<Street>, street: Street): List<Street> =
        if (streets.any { it.id == street.id }) {
            streets
        } else {
            // Сначала сортируем города, а внутри каждого города — улицы.
            (streets + street).sortedWith(
                compareBy(
                    { AddressSearch.canonical(it.locality.name) },
                    { AddressSearch.canonical(it.name) },
                ),
            )
        }

    /** Очищает адреса и временные действия, но оставляет аккаунты и тему. */
    private fun AppUiState.withEmptyAddressTree(): AppUiState = copy(
        streets = emptyList(),
        streetsLoading = false,
        housesByStreet = emptyMap(),
        loadingStreetIds = emptySet(),
        entrancesByHouse = emptyMap(),
        loadingHouseIds = emptySet(),
        selection = AccordionSelection(),
        searchQuery = "",
        searchResults = emptyList(),
        searchBusy = false,
        doorActions = emptyMap(),
        codeActions = emptyMap(),
        cameraStates = emptyMap(),
        shownCode = null,
    )

    /** Отменённую работу просто прекращаем и не показываем как сетевую ошибку. */
    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }
}
