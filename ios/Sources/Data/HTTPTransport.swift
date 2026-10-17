import Foundation

struct HTTPResponse {
    let status: Int
    let data: Data

    func object() throws -> [String: Any] {
        guard let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ServiceError.protocolViolation("Сервер вернул непонятный ответ")
        }
        return value
    }

    func checked() throws -> HTTPResponse {
        guard (200...299).contains(status) else { throw ServiceError.http(status, safeMessage) }
        return self
    }

    var safeMessage: String {
        let json = try? object()
        switch json?.text("errorCode") {
        case "CODE_NOT_ALLOWED_ON_NEW_DEVICE": return "Код пока запрещён для нового устройства"
        case "CODE_EMPTY": return "Код для устройства не найден"
        default: break
        }
        switch status {
        case 401: return "Сессия закончилась — нужен новый вход"
        case 403: return "Сервер запретил доступ к объекту"
        case 404: return "Объект не найден"
        default: return "Служебный портал недоступен (HTTP \(status))"
        }
    }
}

protocol HTTPTransport {
    func send(_ request: URLRequest) async throws -> HTTPResponse
}

final class URLSessionTransport: HTTPTransport {
    private let session: URLSession
    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        session = URLSession(configuration: configuration, delegate: RedirectGuard(), delegateQueue: nil)
    }

    func send(_ request: URLRequest) async throws -> HTTPResponse {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw ServiceError.protocolViolation("Сервер не прислал HTTP-ответ")
        }
        return HTTPResponse(status: response.statusCode, data: data)
    }

}

private final class RedirectGuard: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Адреса API/авторизации фиксированы. Перенаправление не должно повторять команду двери
        // или передавать пароль/токен другому узлу. Для видео VLC использует отдельное соединение.
        completionHandler(nil)
    }
}

extension Dictionary where Key == String, Value == Any {
    func text(_ keys: String...) -> String {
        for key in keys {
            let value: String
            if let string = self[key] as? String { value = string }
            else if let number = self[key] as? NSNumber { value = number.stringValue }
            else { continue }
            if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value != "null" { return value }
        }
        return ""
    }
}

enum FormEncoding {
    static func encode(_ pairs: [(String, String)]) -> Data {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        return Data(pairs.map { key, value in
            key.addingPercentEncoding(withAllowedCharacters: allowed)! + "=" + value.addingPercentEncoding(withAllowedCharacters: allowed)!
        }.joined(separator: "&").utf8)
    }
}
