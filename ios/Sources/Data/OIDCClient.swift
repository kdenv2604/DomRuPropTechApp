import Foundation
import CryptoKit

protocol OIDCSessionClient {
    func signIn(login: String, password: String, previous: StoredSession?) async throws -> StoredSession
    func refresh(_ previous: StoredSession) async throws -> StoredSession
    func signOut(refreshToken: String) async throws
}

struct OIDCClient: OIDCSessionClient {
    let transport: HTTPTransport
    private let base = "https://id.dom.ru/realms/lk/protocol/openid-connect/"

    func signIn(login: String, password: String, previous: StoredSession? = nil) async throws -> StoredSession {
        let json = try await token([("grant_type", "password"), ("scope", "openid"), ("username", login), ("password", password)])
        let access = json.text("access_token")
        guard !access.isEmpty else { throw ServiceError.protocolViolation("Сервер вернул неполную сессию") }
        let claims = Self.claims(json.text("id_token").isEmpty ? access : json.text("id_token"))
        let subject = claims.text("sub")
        let normalized = login.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let fallback = "login-" + SHA256.hash(data: Data(normalized.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
        let name = claims.text("name", "preferred_username")
        return StoredSession(accountID: previous?.accountID ?? (subject.isEmpty ? fallback : subject),
                             title: name.isEmpty ? login : name, login: login, accessToken: access,
                             refreshToken: optional(json.text("refresh_token")), password: password,
                             expiresAt: expiry(json), createdAt: previous?.createdAt ?? Date())
    }

    func refresh(_ previous: StoredSession) async throws -> StoredSession {
        guard let refresh = previous.refreshToken else { throw ServiceError.authentication(.sessionExpired) }
        let json = try await token([("grant_type", "refresh_token"), ("refresh_token", refresh)])
        let access = json.text("access_token")
        guard !access.isEmpty else { throw ServiceError.protocolViolation("Сервер вернул неполную сессию") }
        return StoredSession(accountID: previous.accountID, title: previous.title, login: previous.login,
                             accessToken: access, refreshToken: optional(json.text("refresh_token")) ?? previous.refreshToken,
                             password: previous.password, expiresAt: expiry(json), createdAt: previous.createdAt)
    }

    func signOut(refreshToken: String) async throws {
        let response = try await transport.send(request(path: "logout", values: [("refresh_token", refreshToken)]))
        _ = try response.checked()
    }

    private func token(_ values: [(String, String)]) async throws -> [String: Any] {
        let response = try await transport.send(request(path: "token", values: values))
        let json = try response.object()
        guard (200...299).contains(response.status) else {
            switch json.text("error") {
            case "invalid_grant": throw ServiceError.authentication(.invalidCredentials)
            case "invalid_client", "unauthorized_client": throw ServiceError.authentication(.clientRejected)
            default: throw ServiceError.authentication(.unavailable)
            }
        }
        return json
    }

    private func request(path: String, values: [(String, String)]) -> URLRequest {
        var request = URLRequest(url: URL(string: base + path)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = FormEncoding.encode([("client_id", "lk-frontend")] + values)
        return request
    }

    private func expiry(_ json: [String: Any]) -> Date { Date().addingTimeInterval(max(0, (json["expires_in"] as? Double) ?? 300)) }
    private func optional(_ value: String) -> String? { value.isEmpty ? nil : value }

    private static func claims(_ token: String) -> [String: Any] {
        let parts = token.split(separator: ".")
        guard parts.count > 1 else { return [:] }
        var encoded = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
        guard let data = Data(base64Encoded: encoded), let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        // Поля JWT нужны только для подписи аккаунта; права сервер проверяет по исходному bearer-токену.
        return json
    }
}
