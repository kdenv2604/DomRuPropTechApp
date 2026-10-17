import SwiftUI
import MobileVLCKit

struct LiveCameraPlayer: UIViewRepresentable {
    let playback: CameraPlayback
    let onFailure: (UUID) -> Void
    let onReady: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(playback: playback, onFailure: onFailure, onReady: onReady) }

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
        private let player = VLCMediaPlayer(options: ["--no-audio", "--quiet", "--network-caching=1500", "--http-user-agent=lk.proptech.ru iOS"])
        private var watchdog: Timer?
        private var lastProgress = Date()
        private var lastPictures: Int32 = 0
        private var released = false
        private var failureReported = false
        private var readyReported = false
        private weak var surface: UIView?

        init(playback: CameraPlayback, onFailure: @escaping (UUID) -> Void, onReady: @escaping () -> Void) {
            self.playback = playback; self.onFailure = onFailure; self.onReady = onReady
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
            player.play()
            // Открытое соединение и состояние playing ещё не означают, что кадры продолжают поступать.
            // Проверяем показанные кадры: зависший поток должен получить свежую ссылку.
            watchdog = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.checkProgress() }
        }

        private func checkProgress() {
            guard !released else { return }
            let pictures = player.media?.statistics.displayedPictures ?? 0
            if pictures > 0 && pictures != lastPictures {
                lastPictures = pictures; lastProgress = Date()
                surface?.accessibilityValue = "Видео воспроизводится"
                if !readyReported { readyReported = true; onReady() }
            }
            if Date().timeIntervalSince(lastProgress) >= 20 { fail() }
        }

        func mediaPlayerStateChanged(_ aNotification: Notification) {
            // Перед обращением к владельцу SwiftUI переводим callback плеера на главный поток.
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
            released = true; watchdog?.invalidate(); watchdog = nil
            player.delegate = nil
            player.stop()
            player.drawable = nil
            player.media = nil
        }
    }
}

struct CameraPreview: View {
    @ObservedObject var controller: CameraController
    let entrance: Entrance
    let accounts: [AccountProfile]
    let demo: Bool
    @State private var videoReady = false

    var body: some View {
        ZStack {
            Color.black
            if let playback = controller.playback {
                LiveCameraPlayer(playback: playback, onFailure: controller.failed, onReady: { videoReady = true }).id(playback.id)
                if !videoReady { ProgressView().tint(.white) }
                VStack { HStack { Circle().fill(Palette.red).frame(width: 7, height: 7); Text("LIVE").font(.caption.bold()).foregroundColor(.white); Spacer() }; Spacer() }.padding(12)
            } else {
                VStack(spacing: 12) {
                    if controller.loading { ProgressView().tint(.white) }
                    Text(controller.message).font(.callout).multilineTextAlignment(.center).foregroundColor(.white).padding(.horizontal)
                }
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 17))
        .onAppear { controller.show(entrance, accounts: accounts, demo: demo) }
        .onChange(of: controller.playback?.id) { _ in videoReady = false }
        .onDisappear { controller.hide(entrance.id) }
        .onChange(of: accounts) { controller.show(entrance, accounts: $0, demo: demo) }
    }
}
