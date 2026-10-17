import Foundation

actor SessionProvider {
    private struct Recovery { let id: UUID; let task: Task<StoredSession, Error> }
    private var recoveries: [String: Recovery] = [:]
    private let vault: SessionVault
    private let oidc: OIDCSessionClient

    init(vault: SessionVault, oidc: OIDCSessionClient) { self.vault = vault; self.oidc = oidc }

    func token(for accountID: String, rejecting rejectedToken: String? = nil) async throws -> String {
        try Task.checkCancellation()
        guard let current = try await vault.find(accountID) else { throw ServiceError.authentication(.sessionExpired) }
        if current.isFresh && (rejectedToken == nil || current.accessToken != rejectedToken) { return current.accessToken }
        if let pending = recoveries[accountID] {
            let result = try await pending.task.value
            try Task.checkCancellation()
            return result.accessToken
        }
        // Во время await актор может принять следующий запрос. Общая задача не позволяет двум
        // запросам обновить один refresh-токен; разные аккаунты восстанавливаются независимо.
        let id = UUID()
        let task = Task { try await Self.recover(current, vault: vault, oidc: oidc) }
        recoveries[accountID] = Recovery(id: id, task: task)
        defer { if recoveries[accountID]?.id == id { recoveries[accountID] = nil } }
        let result = try await task.value
        try Task.checkCancellation()
        return result.accessToken
    }

    private static func recover(_ old: StoredSession, vault: SessionVault, oidc: OIDCSessionClient) async throws -> StoredSession {
        func latest() async throws -> StoredSession {
            guard let current = try await vault.find(old.accountID) else { throw ServiceError.authentication(.sessionExpired) }
            return current
        }
        func commit(_ new: StoredSession) async throws -> StoredSession {
            if try await vault.replace(old, with: new) { return new }
            return try await latest()
        }
        let initial = try await latest()
        if initial.revision != old.revision { return initial }
        if old.refreshToken != nil {
            do { return try await commit(oidc.refresh(old)) }
            catch is CancellationError { throw CancellationError() }
            catch { /* Неудачное обновление сессии можно восстановить сохранённым паролем. */ }
        }
        let afterRefresh = try await latest()
        if afterRefresh.revision != old.revision { return afterRefresh }
        guard let password = old.password, !password.isEmpty else { throw ServiceError.authentication(.sessionExpired) }
        for attempt in 0...2 {
            let current = try await latest()
            if current.revision != old.revision { return current }
            do { return try await commit(oidc.signIn(login: old.login, password: password, previous: old)) }
            catch is CancellationError { throw CancellationError() }
            catch {
                let latestRecord = try await latest()
                if latestRecord.revision != old.revision { return latestRecord }
                if case ServiceError.authentication(let reason) = error,
                   reason == .invalidCredentials || reason == .clientRejected { throw error }
                if attempt == 2 { throw ServiceError.authentication(.unavailable) }
                try await Task.sleep(nanoseconds: attempt == 0 ? 300_000_000 : 900_000_000)
            }
        }
        throw ServiceError.authentication(.unavailable)
    }
}
