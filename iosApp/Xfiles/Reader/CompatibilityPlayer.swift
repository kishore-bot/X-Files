import AVFoundation
import Foundation
import MPVKit
import SwiftUI

struct CompatibilityPlayer: View {
    let url: URL
    let seconds: Double
    let resumePlaying: Bool
    let control: ReaderControl?
    let onTime: (Double) -> Void
    let onCheckpoint: () -> Void
    let sidecar: MediaSubtitleSession
    let reason: String?

    @Environment(\.scenePhase) private var scenePhase
    @State private var player: MPVKit.Player?
    @State private var owner = UUID()
    @State private var failure: String?
    @State private var ready = false
    @State private var tracksOpen = false
    @State private var audioSelection: Int?
    @State private var textSelection: Int?
    @State private var seekPreview: Double?

    var body: some View {
        Group {
            if let failure {
                ContentUnavailableView("Media unavailable", systemImage: "play.slash", description: Text(failure))
            } else if let player {
                VStack(spacing: 10) {
                    MPVKit.PlayerView(player: player)
                        .background(.black)
                        .overlay { TimedSidecarOverlay(session: sidecar) { Self.seconds(player.state.position) } }
                        .overlay { if !ready { ProgressView().tint(.white) } }
                    if ready {
                        let total = Self.seconds(player.state.duration)
                        let position = min(max(0, Self.seconds(player.state.position)), max(total, 0))
                        if total > 0 {
                            Slider(value: Binding(
                                get: { seekPreview ?? position },
                                set: { seekPreview = $0 }
                            ), in: 0...total, onEditingChanged: { editing in
                                if !editing, let value = seekPreview {
                                    player.seek(to: .seconds(value))
                                    seekPreview = nil
                                }
                            })
                            .accessibilityLabel("Playback position")
                            HStack {
                                Text(Self.timestamp(seekPreview ?? position))
                                Spacer()
                                Text(Self.timestamp(total))
                            }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                        HStack {
                            Button("Back 10 seconds", systemImage: "gobackward.10") { _ = send(.seek(-10, relative: true), to: player) }
                            Spacer()
                            Button(player.state.isPlaying ? "Pause" : "Play", systemImage: player.state.isPlaying ? "pause.fill" : "play.fill") {
                                _ = send(.playback(!player.state.isPlaying), to: player)
                            }
                            Spacer()
                            Button("Forward 10 seconds", systemImage: "goforward.10") { _ = send(.seek(10, relative: true), to: player) }
                            Spacer()
                            Button("Tracks", systemImage: "list.bullet") { tracksOpen = true }
                        }
                        .labelStyle(.iconOnly)
                        .font(.title3)
                        .buttonStyle(.bordered)
                    }
                    Text(reason ?? "Compatibility playback").font(.caption).foregroundStyle(.secondary)
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
            } else {
                ProgressView("Preparing compatibility playback")
            }
        }
        .sheet(isPresented: $tracksOpen) {
            if let player {
                NavigationStack {
                    Form {
                        MediaRouteDetails(engine: "MPVKit", reportsSystemSpatialSupport: false, showsHDR: player.state.videoInfo != nil)
                        if !player.state.audioTracks.isEmpty {
                            Picker("Audio", selection: $audioSelection) {
                                Text("Automatic").tag(nil as Int?)
                                ForEach(player.state.audioTracks) { track in
                                    Text(Self.label(track)).tag(Optional(track.id))
                                }
                            }.onChange(of: audioSelection) { _, id in player.selectAudioTrack(id: id.map { String($0) } ?? "auto") }
                        }
                        Picker("Subtitles", selection: $textSelection) {
                            Text("Off").tag(nil as Int?)
                            ForEach(player.state.subtitleTracks) { track in
                                Text(Self.label(track)).tag(Optional(track.id))
                            }
                        }.onChange(of: textSelection) { _, id in
                            if id != nil { sidecar.enabled = false }
                            player.selectSubtitle(id: id.map { String($0) })
                        }
                        SidecarTimingOptions(session: sidecar) {
                            textSelection = nil
                            player.selectSubtitle(id: nil)
                        }
                    }
                    .navigationTitle("Media tracks")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { tracksOpen = false } } }
                }
            }
        }
        .task(id: url) { await start() }
        .onDisappear { stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { checkpoint() }
        }
    }

    private func start() async {
        guard Self.isLocalMedia(url) else {
            failure = "Compatibility playback requires a supported local media container."
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try? session.setSupportsMultichannelContent(false)
            let active = try MPVKit.Player()
            player = active
            // The engine starts on file-loaded. Keep it silent until Xfiles pauses and restores position.
            active.setVolume(0)
            active.play(url: url)
            for _ in 0..<160 {
                try await Task.sleep(for: .milliseconds(100))
                if Self.seconds(active.state.duration) > 0 || !active.state.audioTracks.isEmpty || active.state.videoInfo != nil {
                    if let error = active.state.error { throw EngineError(message: error) }
                    break
                }
                if let error = active.state.error { throw EngineError(message: error) }
            }
            guard Self.seconds(active.state.duration) > 0 || !active.state.audioTracks.isEmpty || active.state.videoInfo != nil else {
                throw EngineError(message: "The compatibility player could not open this file.")
            }
            active.pause()
            let duration = Self.seconds(active.state.duration)
            if seconds.isFinite, seconds > 0 {
                active.seek(to: .seconds(duration > 0 ? min(seconds, duration) : seconds))
            }
            active.selectSubtitle(id: nil)
            active.setVolume(1)
            if resumePlaying && scenePhase == .active { active.resume() }
            ready = true
            control?.install(owner: owner, supports: [.playback, .seek, .audio, .subtitles]) { [weak active] command in
                guard let active else { return .unavailable("Playback has closed.") }
                return send(command, to: active)
            }
            while !Task.isCancelled {
                try await Task.sleep(for: .seconds(1))
                if let error = active.state.error { throw EngineError(message: error) }
                onTime(max(0, Self.seconds(active.state.position)))
            }
        } catch is CancellationError {
            return
        } catch {
            stop()
            failure = error.localizedDescription
        }
    }

