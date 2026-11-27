import AVFoundation
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
    @State private var optionsOpen = false
    @State private var audioTracks: AVMediaSelectionGroup?
    @State private var subtitles: AVMediaSelectionGroup?

    var body: some View {
        Group {
            if let problem {
                ContentUnavailableView("Media unavailable", systemImage: "play.slash", description: Text(problem))
            } else if let player {
                VStack(spacing: 0) {
                    VideoPlayer(player: player).background(.black)
                    Button("Playback options", systemImage: "slider.horizontal.3") { optionsOpen = true }
                        .padding(10)
                }
            } else {
                ProgressView("Preparing media")
            }
        }
        .sheet(isPresented: $optionsOpen) {
            if let player {
                MediaOptions(player: player, audio: audioTracks, subtitles: subtitles)
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
                audioTracks = try? await asset.loadMediaSelectionGroup(for: .audible)
                subtitles = try? await asset.loadMediaSelectionGroup(for: .legible)
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


private struct MediaOptions: View {
    let player: AVPlayer
    let audio: AVMediaSelectionGroup?
    let subtitles: AVMediaSelectionGroup?
    @Environment(\.dismiss) private var dismiss
    @State private var rate: Float = 1
    @State private var audioIndex: Int?
    @State private var subtitleIndex: Int?

    var body: some View {
        NavigationStack {
            Form {
                Picker("Speed", selection: $rate) {
                    ForEach([0.5, 0.75, 1, 1.25, 1.5, 2] as [Float], id: \.self) { rate in
                        Text("\(rate.formatted())×").tag(rate)
                    }
                }
                .onChange(of: rate) { _, value in
                    player.defaultRate = value
                    if player.rate != 0 { player.rate = value }
                }
                if let audio {
                    trackPicker("Audio", group: audio, selection: $audioIndex)
                }
                if let subtitles {
                    trackPicker("Subtitles", group: subtitles, selection: $subtitleIndex)
                }
            }
            .navigationTitle("Playback options")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear {
                rate = player.defaultRate
                audioIndex = selectedIndex(audio)
                subtitleIndex = selectedIndex(subtitles)
            }
        }
    }

    private func selectedIndex(_ group: AVMediaSelectionGroup?) -> Int? {
        guard let group, let selected = player.currentItem?.currentMediaSelection.selectedMediaOption(in: group) else { return nil }
        return group.options.firstIndex(of: selected)
    }

    private func trackPicker(_ title: String, group: AVMediaSelectionGroup, selection: Binding<Int?>) -> some View {
        Picker(title, selection: selection) {
            if group.allowsEmptySelection || selection.wrappedValue == nil {
                Text(group.allowsEmptySelection ? "Off" : "Automatic").tag(nil as Int?)
            }
            ForEach(Array(group.options.enumerated()), id: \.offset) { index, option in
                Text(option.displayName).tag(Optional(index))
            }
        }
        .onChange(of: selection.wrappedValue) { _, choice in
            if let choice, group.options.indices.contains(choice) {
                player.currentItem?.select(group.options[choice], in: group)
            } else if group.allowsEmptySelection {
                player.currentItem?.select(nil, in: group)
            }
        }
    }
}
