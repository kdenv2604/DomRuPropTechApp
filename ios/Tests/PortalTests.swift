import XCTest
@testable import DomruTechnics

actor ScriptedTransport: HTTPTransport {
    private var requests: [URLRequest] = []
    let reply: (URLRequest, Int) throws -> HTTPResponse
    init(_ reply: @escaping (URLRequest, Int) throws -> HTTPResponse) { self.reply = reply }
    func send(_ request: URLRequest) async throws -> HTTPResponse {
        requests.append(request)
        return try reply(request, requests.count)
    }
    func recorded() -> [URLRequest] { requests }
}

func response(_ json: String, status: Int = 200) -> HTTPResponse { HTTPResponse(status: status, data: Data(json.utf8)) }

final class PortalTests: XCTestCase {
    private let door = RemoteDoor(specificationID: "spec-a", deviceID: "door-a", accessControlID: "control-a", cameraAvailable: true)
    private func api(_ transport: HTTPTransport) -> PortalAPI {
        PortalAPI(transport: transport, sessions: SessionProvider(vault: SessionVault(persistence: MemoryPersistence([session(fresh: true)])), oidc: TestOIDC()))
    }

    func testOpenUnauthorizedIsNeverReplayed() async throws {
        let transport = ScriptedTransport { _, _ in response("{}", status: 401) }
        _ = try await api(transport).openDoor(accountID: "account-a", door: door)
        let requests = await transport.recorded()
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].httpMethod, "POST")
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "x-specification-id"), "spec-a")
        XCTAssertEqual(String(data: requests[0].httpBody!, encoding: .utf8), #"{"source":"objects"}"#)
    }

    func testOpenRequiresConfirmation() async throws {
        let transport = ScriptedTransport { _, _ in response(#"{"status":false}"#) }
        let result = try await api(transport).openDoor(accountID: "account-a", door: door)
        XCTAssertEqual(result, .rejected("Сервер не подтвердил открытие"))
    }

    func testReadUnauthorizedRecoversExactlyOnce() async throws {
        let transport = ScriptedTransport { _, count in response(#"{"specifications":[]}"#, status: count == 1 ? 401 : 200) }
        _ = try await api(transport).specifications(accountID: "account-a")
        let requests = await transport.recorded()
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer old")
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer refreshed")
    }

    func testCodePreservesLeadingZeroesAndPeriod() async throws {
        let transport = ScriptedTransport { _, _ in response(#"{"code":"0012","installationTime":"2026-10-01T00:00:00Z","period":"P2D"}"#) }
        let code = try await api(transport).code(accountID: "account-a", door: door)
        XCTAssertEqual(code?.value, "0012")
        XCTAssertEqual(code!.validUntil!.timeIntervalSince(code!.updatedAt!), 172_800)
    }

    func testMediaURLKeepsSignedQueryAndRejectsOtherSchemes() {
        let signed = "https://camera.example.invalid/live.flv?token=a%2Bb&expires=123"
        XCTAssertEqual(PortalAPI.mediaURL(signed)?.absoluteString, signed)
        for invalid in ["file:///secret", "javascript:alert(1)", "https:///", "data:video/mp4,test"] { XCTAssertNil(PortalAPI.mediaURL(invalid)) }
    }

    func testPasswordFormEscapesReservedCharacters() {
        let encoded = String(data: FormEncoding.encode([("password", "a+b &пароль=")]), encoding: .utf8)!
        XCTAssertEqual(encoded, "password=a%2Bb%20%26%D0%BF%D0%B0%D1%80%D0%BE%D0%BB%D1%8C%3D")
    }

    func testPaginationUsesAllPagesAndSpecificationHeader() async throws {
        let transport = ScriptedTransport { request, count in
            XCTAssertEqual(request.value(forHTTPHeaderField: "x-specification-id"), "spec-a")
            return response("{\"items\":[{\"_id\":\"\(count)\",\"address\":\"ул. Тестовая, д. \(count)\"}],\"total\":2}")
        }
        let houses = try await api(transport).houses(accountID: "account-a", specification: PortalSpecification(id: "spec-a", companyID: "company", locality: nil))
        XCTAssertEqual(houses.count, 2)
        let requests = await transport.recorded()
        XCTAssertTrue(requests[1].url!.absoluteString.contains("skip=1"))
    }

    func testRetryClassifiesDeniedAndTemporaryFailuresDifferently() {
        XCTAssertFalse(RetryPolicy.isVideoRetryable(ServiceError.http(403, "denied")))
        XCTAssertFalse(RetryPolicy.isVideoRetryable(ServiceError.authentication(.invalidCredentials)))
        XCTAssertTrue(RetryPolicy.isVideoRetryable(ServiceError.http(503, "unavailable")))
        XCTAssertTrue(RetryPolicy.isVideoRetryable(ServiceError.authentication(.unavailable)))
        XCTAssertEqual((0...6).map(RetryPolicy.delay), [2,4,8,15,30,30,30])
    }

    func testRawURLSessionErrorNeverLeaksMediaURL() {
        let error = URLError(.badServerResponse, userInfo: [NSURLErrorFailingURLErrorKey: URL(string: "https://camera.example.invalid/?token=secret")!])
        XCTAssertFalse(userMessage(error).contains("secret"))
    }
}
