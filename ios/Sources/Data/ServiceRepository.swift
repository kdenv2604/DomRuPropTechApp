import Foundation

actor ServiceRepository {
    private struct LoadingCatalog { let id: UUID; let task: Task<Catalog, Error> }
    private let vault: SessionVault
    private let catalogStore: CatalogDiskStore?
    private var generations: [String: UUID] = [:]
    private let oidc: OIDCSessionClient
    private let api: PortalAPI
    private var catalogs: [String: Catalog] = [:]
    private var loading: [String: LoadingCatalog] = [:]

    init(vault: SessionVault = SessionVault(), transport: HTTPTransport = URLSessionTransport(),
         catalogStore: CatalogDiskStore? = nil) {
        self.vault = vault
        self.catalogStore = catalogStore
        let oidc = OIDCClient(transport: transport)
        self.oidc = oidc
        api = PortalAPI(transport: transport, sessions: SessionProvider(vault: vault, oidc: oidc))
    }

    func restoreCatalogs() async throws {
        for session in try await vault.all() {
            let generation = generations[session.accountID]
            guard let saved = catalogStore?.read(accountID: session.accountID, binding: session.catalogBinding) else { continue }
            let current = try await vault.find(session.accountID)
            try Task.checkCancellation()
            if generations[session.accountID] == generation, current?.catalogBinding == session.catalogBinding,
               catalogs[session.accountID] == nil { catalogs[session.accountID] = saved }
        }
    }

    func cachedAccountIDs() -> Set<String> { Set(catalogs.keys) }

    func cachedCatalog(accounts: [AccountProfile]) -> Catalog {
        AddressHierarchyMerger.combine(accounts.compactMap { account in
            catalogs[account.id].map { (account.id, $0) }
        })
    }

    func forgetCatalog(_ accountID: String) { invalidate(accountID) }

    func refreshCatalog(_ accountID: String) async throws -> Bool {
        try await catalog(accountID, refresh: true).complete
    }

    func savedAccounts() async throws -> [AccountProfile] { try await vault.all().map(\.profile) }

    func signIn(login: String, password: String) async throws -> AccountProfile {
        let session = try await oidc.signIn(login: login, password: password, previous: nil)
        try Task.checkCancellation()
        let previous = try await vault.find(session.accountID)
        let stored = StoredSession(accountID: session.accountID, title: session.title, login: session.login,
                                   accessToken: session.accessToken, refreshToken: session.refreshToken, password: password,
                                   expiresAt: session.expiresAt, createdAt: previous?.createdAt ?? session.createdAt)
        try await vault.save(stored)
        invalidate(session.accountID)
        return stored.profile
    }

    func checkAccess(_ accountID: String) async throws -> AccountStatus {
        do { _ = try await api.specifications(accountID: accountID); return .active }
        catch {
            try Task.checkCancellation()
            return (error as? ServiceError)?.accountStatus ?? .unavailable
        }
    }

    func renewPassword(accountID: String, password: String) async throws {
        guard let old = try await vault.find(accountID) else { throw ServiceError.authentication(.sessionExpired) }
        try await requireLostAccess(accountID)
        let session = try await oidc.signIn(login: old.login, password: password, previous: old)
        try Task.checkCancellation()
        guard try await vault.replace(old, with: session) else { throw ServiceError.conflict }
        invalidate(accountID)
    }

    func removeAccount(_ accountID: String) async throws {
        guard let old = try await vault.find(accountID) else { throw ServiceError.authentication(.sessionExpired) }
        try await requireLostAccess(accountID)
        if let token = old.refreshToken {
            do { try await oidc.signOut(refreshToken: token) }
            catch { try Task.checkCancellation() }
        }
        guard try await vault.replace(old, with: nil) else { throw ServiceError.conflict }
        invalidate(accountID)
    }

    private func requireLostAccess(_ accountID: String) async throws {
        switch try await checkAccess(accountID) {
        case .needsPassword: return
        case .active: throw ServiceError.protectedAccount("Рабочую учётку менять или удалять нельзя")
        case .accessDenied: throw ServiceError.protectedAccount("Пароль не поможет: сервер запретил доступ аккаунту")
        default: throw ServiceError.protectedAccount("Сначала нужно подтвердить, что прежняя сессия действительно закончилась")
        }
    }

    func clearCatalogs() {
        catalogs.removeAll()
        for item in loading.values { item.task.cancel() }
        loading.removeAll()
    }

    private func invalidate(_ accountID: String) {
        generations[accountID] = UUID()
        catalogStore?.remove(accountID)
        catalogs[accountID] = nil
        loading.removeValue(forKey: accountID)?.task.cancel()
    }

    func catalog(_ accountID: String, refresh: Bool = false) async throws -> Catalog {
        if !refresh, let cached = catalogs[accountID] { return cached }
        if let pending = loading[accountID] {
            let result = try await pending.task.value
            try Task.checkCancellation()
            return result
        }
        let id = UUID(), generation = generations[accountID], previous = catalogs[accountID]
        let task = Task {
            guard let session = try await vault.find(accountID) else { throw ServiceError.authentication(.sessionExpired) }
            let result = try await Self.loadCatalog(api: api, accountID: accountID, previous: previous)
            try Task.checkCancellation()
            guard let current = try await vault.find(accountID),
                  current.catalogBinding == session.catalogBinding, generations[accountID] == generation else {
                throw CancellationError()
            }
            return result
        }
        loading[accountID] = LoadingCatalog(id: id, task: task)
        defer { if loading[accountID]?.id == id { loading[accountID] = nil } }
        let result = try await task.value
        try Task.checkCancellation()
        guard loading[accountID]?.id == id else { throw CancellationError() }
        guard let session = try await vault.find(accountID) else { throw CancellationError() }
        try Task.checkCancellation()
        guard loading[accountID]?.id == id, generations[accountID] == generation else { throw CancellationError() }
        catalogs[accountID] = result
        // Диск не является условием работы: при его ошибке используем свежий снимок в памяти.
        try? catalogStore?.write(accountID: accountID, binding: session.catalogBinding, catalog: result)
        return result
    }

    private static func loadCatalog(api: PortalAPI, accountID: String, previous: Catalog?) async throws -> Catalog {
        let specifications = try await api.specifications(accountID: accountID)
        var builder = RemoteCatalogBuilder()
        var retained: [Catalog] = []
        var failures: [Error] = []
        var successful = 0
        for specification in specifications {
            try Task.checkCancellation()
            do {
                var locality = specification.locality
                if locality == nil { locality = try? await api.companyLocality(accountID: accountID, specification: specification) }
                try Task.checkCancellation()
                let houses = try await api.houses(accountID: accountID, specification: specification)
                let porches = try await api.porches(accountID: accountID, specification: specification)
                let devices = try await api.devices(accountID: accountID, specification: specification)
                builder.add(specification: specification, locality: locality, houses: houses, porches: porches, devices: devices)
                successful += 1
            } catch {
                try Task.checkCancellation()
                if let serviceError = error as? ServiceError {
                    switch serviceError {
                    case .http(401, _), .authentication(.sessionExpired), .authentication(.invalidCredentials):
                        // 401 относится ко всей учётке; AppModel удалит её снимок после проверки статуса.
                        throw error
                    default:
                        break
                    }
                }
                failures.append(error)
                let revoked: Bool
                if case ServiceError.http(let status, _) = error { revoked = [403, 404].contains(status) }
                else { revoked = false }
                if !revoked, let previous { retained.append(previous.forSpecification(specification.id)) }
            }
        }
        if successful == 0, retained.isEmpty,
           let error = preferredFailure(failures.filter {
               if case ServiceError.http(let status, _) = $0 { return ![403, 404].contains(status) }
               return true
           }) { throw error }
        var result = builder.build(complete: failures.isEmpty)
        for old in retained { result.retain(old) }
        return result
    }

    func combinedCatalog(accounts: [AccountProfile]) async throws -> Catalog {
        // Сохраняем видимый порядок аккаунтов независимо от порядка сетевых ответов.
        let results = await withTaskGroup(of: (Int, String, Result<Catalog, Error>).self) { group in
            for (index, account) in accounts.enumerated() {
                group.addTask {
                    do { return (index, account.id, .success(try await RetryPolicy.read { try await self.catalog(account.id) })) }
                    catch { return (index, account.id, .failure(error)) }
                }
            }
            var collected: [(Int, String, Result<Catalog, Error>)] = []
            for await result in group { collected.append(result) }
            return collected.sorted { $0.0 < $1.0 }
        }
        try Task.checkCancellation()
        let loaded = results.compactMap { item -> (String, Catalog)? in
            guard case .success(let catalog) = item.2 else { return nil }
            return (item.1, catalog)
        }
        let failures = results.compactMap { item -> Error? in if case .failure(let error) = item.2 { return error }; return nil }
        if loaded.isEmpty, let error = Self.preferredFailure(failures) { throw error }
        var combined = AddressHierarchyMerger.combine(loaded)
        combined.complete = combined.complete && failures.isEmpty
        return combined
    }

    func openDoor(_ entrance: Entrance, accounts: [AccountProfile]) async throws -> DoorResult {
        guard let source = sources(entrance, accounts: accounts).first,
              let door = try await catalog(source.accountID).doors[source.remoteID] else {
            return .rejected("Для этой двери нет действующего аккаунта")
        }
        // После отправки не пробуем другой аккаунт: первая команда уже могла открыть дверь.
        do { return try await api.openDoor(accountID: source.accountID, door: door) }
        catch {
            try Task.checkCancellation()
            return .uncertain("Ответ сервера не получен")
        }
    }

    func code(_ entrance: Entrance, accounts: [AccountProfile]) async throws -> TemporalCode? {
        guard let source = sources(entrance, accounts: accounts).first else { return nil }
        return try await RetryPolicy.read {
            guard let door = try await self.catalog(source.accountID).doors[source.remoteID] else { return nil }
            return try await self.api.code(accountID: source.accountID, door: door)
        }
    }

    func camera(_ entrance: Entrance, accounts: [AccountProfile], after failed: AccessSource?) async throws -> CameraStream? {
        guard entrance.cameraAvailable else { return nil }
        var allowed = sources(entrance, accounts: accounts).filter(\.canVideo)
        if let failed, let index = allowed.firstIndex(of: failed) {
            allowed = Array(allowed.dropFirst(index + 1)) + Array(allowed.prefix(index + 1))
        }
        var failures: [Error] = []
        for source in allowed {
            do {
                let stream: CameraStream? = try await RetryPolicy.read {
                    guard let door = try await self.catalog(source.accountID).doors[source.remoteID], door.cameraAvailable else { return nil }
                    return try await self.api.camera(accountID: source.accountID, door: door, source: source)
                }
                if let stream { return stream }
            } catch {
                try Task.checkCancellation()
                failures.append(error)
            }
        }
        if let error = Self.preferredFailure(failures) { throw error }
        return nil
    }

    private func sources(_ entrance: Entrance, accounts: [AccountProfile]) -> [AccessSource] {
        accounts.flatMap { account in entrance.sources.filter { $0.accountID == account.id } }
    }

    private static func preferredFailure(_ failures: [Error]) -> Error? {
        failures.last(where: RetryPolicy.isVideoRetryable) ?? failures.last
    }
}
