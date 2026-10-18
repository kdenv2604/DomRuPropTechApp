import SwiftUI
import MobileVLCKit

struct LiveCameraPlayer: UIViewRepresentable {
    let playback: CameraPlayback
    let onFailure: (UUID) -> Void
    let onReady: () -> Void
    let onWaiting: () -> Void
    let onFrame: (UIView) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(playback: playback, onFailure: onFailure, onReady: onReady, onWaiting: onWaiting, onFrame: onFrame)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        view.isAccessibilityElement = true
        view.accessibilityLabel = "Предпросмотр камеры"
        context.coordinator.attach(view)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) { coordinator.release() }

    final class Coordinator: NSObject, VLCMediaPlayerDelegate {
        private let playback: CameraPlayback
        private let onFailure: (UUID) -> Void
        private let onReady: () -> Void
        private let onWaiting: () -> Void
        private let onFrame: (UIView) -> Void
        private let player = VLCMediaPlayer(options: ["--no-audio", "--quiet", "--network-caching=1500", "--http-user-agent=lk.proptech.ru iOS"])
        private var watchdog: Timer?
        private var lastProgress = ProcessInfo.processInfo.systemUptime
        private var lastSnapshot: TimeInterval = 0
        private var lastPictures: Int32 = 0
        private var released = false
        private var failureReported = false
        private var readyReported = false
        private weak var surface: UIView?

        init(playback: CameraPlayback, onFailure: @escaping (UUID) -> Void, onReady: @escaping () -> Void,
             onWaiting: @escaping () -> Void, onFrame: @escaping (UIView) -> Void) {
            self.playback = playback
            self.onFailure = onFailure
            self.onReady = onReady
            self.onWaiting = onWaiting
            self.onFrame = onFrame
            super.init()
        }

        func attach(_ view: UIView) {
            surface = view
            view.accessibilityValue = "Подключение"
            player.delegate = self
            player.drawable = view
            let media = VLCMedia(url: playback.stream.url)
            media.addOption(":no-audio")
            media.addOption(":network-caching=1500")
            if playback.stream.url.isFileURL { media.addOption(":input-repeat=65535") }
            player.media = media
            lastProgress = ProcessInfo.processInfo.systemUptime
            player.play()
            // Состояние playing не доказывает поступление кадров. Проверяем реальный вывод,
            // но краткую задержку оставляем плееру: она не требует новой ссылки и декодера.
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.checkProgress() }
            watchdog = timer
            RunLoop.main.add(timer, forMode: .common)
        }

        private func checkProgress() {
            guard !released, !failureReported else { return }
            let now = ProcessInfo.processInfo.systemUptime
            let pictures = player.media?.statistics.displayedPictures ?? 0
            if pictures > 0 && pictures != lastPictures {
                lastPictures = pictures
                lastProgress = now
                surface?.accessibilityValue = "Видео воспроизводится"
                if !readyReported { readyReported = true; onReady() }
                // Снимок уже показанного слоя остаётся только в памяти, без записи камеры
                // на диск. Он переживает уничтожение декодера и получение новой ссылки.
                if now - lastSnapshot >= 2, let surface, surface.window != nil,
                   let frame = surface.snapshotView(afterScreenUpdates: false) {
                    lastSnapshot = now
                    onFrame(frame)
                }
            }
            let silence = now - lastProgress
            if silence >= 2, readyReported {
                readyReported = false
                surface?.accessibilityValue = "Ожидание видео"
                onWaiting()
            }
            if silence >= 20 { fail() }
        }

        func mediaPlayerStateChanged(_ notification: Notification) {
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.released else { return }
                if self.player.state == .error || self.player.state == .ended { self.fail() }
            }
        }

        private func fail() {
            guard !released, !failureReported else { return }
            failureReported = true
            onFailure(playback.id)
        }

        func release() {
            guard !released else { return }
            released = true
            watchdog?.invalidate()
            watchdog = nil
            player.delegate = nil
            player.stop()
            player.drawable = nil
            player.media = nil
        }
    }
}

private struct FrozenVideoFrame: UIViewRepresentable {
    let frame: UIView

    func makeUIView(context: Context) -> UIView { UIView() }

    func updateUIView(_ view: UIView, context: Context) {
        guard frame.superview !== view else { return }
        view.subviews.forEach { $0.removeFromSuperview() }
        frame.frame = view.bounds
        frame.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(frame)
    }
}

struct CameraPreview: View {
    @ObservedObject var controller: CameraController
    let entrance: Entrance
    let accounts: [AccountProfile]
    let demo: Bool
    @Environment(\.scenePhase) private var scenePhase
    @State private var readyPlaybackID: UUID?
    @State private var lastFrame: UIView?

    private var videoReady: Bool { controller.playback.map { $0.id == readyPlaybackID } ?? false }

    var body: some View {
        ZStack {
            Color.black
            if let playback = controller.playback {
                LiveCameraPlayer(playback: playback, onFailure: controller.failed,
                    onReady: { if controller.playback?.id == playback.id { readyPlaybackID = playback.id } },
                    onWaiting: { if readyPlaybackID == playback.id { readyPlaybackID = nil } },
                    onFrame: { if controller.playback?.id == playback.id { lastFrame = $0 } })
                    .id(playback.id)
            }
            if !videoReady {
                if let lastFrame {
                    FrozenVideoFrame(frame: lastFrame).accessibilityHidden(true)
                    Color.black.opacity(0.35)
                }
                VStack(spacing: 12) {
                    if controller.loading || controller.playback != nil { ProgressView().tint(.white) }
                    Text(statusMessage).font(.callout).multilineTextAlignment(.center)
                        .foregroundColor(.white).padding(.horizontal)
                }
            } else {
                VStack {
                    HStack {
                        Circle().fill(Palette.red).frame(width: 7, height: 7)
                        Text("LIVE").font(.caption.bold()).foregroundColor(.white)
                        Spacer()
                    }
                    Spacer()
                }.padding(12)
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 17))
        .onAppear { controller.show(entrance, accounts: accounts, demo: demo) }
        .onDisappear { controller.hide(entrance.id); lastFrame = nil; readyPlaybackID = nil }
        .onChange(of: scenePhase) { if $0 == .background { lastFrame = nil; readyPlaybackID = nil } }
        .onChange(of: accounts) { controller.show(entrance, accounts: $0, demo: demo) }
    }

    private var statusMessage: String {
        if controller.playback != nil { return lastFrame == nil ? "Подключаем камеру…" : "Восстанавливаем…" }
        return lastFrame == nil ? controller.message : "Последний кадр · \(controller.message)"
    }
}
