import Foundation

@MainActor final class AppModel: ObservableObject {
    @Published private(set) var accounts: [AccountProfile] = []
    @Published private(set) var statuses: [String: AccountStatus] = [:]
    @Published private(set) var catalog = Catalog()
    @Published private(set) var loading = false
    @Published private(set) var started = false
    @Published private(set) var selection: Selection
    @Published private(set) var doors: [String: DoorState] = [:]
    @Published private(set) var codeLoading: String?
    @Published var shownCode: TemporalCode?
    @Published var errorMessage: String?
    @Published var query = "" { didSet { if query != oldValue { camera.stop(); cancelCode() } } }
    let preferences: AppPreferences
    let camera: CameraController
    private let repository: ServiceRepository
    private var cachedAccounts: Set<String> = []
    private var loadTask: Task<Void, Never>?
    private var loadGeneration = UUID()
    private var codeTask: Task<Void, Never>?
    private var codeGeneration = UUID()
    private var actionTasks: [String: Task<Void, Never>] = [:]
    private var actionGenerations: [String: UUID] = [:]

    var isDemo: Bool { !accounts.isEmpty && accounts.allSatisfy(\.isDemo) }
    var usableAccounts: [AccountProfile] {
        accounts.filter { account in
            let status = statuses[account.id]
            return status == .active || (cachedAccounts.contains(account.id) &&
                (status == nil || status == .checking || status == .unavailable))
        }
    }

    init(repository: ServiceRepository = ServiceRepository(catalogStore: CatalogDiskStore()), preferences: AppPreferences) {
        self.repository = repository
        self.preferences = preferences
        selection = preferences.selection
        camera = CameraController(repository: repository)
    }

    func start(demo: Bool = false) async {
        guard !started else { return }
        started = true
        if demo { enterDemo(); return }
        do {
            accounts = try await repository.savedAccounts()
            try await repository.restoreCatalogs()
            guard !isDemo else { return }
            cachedAccounts = await repository.cachedAccountIDs()
            catalog = await repository.cachedCatalog(accounts: accounts)
            // При пустом снимке выбор восстановится после первого ответа сервера.
            if !catalog.streets.isEmpty { restoreSelection() }
            refresh()
        }
        catch { errorMessage = userMessage(error) }
    }

    func enterDemo() {
        loadTask?.cancel(); loadGeneration = UUID(); camera.stop(); cancelCode()
        accounts = DemoCatalog.accounts
        statuses = Dictionary(uniqueKeysWithValues: accounts.map { ($0.id, .active) })
        catalog = DemoCatalog.make()
        selection = Selection()
        query = ""
        loading = false
        errorMessage = nil
    }

    func leaveDemo() async {
        camera.stop(); cancelCode()
        do {
            accounts = try await repository.savedAccounts()
            catalog = Catalog(); selection = preferences.selection; query = ""
            refresh()
        } catch { errorMessage = userMessage(error) }
    }

