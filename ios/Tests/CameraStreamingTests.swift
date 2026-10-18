import XCTest
import Network
import UIKit
@testable import DomruTechnics

/// Проверяет настоящий декодер через HTTP, а не только состояние контроллера.
@MainActor final class CameraStreamingTests: XCTestCase {
    func testContinuousHTTPVideoSurvivesShortPauseAndReportsClosedConnection() async throws {
        let fixture = try XCTUnwrap(Bundle.main.url(forResource: "demo-camera", withExtension: "flv"))
        let server = try LocalFLVServer(data: Data(contentsOf: fixture))
        defer { server.stop() }
        let url = try await server.start()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let host = UIViewController()
        window.rootViewController = host
        window.makeKeyAndVisible()
        let surface = UIView(frame: CGRect(x: 0, y: 50, width: 320, height: 180))
        host.view.addSubview(surface)
        var failureCount = 0
        var readyCount = 0
        var frames = 0
        var lastFrame: UIView?
        let player = LiveCameraPlayer.Coordinator(playback: CameraPlayback(stream: CameraStream(url: url, source: nil)),
            onFailure: { _ in failureCount += 1 }, onReady: { readyCount += 1 }, onWaiting: {},
            onFrame: { frames += 1; lastFrame = $0 })
        defer { player.release(); window.isHidden = true }
        player.attach(surface)
        try await waitUntil { readyCount > 0 && frames > 0 }
        // Три полных интервала контроля зависания при непрерывной сетевой передаче.
        try await Task.sleep(nanoseconds: 61_000_000_000)
        XCTAssertEqual(failureCount, 0)
        XCTAssertGreaterThan(frames, 15)
        let beforePause = frames
        server.pause()
        try await Task.sleep(nanoseconds: 6_000_000_000)
        XCTAssertEqual(failureCount, 0, "Краткая пауза не требует пересоздания декодера")
        server.resume()
        try await waitUntil { frames > beforePause + 2 }
        XCTAssertEqual(failureCount, 0)
        XCTAssertEqual(server.connectionCount, 1, "Поток переживает паузу на исходном HTTP-соединении")
        server.stop()
        try await waitUntil { failureCount > 0 }
        XCTAssertEqual(failureCount, 1)
        XCTAssertNotNil(lastFrame, "Кадр остаётся после закрытия сетевого потока")
        // Проверяем, что UIKit-снимок можно показать после освобождения VLC.
        player.release()
        if let lastFrame { lastFrame.frame = surface.frame; host.view.addSubview(lastFrame) }
        try await Task.sleep(nanoseconds: 200_000_000)
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "last-frame-after-http-disconnect"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func waitUntil(_ predicate: () -> Bool) async throws {
        for _ in 0..<250 {
            if predicate() { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTFail("Видеоплеер не достиг ожидаемого состояния")
        throw NSError(domain: "CameraStreamingTests", code: 1)
    }
}

/// Локальная камера без аккаунтов: зацикливает AVC-теги FLV с возрастающими метками времени.
private final class LocalFLVServer: @unchecked Sendable {
    private struct Tag { let bytes: Data; let timestamp: Int }
    private let queue = DispatchQueue(label: "camera-http-fixture")
    private let listener: NWListener
    private let header: Data
    private let tags: [Tag]
    private let duration: Int
    private var clients: [NWConnection] = []
    private var timer: DispatchSourceTimer?
    private var paused = false
    private var count = 0
    private var startCompleted = false
    var connectionCount: Int { queue.sync { count } }

    init(data: Data) throws {
        let bytes = Array(data)
        header = data.prefix(13)
        var parsed: [Tag] = []
        var position = 13
        while position + 15 <= bytes.count {
            let size = Int(bytes[position + 1]) << 16 | Int(bytes[position + 2]) << 8 | Int(bytes[position + 3])
            let end = position + 11 + size + 4
            guard end <= bytes.count else { break }
            let timestamp = Int(bytes[position + 7]) << 24 | Int(bytes[position + 4]) << 16 |
                Int(bytes[position + 5]) << 8 | Int(bytes[position + 6])
            // Метаданные конечного файла и AVC end-of-sequence в живой поток не попадают.
            if bytes[position] == 9 && size > 1 && bytes[position + 12] != 2 {
                parsed.append(Tag(bytes: data.subdata(in: position..<end), timestamp: timestamp))
            }
            position = end
        }
        guard parsed.count > 2 else { throw NSError(domain: "FLVFixture", code: 1) }
        tags = parsed
        duration = parsed.map(\.timestamp).max()! + 40
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }

    func start() async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self] state in
                guard let self, !self.startCompleted else { return }
                if case .ready = state, let port = self.listener.port {
                    self.startCompleted = true
                    continuation.resume(returning: URL(string: "http://127.0.0.1:\(port.rawValue)/live.flv")!)
                } else if case .failed(let error) = state {
                    self.startCompleted = true
                    continuation.resume(throwing: error)
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.start(queue: queue)
        }
    }

    private func accept(_ connection: NWConnection) {
        count += 1
        clients.append(connection)
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] _, _, _, _ in
            guard let self else { return }
            let response = Data("HTTP/1.0 200 OK\r\nContent-Type: video/x-flv\r\nConnection: close\r\n\r\n".utf8) + self.header
            connection.send(content: response, completion: .contentProcessed { _ in })
            self.stream(to: connection)
        }
    }

    private func stream(to connection: NWConnection) {
        var index = 0
        var cycle = 0
        var clock = 0
        let timer = DispatchSource.makeTimerSource(queue: queue)
        self.timer?.cancel()
        self.timer = timer
        timer.schedule(deadline: .now(), repeating: .milliseconds(40))
        timer.setEventHandler { [weak self, weak connection] in
            guard let self, let connection, !self.paused else { return }
            while self.tags[index].timestamp + cycle * self.duration <= clock {
                let tag = self.tags[index]
                let timestamp = tag.timestamp + cycle * self.duration
                var bytes = tag.bytes
                bytes[4] = UInt8((timestamp >> 16) & 255)
                bytes[5] = UInt8((timestamp >> 8) & 255)
                bytes[6] = UInt8(timestamp & 255)
                bytes[7] = UInt8((timestamp >> 24) & 255)
                connection.send(content: bytes, completion: .contentProcessed { _ in })
                index += 1
                if index == self.tags.count { index = 0; cycle += 1 }
            }
            clock += 40
        }
        timer.resume()
    }

    func pause() { queue.sync { paused = true } }
    func resume() { queue.sync { paused = false } }
    func stop() {
        queue.sync {
            timer?.cancel(); timer = nil
            clients.forEach { $0.cancel() }; clients.removeAll()
            listener.cancel()
        }
    }
}
