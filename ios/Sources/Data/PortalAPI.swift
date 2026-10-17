import Foundation

struct PortalSpecification { let id: String; let companyID: String; let locality: String? }
struct PortalHouse { let id: String; let address: String; let route: String }
struct PortalPorch { let id: String; let number: String; let route: String; let parentRoute: String }
struct PortalDevice { let id: String; let accessControlID: String; let name: String; let route: String; let cameraAvailable: Bool }

struct PortalAPI {
    let transport: HTTPTransport
    let sessions: SessionProvider
    private let publicBase = "https://public.proptech.ru"
    private let bffBase = "https://bff.lk.proptech.ru"

    func specifications(accountID: String) async throws -> [PortalSpecification] {
        let response = try await request(accountID: accountID, path: "/accounts/v1/accounts/me")
        let json = try response.checked().object()
        var seen = Set<String>()
        return (json["specifications"] as? [[String: Any]] ?? []).compactMap { item in
            let id = item.text("_id", "id")
            let company = item["company"] as? [String: Any] ?? [:]
            let companyID = item.text("companyId").nonempty ?? company.text("_id", "id")
            guard !id.isEmpty, !companyID.isEmpty, seen.insert(id).inserted else { return nil }
            let locality = item.text("locationName", "cityName").nonempty ?? company.text("locationName", "cityName").nonempty
            return PortalSpecification(id: id, companyID: companyID, locality: locality)
        }
    }

    func companyLocality(accountID: String, specification: PortalSpecification) async throws -> String? {
        let response = try await request(accountID: accountID, specificationID: specification.id,
                                         path: "/companies/v1/companies/" + pathSegment(specification.companyID))
        let json = try response.checked().object()
        let location = json["location"] as? [String: Any] ?? [:]
        return json.text("locationName", "cityName").nonempty ?? location.text("name", "value", "label").nonempty
    }

    func houses(accountID: String, specification: PortalSpecification) async throws -> [PortalHouse] {
        try await paged(accountID: accountID, specification: specification, kind: "houses").compactMap { item in
            let id = item.text("houseId", "_id", "id"), address = item.text("address")
            guard !id.isEmpty, !address.isEmpty else { return nil }
            return PortalHouse(id: id, address: address, route: item.text("route"))
        }
    }

    func porches(accountID: String, specification: PortalSpecification) async throws -> [PortalPorch] {
        try await paged(accountID: accountID, specification: specification, kind: "porches").compactMap { item in
            let id = item.text("porchId", "_id", "id")
            guard !id.isEmpty else { return nil }
            return PortalPorch(id: id, number: item.text("number").nonempty ?? id,
                               route: item.text("route"), parentRoute: item.text("parentRoute"))
        }
    }

    func devices(accountID: String, specification: PortalSpecification) async throws -> [PortalDevice] {
        let response = try await request(accountID: accountID, specificationID: specification.id,
                                         path: "/devices/v2/devices/intercom", query: [URLQueryItem(name: "companyId", value: specification.companyID)])
        let checked = try response.checked()
        guard let items = try? JSONSerialization.jsonObject(with: checked.data) as? [[String: Any]] else {
            throw ServiceError.protocolViolation("Сервер вернул непонятный список домофонов")
        }
        return items.compactMap { item in
            let id = item.text("_id", "id"), control = item.text("accessControlId")
            let route = (item["routes"] as? [String])?.first ?? ""
            guard !id.isEmpty, !control.isEmpty, !route.isEmpty else { return nil }
            return PortalDevice(id: id, accessControlID: control, name: item.text("name").nonempty ?? "Домофон", route: route,
                                cameraAvailable: (item["meta"] as? [String: Any])?["allowVideo"] as? Bool == true)
        }
    }