    private func send(_ command: ReaderCommand, to player: MPVKit.Player) -> ReaderActionResult {
        switch command {
        case .playback(let play):
            if play { player.resume() } else { player.pause() }
        case let .seek(value, relative):
            let duration = Self.seconds(player.state.duration)
            guard duration > 0 else { return .unavailable("This media has no seekable duration.") }
            let target = relative ? Self.seconds(player.state.position) + value : value
            player.seek(to: .seconds(min(max(0, target), duration)))
        case .audio, .subtitles:
            tracksOpen = true
        case .audioTrack(let query):
            guard let track = player.state.audioTracks.match(query, label: Self.label) else {
                return .unavailable("I couldn’t identify that audio track.")
            }
            audioSelection = track.id
            player.selectAudioTrack(id: String(track.id))
        case .subtitlesOn:
            if sidecar.ready {
                sidecar.enabled = true
                textSelection = nil
                player.selectSubtitle(id: nil)
                return .done
            }
            let tracks = player.state.subtitleTracks
            guard let track = tracks.first(where: \.isDefault) ?? tracks.first else {
                return .unavailable("This file has no available subtitle track.")
            }
            textSelection = track.id
            player.selectSubtitle(id: String(track.id))
        case .subtitlesOff:
            sidecar.enabled = false
            textSelection = nil
            player.selectSubtitle(id: nil)
        default:
            return .unavailable("That action isn’t available during playback.")
        }
        return .done
    }

    private func checkpoint() {
        guard let player else { return }
        player.pause()
        if ready { onTime(max(0, Self.seconds(player.state.position))); onCheckpoint() }
    }

    private func stop() {
        checkpoint()
        control?.remove(owner: owner)
        player?.stop()
        player = nil
        ready = false
    }

    private static func seconds(_ duration: Duration) -> Double {
        Double(duration.components.seconds) + Double(duration.components.attoseconds) / 1e18
    }

    private static func timestamp(_ seconds: Double) -> String {
        let whole = Int(min(max(seconds, 0), 360_000))
        return "\(whole / 60):" + String(format: "%02d", whole % 60)
    }

    private static func label(_ track: MPVKit.TrackInfo) -> String {
        [track.title, track.lang, track.codec].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
            .nonempty ?? "Track \(track.id)"
    }

    // A local path alone is insufficient: playlists can contain remote URLs.
    private nonisolated static func isLocalMedia(_ url: URL) -> Bool {
        guard url.isFileURL, let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: 401) else { return false }
        let bytes = Array(data)
        let starts: (String) -> Bool = { bytes.starts(with: $0.utf8) }
        if bytes.starts(with: [0x1A, 0x45, 0xDF, 0xA3]) { return true } // Matroska / WebM
        if starts("RIFF"), bytes.count >= 12 {
            let format = String(decoding: bytes[8..<12], as: UTF8.self)
            return ["AVI ", "WAVE"].contains(format)
        }
        if bytes.count >= 8, ["ftyp", "moov", "mdat", "wide"].contains(String(decoding: bytes[4..<8], as: UTF8.self)) {
            return true
        }
        if starts("fLaC") || starts("OggS") || starts("ID3") { return true }
        if bytes.starts(with: [0x0B, 0x77]) { return true } // AC-3 / E-AC-3
        if bytes.starts(with: [0, 0, 1, 0xBA]) { return true } // MPEG program stream
        if bytes.starts(with: [0x7F, 0xFE, 0x80, 0x01]) || bytes.starts(with: [0xFE, 0x7F, 0x01, 0x80]) { return true }
        if bytes.count >= 8 && bytes[4..<8].elementsEqual([0xF8, 0x72, 0x6F, 0xBA]) { return true } // TrueHD major sync
        if bytes.count > 388 && ((bytes[0] == 0x47 && bytes[188] == 0x47 && bytes[376] == 0x47)
                                 || (bytes[4] == 0x47 && bytes[196] == 0x47 && bytes[388] == 0x47)) { return true }
        return bytes.count >= 2 && bytes[0] == 0xFF && (bytes[1] & 0xE0) == 0xE0 // AAC / MPEG audio
    }
}

extension Array {
    func match(_ query: String, label: (Element) -> String) -> Element? {
        if let index = Int(query), index > 0, indices.contains(index - 1) { return self[index - 1] }
        let matches = filter { label($0).localizedStandardContains(query) }
        return matches.count == 1 ? matches.first : nil
    }
}

private extension String {
    var nonempty: String? { isEmpty ? nil : self }
}