    func signIn(login: String, password: String) async throws {
        _ = try await repository.signIn(login: login.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
        accounts = try await repository.savedAccounts()
        refresh()
    }

    func renew(_ account: AccountProfile, password: String) async throws {
        try await repository.renewPassword(accountID: account.id, password: password)
        accounts = try await repository.savedAccounts()
        refresh()
    }

    func remove(_ account: AccountProfile) async {
        do {
            try await repository.removeAccount(account.id)
            accounts = try await repository.savedAccounts()
            refresh()
        } catch { errorMessage = userMessage(error) }
    }

    func refresh() {
        guard !isDemo else { catalog = DemoCatalog.make(); return }
        loadTask?.cancel()
        let generation = UUID(); loadGeneration = generation
        let requested = accounts
        statuses = Dictionary(uniqueKeysWithValues: requested.map { ($0.id, .checking) })
        loading = !requested.isEmpty; errorMessage = nil
        loadTask = Task {
            guard !Task.isCancelled, generation == loadGeneration else { return }
            // Сначала исключаем удалённые аккаунты из дерева. Остальные снимки продолжают работать.
            await applyCachedCatalog(requested, generation: generation)
            guard !Task.isCancelled, generation == loadGeneration else { return }
            do {
                let checked = try await withThrowingTaskGroup(of: (String, AccountStatus).self) { group in
                    for account in requested {
                        group.addTask {
                            var status = try await self.repository.checkAccess(account.id)
                            try Task.checkCancellation()
                            if status == .active {
                                do {
                                    if try await self.repository.refreshCatalog(account.id) == false { status = .unavailable }
                                } catch {
                                    try Task.checkCancellation()
                                    status = (error as? ServiceError)?.accountStatus ?? .unavailable
                                }
                            }
                            if status == .needsPassword || status == .accessDenied {
                                await self.repository.forgetCatalog(account.id)
                            }
                            return (account.id, status)
                        }
                    }
                    var result: [String: AccountStatus] = [:]
                    for try await (id, status) in group { result[id] = status }
                    return result
                }
                try Task.checkCancellation()
                guard generation == loadGeneration else { return }
                statuses = checked
                await applyCachedCatalog(requested, generation: generation)
                guard !Task.isCancelled, generation == loadGeneration else { return }
                if checked.values.contains(.unavailable) {
                    errorMessage = catalog.streets.isEmpty ? "Не удалось загрузить доступы. Проверь состояние аккаунтов" :
                        "Не всё удалось обновить. Показываем сохранённые доступы"
                }
            } catch {
                guard !Task.isCancelled, generation == loadGeneration else { return }
                errorMessage = userMessage(error)
            }
            if generation == loadGeneration { loading = false }
        }
    }

    private func applyCachedCatalog(_ requested: [AccountProfile], generation: UUID) async {
        let ids = await repository.cachedAccountIDs()
        let loaded = await repository.cachedCatalog(accounts: requested)
        guard !Task.isCancelled, generation == loadGeneration else { return }
        let oldVisible = catalog.entrances.values.joined().first { $0.id == selection.entranceID }
        let newVisible = loaded.entrances.values.joined().first { $0.id == selection.entranceID }
        cachedAccounts = ids
        catalog = loaded
        // Сохраняем текущий пользовательский выбор, а не заново читаем его с диска.
        if !loaded.streets.isEmpty || !loading || requested.isEmpty || !cachedAccounts.isEmpty { restoreSelection() }
        if oldVisible?.sources != newVisible?.sources { cancelCode() }
        if let newVisible, newVisible.cameraAvailable {
            camera.updateContext(newVisible, accounts: usableAccounts)
        } else if oldVisible != nil { camera.stop() }
    }

    private func restoreSelection() {
        if !catalog.streets.contains(where: { $0.locality.id == selection.localityID }) { selection = Selection() }
        else if !catalog.houses.values.joined().contains(where: { $0.id == selection.houseID }) { selection.houseID = nil; selection.entranceID = nil }
        else if !catalog.entrances.values.joined().contains(where: { $0.id == selection.entranceID }) { selection.entranceID = nil }
    }

    func selectCity(_ locality: Locality) {
        select(selection.localityID == locality.id ? Selection() : Selection(localityID: locality.id))
    }

    func selectHouse(_ house: House, street: Street) {
        select(Selection(localityID: street.locality.id, houseID: selection.houseID == house.id ? nil : house.id))
    }

    func selectEntrance(_ entrance: Entrance) {
        var next = selection
        next.entranceID = selection.entranceID == entrance.id ? nil : entrance.id
        select(next)
    }

    func selectSearchResult(house: House, street: Street) {
        query = ""
        select(Selection(localityID: street.locality.id, houseID: house.id))
    }

    private func select(_ next: Selection) {
        camera.stop(); cancelCode()
        selection = next
        if !isDemo { preferences.selection = next }
    }

    func open(_ entrance: Entrance) {
        guard doors[entrance.id] != .sending, codeLoading != entrance.id else { return }
        actionTasks[entrance.id]?.cancel()
        let generation = UUID()
        actionGenerations[entrance.id] = generation
        doors[entrance.id] = .sending
        let accounts = usableAccounts, demo = isDemo
        actionTasks[entrance.id] = Task {
            do {
                let result: DoorResult
                if demo { try await Task.sleep(nanoseconds: 350_000_000); result = .confirmed }
                else { result = try await repository.openDoor(entrance, accounts: accounts) }
                switch result {
                case .confirmed: doors[entrance.id] = .opened
                case .rejected(let message): doors[entrance.id] = .failed(message)
                case .uncertain(let message): doors[entrance.id] = .uncertain(message)
                }
                if result == .confirmed {
                    try await Task.sleep(nanoseconds: 2_500_000_000)
                    if doors[entrance.id] == .opened { doors[entrance.id] = .idle }
                }
            } catch {
                if !Task.isCancelled { doors[entrance.id] = .uncertain("Ответ сервера не получен") }
            }
            if actionGenerations[entrance.id] == generation {
                actionTasks[entrance.id] = nil
                actionGenerations[entrance.id] = nil
            }
        }
    }

    func requestCode(_ entrance: Entrance) {
        guard doors[entrance.id] != .sending, codeLoading == nil else { return }
        cancelCode()
        let generation = codeGeneration, accounts = usableAccounts, demo = isDemo
        codeLoading = entrance.id
        codeTask = Task {
            do {
                let code = demo ? TemporalCode(value: "1234", updatedAt: Date(), validUntil: nil) :
                    try await repository.code(entrance, accounts: accounts)
                try Task.checkCancellation()
                guard codeGeneration == generation else { return }
                if let code { shownCode = code } else { errorMessage = "Код для этой двери недоступен" }
            } catch {
                if !Task.isCancelled, codeGeneration == generation { errorMessage = userMessage(error) }
            }
            if codeGeneration == generation { codeLoading = nil }
        }
    }

    func background() { camera.setForeground(false); cancelCode() }
    func foreground() { camera.setForeground(true) }

    private func cancelCode() {
        codeTask?.cancel(); codeGeneration = UUID(); codeLoading = nil; shownCode = nil
    }
}