    func openDoor(accountID: String, door: RemoteDoor) async throws -> DoorResult {
        let response = try await request(accountID: accountID, specificationID: door.specificationID,
                                         path: "/devices/v2/devices/\(pathSegment(door.deviceID))/open", method: "POST",
                                         body: Data(#"{"source":"objects"}"#.utf8))
        guard (200...299).contains(response.status) else { return .rejected(response.safeMessage) }
        let status = (try? response.object())?["status"]
        let confirmed: Bool
        if let number = status as? NSNumber { confirmed = number.intValue != 0 }
        else if let string = status as? String { confirmed = !string.trimmingCharacters(in: .whitespaces).isEmpty && string.lowercased() != "false" }
        else { confirmed = false }
        return confirmed ? .confirmed : .rejected("Сервер не подтвердил открытие")
    }

    func code(accountID: String, door: RemoteDoor) async throws -> TemporalCode? {
        let response = try await request(accountID: accountID, specificationID: door.specificationID,
                                         path: "/v1/web/codes/" + pathSegment(door.accessControlID), useBFF: true)
        if response.status == 404 { return nil }
        let json = try response.checked().object()
        guard let value = json.text("code").nonempty else { return nil }
        let installed = Self.date(json.text("installationTime"))
        let group = json["group"] as? [String: Any] ?? [:]
        var until = Self.date(group.text("refreshTime"))
        let period = json.text("period")
        if until == nil, let installed, period.range(of: "^P\\d+D$", options: .regularExpression) != nil,
           let days = Double(period.dropFirst().dropLast()) { until = installed.addingTimeInterval(days * 86_400) }
        return TemporalCode(value: value, updatedAt: installed, validUntil: until)
    }

    func camera(accountID: String, door: RemoteDoor, source: AccessSource) async throws -> CameraStream {
        let response = try await request(accountID: accountID, specificationID: door.specificationID,
                                         path: "/devices/v2/devices/\(pathSegment(door.deviceID))/camera/video")
        let json = try response.checked().object()
        guard let url = Self.mediaURL(json.text("streamUrl")) else { throw ServiceError.protocolViolation("Сервер не дал ссылку на видеопоток") }
        return CameraStream(url: url, source: source)
    }

    static func mediaURL(_ string: String) -> URL? {
        guard let url = URL(string: string), ["http", "https", "rtsp"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty else { return nil }
        return url
    }

    private func paged(accountID: String, specification: PortalSpecification, kind: String) async throws -> [[String: Any]] {
        var items: [[String: Any]] = []
        for _ in 0..<100 {
            var query = [URLQueryItem(name: "skip", value: String(items.count)), URLQueryItem(name: "limit", value: "300")]
            if kind == "houses" { query.append(URLQueryItem(name: "sort", value: "address")) }
            let response = try await request(accountID: accountID, specificationID: specification.id,
                                             path: "/houses/v1/companies/\(pathSegment(specification.companyID))/\(kind)", query: query)
            let json = try response.checked().object()
            let page = json["items"] as? [[String: Any]] ?? []
            items.append(contentsOf: page)
            if page.isEmpty || items.count >= (json["total"] as? Int ?? page.count) { return items }
        }
        // Усечённый каталог нельзя незаметно сохранить в кеше как полный.
        throw ServiceError.protocolViolation("Каталог превысил допустимое число страниц")
    }

    private func request(accountID: String, specificationID: String? = nil, path: String, query: [URLQueryItem] = [],
                         method: String = "GET", body: Data? = nil, useBFF: Bool = false) async throws -> HTTPResponse {
        var url = URLComponents(string: (useBFF ? bffBase : publicBase) + path)!
        if !query.isEmpty { url.queryItems = query }
        var request = URLRequest(url: url.url!)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type") }
        request.setValue(specificationID, forHTTPHeaderField: "x-specification-id")
        let token = try await sessions.token(for: accountID)
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        let response = try await transport.send(request)
        // После восстановления входа повторяем только чтение. Команда двери отправляется один раз.
        if response.status == 401 && method == "GET" {
            request.setValue("Bearer " + (try await sessions.token(for: accountID, rejecting: token)), forHTTPHeaderField: "Authorization")
            return try await transport.send(request)
        }
        return response
    }

    private func pathSegment(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))!
    }
    private static func date(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
}

extension String { var nonempty: String? { isEmpty ? nil : self } }
