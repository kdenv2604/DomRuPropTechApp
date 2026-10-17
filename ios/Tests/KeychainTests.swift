import XCTest
import Security
@testable import DomruTechnics

final class KeychainTests: XCTestCase {
    func testSeparateVaultRestoresSessionsWithoutOverwritingNewerRevision() async throws {
        let service = "ru.domru.technics.tests." + UUID().uuidString
        defer { SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary) }
        let persistence = KeychainSessionPersistence(service: service)
        let first = SessionVault(persistence: persistence)
        let old = session()
        try await first.save(old)
        let second = SessionVault(persistence: KeychainSessionPersistence(service: service))
        let restored = try await second.find(old.accountID)
        XCTAssertEqual(restored, old)
        let newer = session(token: "new", fresh: true)
        try await second.save(newer)
        let replaced = try await first.replace(old, with: session(token: "late-refresh", fresh: true))
        XCTAssertFalse(replaced)
        let current = try await second.find(old.accountID)
        XCTAssertEqual(current, newer)
    }
}
