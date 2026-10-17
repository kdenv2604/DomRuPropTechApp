import XCTest
@testable import DomruTechnics

@MainActor final class CameraControllerTests: XCTestCase {
    func testForegroundAndFailureCreateNewPlaybackEvenWhenURLIsIdentical() async throws {
        let transport = ScriptedTransport { request, _ in
            if request.url!.path.hasSuffix("/camera/video") { return response(#"{"streamUrl":"https://camera.example.invalid/live.flv?token=unchanged"}"#) }
            return PortalFixtures.catalogResponse(request)
        }
        let repository = ServiceRepository(vault: SessionVault(persistence: MemoryPersistence([session(fresh: true)])), transport: transport)
        let accounts = [AccountProfile(id: "account-a", title: "A", login: "test")]
        let catalog = try await repository.combinedCatalog(accounts: accounts)
        let entrance = try XCTUnwrap(catalog.entrances.values.flatMap { $0 }.first)
        let camera = CameraController(repository: repository)
        camera.show(entrance, accounts: accounts, demo: false)
        let first = try await waitForPlayback(camera)
        camera.setForeground(false)
        XCTAssertNil(camera.playback)
        camera.setForeground(true)
        let second = try await waitForPlayback(camera)
        XCTAssertEqual(first.stream.url, second.stream.url)
        XCTAssertNotEqual(first.id, second.id)
        camera.failed(first.id)
        XCTAssertEqual(camera.playback?.id, second.id, "Запоздалая ошибка старого плеера не останавливает новый")
        camera.failed(second.id)
        XCTAssertNil(camera.playback)
        let third = try await waitForPlayback(camera)
        XCTAssertNotEqual(third.id, second.id)
        let requests = await transport.recorded().filter { $0.url!.path.hasSuffix("/camera/video") }
        XCTAssertEqual(requests.count, 3)
        camera.hide(entrance.id)
        camera.failed(third.id)
        XCTAssertNil(camera.playback)
        XCTAssertFalse(camera.loading)
    }

    private func waitForPlayback(_ camera: CameraController) async throws -> CameraPlayback {
        for _ in 0..<100 {
            if let value = camera.playback { return value }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return try XCTUnwrap(camera.playback)
    }
}
