import Foundation
import Security

struct StoredSession: Codable, Equatable {
    var revision = UUID()
    let accountID: String
    let title: String
    let login: String
    let accessToken: String
    let refreshToken: String?
    let password: String?
    let expiresAt: Date
    let createdAt: Date

    var profile: AccountProfile { AccountProfile(id: accountID, title: title, login: login) }
    var isFresh: Bool { expiresAt.timeIntervalSinceNow > 30 }
}

protocol SessionPersistence {
    func read() throws -> [StoredSession]
    func write(_ sessions: [StoredSession]) throws
}

struct KeychainSessionPersistence: SessionPersistence {
    private let service: String

    init(service: String = (Bundle.main.bundleIdentifier ?? "ru.domru.technics") + ".sessions") {
        self.service = service
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: "service-accounts-v1",
         kSecAttrSynchronizable as String: false]
    }

    func read() throws -> [StoredSession] {
        var lookup = query
        lookup[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let data = result as? Data,
              let sessions = try? JSONDecoder().decode([StoredSession].self, from: data) else { throw ServiceError.storage }
        return sessions
    }

    func write(_ sessions: [StoredSession]) throws {
        let data = try JSONEncoder().encode(sessions)
        let update = [kSecValueData as String: data]
        var status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            // Служебный доступ нужен на экране; секреты не синхронизируются и не переносятся на другое устройство.
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw ServiceError.storage }
    }
}

actor SessionVault {
    private let persistence: SessionPersistence
    init(persistence: SessionPersistence = KeychainSessionPersistence()) { self.persistence = persistence }

    func all() throws -> [StoredSession] {
        try persistence.read().sorted { ($0.createdAt, $0.accountID) < ($1.createdAt, $1.accountID) }
    }
    func find(_ id: String) throws -> StoredSession? { try all().first { $0.accountID == id } }

    func save(_ session: StoredSession) throws {
        var sessions = try all().filter { $0.accountID != session.accountID }
        sessions.append(session)
        try persistence.write(sessions)
    }

    @discardableResult func replace(_ old: StoredSession, with new: StoredSession?) throws -> Bool {
        var sessions = try all()
        guard let index = sessions.firstIndex(where: { $0.accountID == old.accountID }),
              sessions[index].revision == old.revision else { return false }
        sessions.remove(at: index)
        if let new { sessions.append(new) }
        try persistence.write(sessions)
        return true
    }
}
