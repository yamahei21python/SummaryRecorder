import AVFoundation

@MainActor
final class AudioPlaybackManager: ObservableObject {
    @Published private(set) var isPlaying = false
    @Published var playbackTime: TimeInterval = 0
    @Published var playbackSpeed: Float = 1.0

    private var player: AVAudioPlayer?
    private var progressTimer: Timer?
    private(set) var totalDuration: TimeInterval = 0

    func setup(for wavFileName: String) {
        stop()
        let url = AppPaths.recordingsDirectory.appendingPathComponent(wavFileName)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            let player = try AVAudioPlayer(contentsOf: url)
            player.prepareToPlay()
            self.player = player
            self.totalDuration = player.duration
            applySpeed()
            player.play()
            startTimer()
            isPlaying = true
        } catch {
            NSLog("[AudioPlayback] Failed to setup player: %@", error.localizedDescription)
        }
    }

    func toggle() {
        if isPlaying {
            player?.pause()
            progressTimer?.invalidate()
            progressTimer = nil
            isPlaying = false
        } else {
            applySpeed()
            player?.play()
            startTimer()
            isPlaying = true
        }
    }

    func seek(to time: TimeInterval) {
        player?.currentTime = time
    }

    func stop() {
        player?.stop()
        player = nil
        progressTimer?.invalidate()
        progressTimer = nil
        playbackTime = 0
        isPlaying = false
    }

    func formatTime(_ time: TimeInterval) -> String {
        String(format: "%d:%02d", Int(time) / 60, Int(time) % 60)
    }

    private func applySpeed() {
        player?.enableRate = true
        player?.rate = playbackSpeed
    }

    private func startTimer() {
        progressTimer?.invalidate()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self, let player = self.player, player.isPlaying else { return }
            self.playbackTime = player.currentTime
        }
    }
}
