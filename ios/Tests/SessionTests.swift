import XCTest
@testable import DomruTechnics

final class MemoryPersistence: SessionPersistence {
    // Доступ только из актора SessionVault, в том числе в тестах.
    private var sessions: [StoredSession]
    init(_ sessions: [StoredSession] = []) { self.sessions = sessions }
    func read() throws -> [StoredSession] { sessions }
    func write(_ sessions: [StoredSession]) throws { self.sessions = sessions }
}

func session(_ id: String = "account-a", token: String = "old", fresh: Bool = false) -> StoredSession {
    StoredSession(accountID: id, title: "Test account", login: "test", accessToken: token, refreshToken: "refresh-" + token,
                  password: "test-password", expiresAt: Date().addingTimeInterval(fresh ? 600 : -60), createdAt: Date(timeIntervalSince1970: 10))
}

actor TestOIDC: OIDCSessionClient {
    var refreshCount = 0
    var loginCount = 0
    var failRefresh = false
    var loginFailure: ServiceError?
    var started: (() -> Void)?
    var delay: UInt64 = 30_000_000

    func configure(failRefresh: Bool = false, loginFailure: ServiceError? = nil, delay: UInt64 = 30_000_000, started: (() -> Void)? = nil) {
        self.failRefresh = failRefresh; self.loginFailure = loginFailure; self.delay = delay; self.started = started
    }
    func refresh(_ previous: StoredSession) async throws -> StoredSession {
        refreshCount += 1; started?()
        try await Task.sleep(nanoseconds: delay)
        if failRefresh { throw URLError(.networkConnectionLost) }
        return session(previous.accountID, token: "refreshed", fresh: true)
    }
    func signIn(login: String, password: String, previous: StoredSession?) async throws -> StoredSession {
        loginCount += 1
        if let loginFailure { throw loginFailure }
        return session(previous?.accountID ?? "new", token: "logged-in", fresh: true)
    }
    func signOut(refreshToken: String) async throws {}
}

final class SessionTests: XCTestCase {
    func testConcurrentExpiredReadsUseOneRefresh() async throws {
        let vault = SessionVault(persistence: MemoryPersistence([session()]))
        let oidc = TestOIDC(), provider = SessionProvider(vault: vault, oidc: oidc)
        let tokens = try await withThrowingTaskGroup(of: String.self) { group in
            for _ in 0..<20 { group.addTask { try await provider.token(for: "account-a") } }
            var values: [String] = []
            for try await value in group { values.append(value) }
            return values
        }
        XCTAssertEqual(Set(tokens), ["refreshed"])
        let count = await oidc.refreshCount
        XCTAssertEqual(count, 1)
    }

    func testDifferentAccountsKeepIndependentTokens() async throws {
        let vault = SessionVault(persistence: MemoryPersistence([session("a", token: "a-token", fresh: true), session("b", token: "b-token", fresh: true)]))
        let provider = SessionProvider(vault: vault, oidc: TestOIDC())
        async let a = provider.token(for: "a")
        async let b = provider.token(for: "b")
        let values = try await [a, b]
        XCTAssertEqual(values, ["a-token", "b-token"])
    }

    func testLateRefreshCannotOverwriteNewLogin() async throws {
        let vault = SessionVault(persistence: MemoryPersistence([session()]))
        let oidc = TestOIDC()
        let started = expectation(description: "refresh started")
        await oidc.configure(delay: 200_000_000, started: { started.fulfill() })
        let provider = SessionProvider(vault: vault, oidc: oidc)
        let pending = Task { try await provider.token(for: "account-a") }
        await fulfillment(of: [started], timeout: 2)
        try await vault.save(session(token: "new-login", fresh: true))
        let result = try await pending.value
        let stored = try await vault.find("account-a")
        XCTAssertEqual(result, "new-login")
        XCTAssertEqual(stored?.accessToken, "new-login")
    }

    func testDeletedAccountCannotBeResurrectedByRefresh() async throws {
        let old = session(), vault = SessionVault(persistence: MemoryPersistence([session()]))
        try await vault.save(old)
        let oidc = TestOIDC(), started = expectation(description: "refresh started")
        await oidc.configure(delay: 150_000_000, started: { started.fulfill() })
        let provider = SessionProvider(vault: vault, oidc: oidc)
        let pending = Task { try await provider.token(for: "account-a") }
        await fulfillment(of: [started], timeout: 2)
        let deleted = try await vault.replace(old, with: nil)
        XCTAssertTrue(deleted)
        do { _ = try await pending.value; XCTFail("Deleted account recovered") } catch {}
        let remaining = try await vault.all()
        XCTAssertTrue(remaining.isEmpty)
    }

    func testOldUnauthorizedResponseUsesAlreadyRotatedToken() async throws {
        let oidc = TestOIDC()
        let provider = SessionProvider(vault: SessionVault(persistence: MemoryPersistence([session(token: "new", fresh: true)])), oidc: oidc)
        let token = try await provider.token(for: "account-a", rejecting: "old")
        XCTAssertEqual(token, "new")
        let count = await oidc.refreshCount
        XCTAssertEqual(count, 0)
    }

    func testInvalidSavedPasswordIsNotRetried() async throws {
        let oidc = TestOIDC()
        await oidc.configure(failRefresh: true, loginFailure: .authentication(.invalidCredentials))
        let provider = SessionProvider(vault: SessionVault(persistence: MemoryPersistence([session()])), oidc: oidc)
        do { _ = try await provider.token(for: "account-a"); XCTFail("Expected invalid password") }
        catch { XCTAssertEqual((error as? ServiceError)?.accountStatus, .needsPassword) }
        let count = await oidc.loginCount
        XCTAssertEqual(count, 1)
    }

    func testTemporaryLoginFailureHasBoundedAttempts() async throws {
        let oidc = TestOIDC()
        await oidc.configure(failRefresh: true, loginFailure: .authentication(.unavailable))
        let provider = SessionProvider(vault: SessionVault(persistence: MemoryPersistence([session()])), oidc: oidc)
        do { _ = try await RetryPolicy.read { try await provider.token(for: "account-a") }; XCTFail("Expected failure") } catch {}
        let count = await oidc.loginCount
        XCTAssertEqual(count, 3, "Outer read retry must not multiply automatic login attempts")
    }
}
