import Foundation
import Network

struct CameraPlayback: Identifiable, Equatable {
    let id = UUID()
    let stream: CameraStream
}

@MainActor final class CameraController: ObservableObject {
    @Published private(set) var playback: CameraPlayback?
    @Published private(set) var message = "Подключаем камеру…"
    @Published private(set) var loading = false
    private let repository: ServiceRepository
    private let monitor = NWPathMonitor()
    private var networkPath: NWPath?
    private var request: Task<Void, Never>?
    private var generation = UUID()
    private var entrance: Entrance?
    private var accounts: [AccountProfile] = []
    private var demo = false
    private var foreground = true
    private var obscured = false
    private var visible: Bool { foreground && !obscured && entrance != nil }

    init(repository: ServiceRepository) {
        self.repository = repository
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                guard let self else { return }
                let previous = self.networkPath
                self.networkPath = path
                // Сравниваем весь маршрут: переход между двумя Wi-Fi сетями может сохранить имя интерфейса.
                if previous != nil, previous != path, path.status == .satisfied { self.restart() }
            }
        }
        monitor.start(queue: DispatchQueue(label: "ru.domru.technics.network"))
    }

    deinit { monitor.cancel(); request?.cancel() }

    func show(_ entrance: Entrance, accounts: [AccountProfile], demo: Bool) {
        guard self.entrance != entrance || self.accounts != accounts || playback == nil else { return }
        self.entrance = entrance; self.accounts = accounts; self.demo = demo
        restart()
    }

    func hide(_ entranceID: String) { if entrance?.id == entranceID { stop() } }

    func stop() {
        entrance = nil
        cancelPlayback()
    }

    func setForeground(_ active: Bool) {
        guard foreground != active else { return }
        foreground = active
        if active { restart() } else { cancelPlayback() }
    }

    func setObscured(_ value: Bool) {
        guard obscured != value else { return }
        obscured = value
        if value { cancelPlayback() } else { restart() }
    }

    func failed(_ playbackID: UUID) {
        guard playback?.id == playbackID, visible else { return }
        let source = playback?.stream.source
        restart(after: source, playbackFailure: true)
    }

    private func cancelPlayback() {
        generation = UUID(); request?.cancel(); request = nil
        playback = nil; loading = false
    }

    private func restart(after source: AccessSource? = nil, playbackFailure: Bool = false) {
        cancelPlayback()
        guard visible, let entrance, entrance.cameraAvailable else { return }
        let generation = self.generation, accounts = self.accounts, demo = self.demo
        message = playbackFailure ? "Восстанавливаем видеопоток…" : "Подключаем камеру…"
        loading = true
        request = Task {
            do {
                if playbackFailure { try await Task.sleep(nanoseconds: 3_000_000_000) }
                var failures = 0
                while visible && self.generation == generation {
                    var failure: Error?
                    do {
                        let stream: CameraStream?
                        if demo {
                            stream = Bundle.main.url(forResource: "demo-camera", withExtension: "flv").map { CameraStream(url: $0, source: nil) }
                        } else { stream = try await repository.camera(entrance, accounts: accounts, after: source) }
                        try Task.checkCancellation()
                        guard self.generation == generation, visible else { return }
                        if let stream { playback = CameraPlayback(stream: stream); loading = false; return }
                    } catch {
                        try Task.checkCancellation()
                        failure = error
                    }
                    guard self.generation == generation, visible else { return }
                    if let failure, !RetryPolicy.isVideoRetryable(failure) {
                        message = userMessage(failure); loading = false; return
                    }
                    let seconds = RetryPolicy.delay(after: failures)
                    failures += 1
                    message = "Восстанавливаем видеопоток… Повтор через \(seconds) сек."
                    try await Task.sleep(nanoseconds: seconds * 1_000_000_000)
                }
            } catch { /* Закрытие карточки, смена сети или уход из приложения отменяют это поколение загрузки. */ }
        }
    }
}
