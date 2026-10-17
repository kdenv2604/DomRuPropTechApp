import XCTest
@testable import DomruTechnics

enum PortalFixtures {
    static func catalogResponse(_ request: URLRequest) -> HTTPResponse {
        let path = request.url!.path
        if path.hasSuffix("/accounts/me") { return response(#"{"specifications":[{"_id":"spec","companyId":"company","cityName":"Казань"}]}"#) }
        if path.hasSuffix("/houses") { return response(#"{"items":[{"_id":"house","address":"ул. Тестовая, д. 9","route":"1.company.house"}],"total":1}"#) }
        if path.hasSuffix("/porches") { return response(#"{"items":[{"porchId":"1","number":"1","route":"1.company.house.1","parentRoute":"1.company.house"}],"total":1}"#) }
        if path.hasSuffix("/intercom") { return response(#"[{"_id":"device","accessControlId":"control","name":"Подъезд 1","routes":["1.company.house.1"],"meta":{"allowVideo":true}}]"#) }
        return response("{}", status: 404)
    }
}

final class RepositoryTests: XCTestCase {
    private func repository(_ transport: HTTPTransport) -> ServiceRepository {
        let vault = SessionVault(persistence: MemoryPersistence([session("a", token: "a", fresh: true), session("b", token: "b", fresh: true)]))
        return ServiceRepository(vault: vault, transport: transport)
    }
    private let accounts = [AccountProfile(id: "a", title: "A", login: "a"), AccountProfile(id: "b", title: "B", login: "b")]

    func testUnavailableAccountDoesNotHideAvailableDoors() async throws {
        let transport = ScriptedTransport { request, _ in
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer a" { return response("{}", status: 403) }
            return PortalFixtures.catalogResponse(request)
        }
        let catalog = try await repository(transport).combinedCatalog(accounts: accounts)
        XCTAssertFalse(catalog.complete)
        XCTAssertEqual(catalog.entrances.values.flatMap { $0 }.count, 1)
        XCTAssertEqual(catalog.streets.first?.sources.first?.accountID, "b")
    }

    func testCameraFallbackUsesOnlyAuthorizedSourcesOfSameDoor() async throws {
        let transport = ScriptedTransport { request, _ in
            if request.url!.path.hasSuffix("/camera/video") { return response(#"{"streamUrl":"https://camera.example.invalid/live.flv?token=test"}"#) }
            return PortalFixtures.catalogResponse(request)
        }
        let repository = repository(transport)
        let catalog = try await repository.combinedCatalog(accounts: accounts)
        let merged = try XCTUnwrap(catalog.entrances.values.flatMap { $0 }.first)
        let entrance = Entrance(id: merged.id, houseID: merged.houseID, label: merged.label, deviceIdentity: merged.deviceIdentity,
                                cameraAvailable: true, sources: merged.sources.map {
                                    AccessSource(accountID: $0.accountID, remoteID: $0.remoteID, canVideo: $0.accountID == "b")
                                })
        let stream = try await repository.camera(entrance, accounts: accounts, after: nil)
        XCTAssertEqual(stream?.source?.accountID, "b")
        let requests = await transport.recorded().filter { $0.url!.path.hasSuffix("/camera/video") }
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer b")
        XCTAssertTrue(requests[0].url!.path.contains("/device/"))
    }

    func testPlaybackFailureRotatesToNextAuthorizedAccount() async throws {
        let transport = ScriptedTransport { request, _ in
            if request.url!.path.hasSuffix("/camera/video") { return response(#"{"streamUrl":"https://camera.example.invalid/live.flv"}"#) }
            return PortalFixtures.catalogResponse(request)
        }
        let repository = repository(transport), catalog = try await repository.combinedCatalog(accounts: accounts)
        let entrance = try XCTUnwrap(catalog.entrances.values.flatMap { $0 }.first)
        let first = try await repository.camera(entrance, accounts: accounts, after: nil)
        let second = try await repository.camera(entrance, accounts: accounts, after: first?.source)
        XCTAssertEqual(first?.source?.accountID, "a")
        XCTAssertEqual(second?.source?.accountID, "b")
    }

    func testLostOpenResponseDoesNotTrySecondAccount() async throws {
        let transport = ScriptedTransport { request, _ in
            if request.httpMethod == "POST" { throw URLError(.networkConnectionLost) }
            return PortalFixtures.catalogResponse(request)
        }
        let repository = repository(transport), catalog = try await repository.combinedCatalog(accounts: accounts)
        let entrance = try XCTUnwrap(catalog.entrances.values.flatMap { $0 }.first)
        let result = try await repository.openDoor(entrance, accounts: accounts)
        XCTAssertEqual(result, .uncertain("Ответ сервера не получен"))
        let posts = await transport.recorded().filter { $0.httpMethod == "POST" }
        XCTAssertEqual(posts.count, 1)
        XCTAssertEqual(posts[0].value(forHTTPHeaderField: "Authorization"), "Bearer a")
    }
}
