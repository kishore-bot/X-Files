import AVFoundation
import AVKit
import SwiftUI

struct MediaReader: View {
    let url: URL
    let seconds: Double
    let onTime: (Double) -> Void
    let onCheckpoint: () -> Void
    var control: ReaderControl? = nil
    @Environment(\.scenePhase) private var scenePhase
    @State private var player: AVPlayer?
    @State private var optionsOpen = false
    @State private var audioTracks: AVMediaSelectionGroup?
    @State private var subtitles: AVMediaSelectionGroup?
    @State private var owner = UUID()
    @State private var fallback: Fallback?
    @State private var requestedPlay = false
    @State private var nativeAttempt = 0

    private struct Fallback {
        let seconds: Double
        let playing: Bool
    }

    var body: some View {
        Group {
            if let fallback {
                CompatibilityPlayer(url: url, seconds: fallback.seconds, resumePlaying: fallback.playing,
                                    control: control, onTime: onTime, onCheckpoint: onCheckpoint)
            } else if let player {
                VStack(spacing: 0) {
                    VideoPlayer(player: player).background(.black)
                    Menu {
                        Button("Playback options", systemImage: "slider.horizontal.3") { optionsOpen = true }
                        Button("Use compatibility playback", systemImage: "play.rectangle") { switchToFallback() }
                    } label: {
                        Label("Playback options", systemImage: "slider.horizontal.3")
                    }
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
        .task(id: nativeAttempt) {
            guard fallback == nil else { return }
            let asset = AVURLAsset(url: url)
            do {
                guard try await asset.load(.isPlayable) else {
                    switchToFallback()
                    return
                }
                let duration = try await asset.load(.duration).seconds
                try Task.checkCancellation()
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                let active = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                defer { active.pause(); control?.remove(owner: owner) }
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
                try Task.checkCancellation()
                installControl(active)
                while !Task.isCancelled {
                    if active.currentItem?.status == .failed || active.error != nil {
                        switchToFallback()
                        break
                    }
                    requestedPlay = active.timeControlStatus != .paused
                    record(active)
                    try await Task.sleep(for: .seconds(1))
                }
            } catch is CancellationError {
                // The reader left the screen.
            } catch {
                guard fallback == nil else { return }
                switchToFallback()
            }
        }
        .onDisappear { finish(); control?.remove(owner: owner) }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { finish() }
        }
    }

    private func installControl(_ active: AVPlayer) {
        var capabilities: Set<ReaderCommand.Capability> = [.playback, .seek]
        if audioTracks != nil { capabilities.insert(.audio) }
        if subtitles != nil { capabilities.insert(.subtitles) }
        control?.install(owner: owner, supports: capabilities) { [weak active] command in
            guard let active, let item = active.currentItem else { return .unavailable("Playback has closed.") }
            switch command {
            case .playback(let play):
                if play { active.play() } else { active.pause() }
                requestedPlay = play
            case let .seek(value, relative):
                let total = item.duration.seconds
                guard total.isFinite, total > 0 else { return .unavailable("This media has no seekable duration.") }
                let now = active.currentTime().seconds
                let target = value + (relative && now.isFinite ? now : 0)
                active.seek(to: CMTime(seconds: min(max(0, target), total), preferredTimescale: 600))
            case .audio, .subtitles:
                optionsOpen = true
            case .audioTrack(let query):
                guard let group = audioTracks, let option = group.options.match(query, label: { $0.displayName }) else {
                    return .unavailable("I couldn’t identify that audio track.")
                }
                item.select(option, in: group)
            case .subtitlesOn:
                guard let group = subtitles, let option = group.defaultOption ?? group.options.first else {
                    return .unavailable("This file has no available subtitle track.")
                }
                item.select(option, in: group)
            case .subtitlesOff:
                guard let subtitles, subtitles.allowsEmptySelection else { return .unavailable("These subtitles cannot be turned off.") }
                item.select(nil, in: subtitles)
            default:
                return .unavailable("That action isn’t available during playback.")
            }
            return .done
        }
    }

    private func switchToFallback() {
        guard fallback == nil else { return }
        let now = player?.currentTime().seconds
        let position = now.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? seconds
        let playing = requestedPlay || (player.map { $0.timeControlStatus != .paused } ?? false)
        player?.pause()
        player = nil
        optionsOpen = false
        control?.remove(owner: owner)
        fallback = Fallback(seconds: position, playing: playing)
        nativeAttempt += 1
    }

    private func record(_ active: AVPlayer) {
        let time = active.currentTime().seconds
        if time.isFinite, time >= 0 { onTime(time) }
    }

    private func finish() {
        requestedPlay = false
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
