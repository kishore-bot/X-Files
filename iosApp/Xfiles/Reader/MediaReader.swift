import AVKit
import SwiftUI

struct MediaReader: View {
    let url: URL
    let seconds: Double
    let onTime: (Double) -> Void
    let onCheckpoint: () -> Void
    @Environment(\.scenePhase) private var scenePhase
    @State private var player: AVPlayer?
    @State private var problem: String?

    var body: some View {
        Group {
            if let problem {
                ContentUnavailableView("Media unavailable", systemImage: "play.slash", description: Text(problem))
            } else if let player {
                VideoPlayer(player: player)
                    .background(.black)
            } else {
                ProgressView("Preparing media")
            }
        }
        .task(id: url) {
            let asset = AVURLAsset(url: url)
            do {
                guard try await asset.load(.isPlayable) else {
                    problem = "This codec or container is unsupported by iOS."
                    return
                }
                let duration = try await asset.load(.duration).seconds
                try Task.checkCancellation()
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                let active = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                defer { active.pause() }
                let position = seconds.isFinite ? max(seconds, 0) : 0
                if duration.isFinite, duration > 0, position > 0 {
                    _ = await active.seek(
                        to: CMTime(seconds: min(position, duration), preferredTimescale: 600),
                        toleranceBefore: .zero,
                        toleranceAfter: .zero
                    )
                }
                try Task.checkCancellation()
                player = active
                while !Task.isCancelled {
                    if let error = active.currentItem?.error {
                        problem = error.localizedDescription
                        break
                    }
                    record(active)
                    try await Task.sleep(for: .seconds(1))
                }
            } catch is CancellationError {
                // The reader left the screen.
            } catch {
                problem = error.localizedDescription
            }
        }
        .onDisappear { finish() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { finish() }
        }
    }

    private func record(_ active: AVPlayer) {
        let time = active.currentTime().seconds
        if time.isFinite, time >= 0 { onTime(time) }
    }

    private func finish() {
        guard let player else { return }
        player.pause()
        if player.currentItem?.status == .readyToPlay {
            record(player)
            onCheckpoint()
        }
    }
}
