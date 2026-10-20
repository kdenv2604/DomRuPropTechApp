import Foundation

enum ThemeMode: String, CaseIterable { case system, light, dark }
enum Handedness: String, CaseIterable { case right, left }

struct AccountProfile: Identifiable, Equatable {
    let id: String
    let title: String
    let login: String
    var isDemo = false
}

enum AccountStatus: String {
    case checking, active, needsPassword, accessDenied, unavailable

    var caption: String {
        switch self {
        case .checking: return "Проверяем доступ…"
        case .active: return "Доступ действует"
        case .needsPassword: return "Доступ потерян — нужен новый пароль"
        case .accessDenied: return "Сервер запретил доступ"
        case .unavailable: return "Не удалось проверить доступ"
        }
    }
}

struct AccessSource: Hashable, Codable {
    let accountID: String
    let remoteID: String
    var canVideo = true
}

struct Locality: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    static let unknown = Locality(id: "locality:unknown", name: "Населённый пункт не указан")
}

struct Street: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    let locality: Locality
    var sources: [AccessSource] = []
}

struct House: Identifiable, Equatable, Codable {
    let id: String
    let streetID: String
    let label: String
    var sources: [AccessSource] = []
}

struct Entrance: Identifiable, Equatable, Codable {
    let id: String
    let houseID: String
    let label: String
    let deviceIdentity: String?
    let cameraAvailable: Bool
    var sources: [AccessSource] = []

    var compactLabel: String {
        label.replacingOccurrences(of: "(?i)^Подъезд(?=\\s|$)", with: "Под.", options: .regularExpression)
    }
}

struct Selection: Codable, Equatable {
    var localityID: String?
    var houseID: String?
    var entranceID: String?
}

struct RemoteDoor: Codable {
    let specificationID: String
    let deviceID: String
    let accessControlID: String
    let cameraAvailable: Bool
}

struct Catalog: Codable {
    var streets: [Street] = []
    var houses: [String: [House]] = [:]
    var entrances: [String: [Entrance]] = [:]
    var doors: [String: RemoteDoor] = [:]
    var complete = true
}

struct CameraStream: Equatable {
    let url: URL
    let source: AccessSource?
}

struct TemporalCode: Identifiable {
    let id = UUID()
    let value: String
    let updatedAt: Date?
    let validUntil: Date?
}

enum DoorResult: Equatable {
    case confirmed
    case rejected(String)
    case uncertain(String)
}

enum DoorState: Equatable {
    case idle, sending, opened, failed(String), uncertain(String)

    var caption: String {
        switch self {
        case .idle: return "ОТКРЫТЬ"
        case .sending: return "ОТКРЫВАЕМ…"
        case .opened: return "ОТКРЫТО"
        case .failed, .uncertain: return "ПОВТОРИТЬ"
        }
    }

    var message: String? {
        switch self {
        case .failed(let text): return text
        case .uncertain(let text): return text + ". Повтор — только вручную"
        default: return nil
        }
    }
}

enum AuthenticationFailure: String, Codable { case invalidCredentials, clientRejected, sessionExpired, unavailable }

enum ServiceError: Error, LocalizedError {
    case authentication(AuthenticationFailure)
    case http(Int, String)
    case protocolViolation(String)
    case storage
    case conflict
    case protectedAccount(String)

    var errorDescription: String? {
        switch self {
        case .authentication(.invalidCredentials): return "Сервер не принял логин или пароль"
        case .authentication(.clientRejected): return "Сервер не разрешил вход из приложения"
        case .authentication(.sessionExpired): return "Сессия закончилась — нужен новый вход"
        case .authentication(.unavailable): return "Сервер входа временно недоступен"
        case .http(_, let message), .protocolViolation(let message), .protectedAccount(let message): return message
        case .storage: return "Не удалось прочитать или сохранить защищённую сессию"
        case .conflict: return "Запись аккаунта изменилась. Повтори действие"
        }
    }

    var accountStatus: AccountStatus {
        switch self {
        case .authentication(.invalidCredentials), .authentication(.sessionExpired), .http(401, _): return .needsPassword
        case .authentication(.clientRejected), .http(403, _): return .accessDenied
        default: return .unavailable
        }
    }
}

func userMessage(_ error: Error) -> String {
    // Ошибка URLSession может содержать подписанную ссылку камеры; её исходный текст не показываем.
    if let error = error as? ServiceError { return error.localizedDescription }
    return "Не удалось связаться с сервером. Проверь подключение к интернету"
}

enum RetryPolicy {
    static func isSafeReadRetryable(_ error: Error) -> Bool {
        if let error = error as? URLError { return error.code != .cancelled }
        guard let error = error as? ServiceError else { return false }
        switch error {
        case .storage: return true
        case .http(let code, _): return [408, 425, 429].contains(code) || (500...599).contains(code)
        default: return false // Восстановление входа уже ограничивает собственные попытки.
        }
    }

    static func isVideoRetryable(_ error: Error) -> Bool {
        if isSafeReadRetryable(error) { return true }
        switch error as? ServiceError {
        case .authentication(.unavailable), .protocolViolation: return true
        default: return false
        }
    }

    static func delay(after failure: Int) -> UInt64 { [2, 4, 8, 15, 30][min(max(failure, 0), 4)] }

    static func read<T>(_ operation: () async throws -> T) async throws -> T {
        for attempt in 0...2 {
            try Task.checkCancellation()
            do { return try await operation() }
            catch {
                try Task.checkCancellation()
                guard attempt < 2, isSafeReadRetryable(error) else { throw error }
                try await Task.sleep(nanoseconds: attempt == 0 ? 250_000_000 : 750_000_000)
            }
        }
        preconditionFailure("Bounded retry must return or throw")
    }
}
