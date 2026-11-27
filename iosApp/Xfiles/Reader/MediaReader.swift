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
    @AppStorage("xfiles.media.autoLosslessUSB") private var autoLosslessUSB = true
    @State private var player: AVPlayer?
    @State private var sidecar = MediaSubtitleSession()
    @State private var optionsOpen = false
    @State private var audioTracks: AVMediaSelectionGroup?
    @State private var subtitles: AVMediaSelectionGroup?
    @State private var owner = UUID()
    @State private var fallback: Fallback?
    @State private var requestedPlay = false
    @State private var nativeAttempt = 0
    @State private var diagnostics = MediaSourceDiagnostics()
    @State private var usbAudioStatus: String?

    private struct Fallback {
        let seconds: Double
        let playing: Bool
        let reason: String?
    }

    var body: some View {
        Group {
            if let fallback {
                CompatibilityPlayer(url: url, seconds: fallback.seconds, resumePlaying: fallback.playing,
                                    control: control, onTime: onTime, onCheckpoint: onCheckpoint, sidecar: sidecar, reason: fallback.reason)
            } else if let player {
                VStack(spacing: 0) {
                    VideoPlayer(player: player).background(.black)
                        .overlay { TimedSidecarOverlay(session: sidecar) { player.currentTime().seconds } }
                    if let usbAudioStatus {
                        Label(usbAudioStatus, systemImage: "cable.connector")
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(.regularMaterial, in: Capsule())
                    }
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
                MediaOptions(player: player, audio: audioTracks, subtitles: subtitles, sidecar: sidecar, diagnostics: diagnostics, usbStatus: usbAudioStatus,
                             autoLosslessUSB: $autoLosslessUSB, onCompatibility: { switchToFallback() })
            }
        }
        .task(id: url) {
            await sidecar.restore(for: url)
            if sidecar.enabled, let subtitles, subtitles.allowsEmptySelection {
                player?.currentItem?.select(nil, in: subtitles)
            }
        }
        .task(id: nativeAttempt) {
            guard fallback == nil else { return }
            let asset = AVURLAsset(url: url)
            do {
                guard try await asset.load(.isPlayable) else {
                    switchToFallback(reason: "AVPlayer could not open this format.")
                    return
                }
                let facts = await MediaSourceDiagnostics.load(from: asset)
                try Task.checkCancellation()
                diagnostics = facts
                if !facts.audio.isEmpty && facts.audio.allSatisfy({ !$0.playable }) {
                    switchToFallback(reason: "AVPlayer found audio tracks but could not play them.")
                    return
                }
                let duration = try await asset.load(.duration).seconds
                try Task.checkCancellation()
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default)
                try? session.setSupportsMultichannelContent(facts.audio.contains { $0.playable && $0.channels > 2 })
                let item = AVPlayerItem(asset: asset)
                item.allowedAudioSpatializationFormats = [.monoAndStereo, .multichannel]
                let active = AVPlayer(playerItem: item)
                let usbRate = MediaUSBRatePreference()
                defer {
                    usbRate.clear(session: session)
                    usbAudioStatus = nil
                    active.pause()
                    control?.remove(owner: owner)
                }
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
                if sidecar.enabled, let subtitles, subtitles.allowsEmptySelection {
                    active.currentItem?.select(nil, in: subtitles)
                }
                try Task.checkCancellation()
                installControl(active)
                while !Task.isCancelled {
                    if active.currentItem?.status == .failed || active.error != nil {
                        switchToFallback(reason: "Native playback failed.")
                        break
                    }
                    if active.currentItem?.status == .readyToPlay,
                       item.tracks.contains(where: { activeTrack in
                           guard activeTrack.isEnabled, let id = activeTrack.assetTrack?.trackID else { return false }
                           return facts.audio.contains { $0.id == id && !$0.playable }
                       }) {
                        switchToFallback(reason: "AVPlayer could not play the selected audio track.")
                        break
                    }
                    if scenePhase == .active {
                        let selected = Set(item.tracks.compactMap { track in
                            track.isEnabled ? track.assetTrack?.trackID : nil
                        })
                        let selectedAudio = facts.audio.filter { selected.contains($0.id) }
                        let source = selectedAudio.count == 1 ? selectedAudio.first : (facts.audio.count == 1 ? facts.audio.first : nil)
                        usbAudioStatus = usbRate.update(session: session, sourceRate: autoLosslessUSB ? source?.losslessRate : nil)
                    } else {
                        usbRate.clear(session: session)
                        usbAudioStatus = nil
                    }
                    requestedPlay = active.timeControlStatus != .paused
                    record(active)
                    try await Task.sleep(for: .seconds(1))
                }
            } catch is CancellationError {
                // The reader left the screen.
            } catch {
                guard fallback == nil else { return }
                switchToFallback(reason: "Native playback could not start.")
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
        capabilities.insert(.subtitles)
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
                if sidecar.ready {
                    sidecar.enabled = true
                    if let subtitles, subtitles.allowsEmptySelection { item.select(nil, in: subtitles) }
                    return .done
                }
                guard let group = subtitles, let option = group.defaultOption ?? group.options.first else {
                    return .unavailable("This file has no available subtitle track.")
                }
                item.select(option, in: group)
            case .subtitlesOff:
                if sidecar.enabled { sidecar.enabled = false; return .done }
                guard let subtitles, subtitles.allowsEmptySelection else { return .unavailable("These subtitles cannot be turned off.") }
                item.select(nil, in: subtitles)
            default:
                return .unavailable("That action isn’t available during playback.")
            }
            return .done
        }
    }

    private func switchToFallback(reason: String? = nil) {
        guard fallback == nil else { return }
        let now = player?.currentTime().seconds
        let position = now.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? seconds
        let playing = requestedPlay || (player.map { $0.timeControlStatus != .paused } ?? false)
        player?.pause()
        player = nil
        optionsOpen = false
        control?.remove(owner: owner)
        fallback = Fallback(seconds: position, playing: playing, reason: reason)
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
    let sidecar: MediaSubtitleSession
    let diagnostics: MediaSourceDiagnostics
    let usbStatus: String?
    @Binding var autoLosslessUSB: Bool
    let onCompatibility: () -> Void
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
                    trackPicker("Subtitles", group: subtitles, selection: $subtitleIndex, embedded: true)
                }
                Section("Source audio tracks") {
                    ForEach(diagnostics.audio) { track in
                        LabeledContent(track.language ?? "Audio", value: track.playable ? track.format : track.format + " · unavailable in AVPlayer")
                    }
                    Button("Use compatibility playback", action: onCompatibility)
                }
                if let sourceColor = diagnostics.sourceColor {
                    LabeledContent("Source color", value: sourceColor)
                }
                Toggle("Match source rate on USB", isOn: $autoLosslessUSB)
                MediaRouteDetails(engine: "AVPlayer", showsHDR: diagnostics.hasVideo, usbStatus: usbStatus)
                SidecarTimingOptions(session: sidecar) {
                    if let subtitles, subtitles.allowsEmptySelection {
                        player.currentItem?.select(nil, in: subtitles)
                        subtitleIndex = nil
                    }
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

    private func trackPicker(_ title: String, group: AVMediaSelectionGroup, selection: Binding<Int?>, embedded: Bool = false) -> some View {
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
                if embedded { sidecar.enabled = false }
                player.currentItem?.select(group.options[choice], in: group)
            } else if group.allowsEmptySelection {
                player.currentItem?.select(nil, in: group)
            }
        }
    }
}
