import Foundation
import Observation
import SwiftUI
import UniformTypeIdentifiers

/// One local sidecar and one signed offset per library item, shared by both playback paths.
@MainActor @Observable
final class MediaSubtitleSession {
    private struct Cue: Decodable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let text: String
    }

    private var cues: [Cue] = []
    private var prefixEnd: [Int64] = []
    private var location: URL?
    private var offsetKey: String?
    var enabled = false
    private(set) var offset = 0
    var ready: Bool { !cues.isEmpty }

    func restore(for media: URL) async {
        let record = media.deletingLastPathComponent().deletingLastPathComponent()
        let sidecar = record.appendingPathComponent("subtitles.sidecar")
        location = sidecar
        let key = "xfiles.subtitle-offset." + record.lastPathComponent
        offsetKey = key
        offset = min(5_000, max(-5_000, UserDefaults.standard.integer(forKey: key)))
        enabled = false
        cues = []
        prefixEnd = []
        guard FileManager.default.fileExists(atPath: sidecar.path) else { return }
        do {
            let parsed: [Cue] = try await DocumentEngine.run(EngineRequest(operation: "subtitleTimeline", path: sidecar.path))
            guard !Task.isCancelled, location == sidecar else { return }
            replace(parsed)
        } catch { return }
    }

    func load(_ file: URL) async throws {
        guard let location else { throw EngineError(message: "The media is still opening.") }
        guard ["srt", "vtt"].contains(file.pathExtension.lowercased()) else {
            throw EngineError(message: "Choose an SRT or WebVTT subtitle file.")
        }
        let access = file.startAccessingSecurityScopedResource()
        defer { if access { file.stopAccessingSecurityScopedResource() } }
        let work = try OutputFiles.folder()
        defer { OutputFiles.remove(work) }
        let staged = work.appendingPathComponent("subtitles")
        let data = try await Task.detached(priority: .userInitiated) {
            let reader = try FileHandle(forReadingFrom: file)
            defer { try? reader.close() }
            let data = try reader.read(upToCount: 4 * 1024 * 1024 + 1) ?? Data()
            guard data.count <= 4 * 1024 * 1024 else { throw EngineError(message: "Subtitle files may be at most 4 MiB.") }
            try data.write(to: staged)
            return data
        }.value
        let parsed: [Cue] = try await DocumentEngine.run(EngineRequest(operation: "subtitleTimeline", path: staged.path))
        try Task.checkCancellation()
        guard self.location == location else { throw CancellationError() }
        try data.write(to: location, options: .atomic)
        replace(parsed)
    }

    private func replace(_ parsed: [Cue]) {
        cues = parsed
        var longest: Int64 = 0
        prefixEnd = parsed.map {
            longest = max(longest, $0.endMs)
            return longest
        }
        enabled = true
    }

    func shift(_ delta: Int) {
        offset = min(5_000, max(-5_000, offset + delta))
        if let offsetKey { UserDefaults.standard.set(offset, forKey: offsetKey) }
    }

    func text(at seconds: Double) -> String {
        guard enabled, !cues.isEmpty, seconds.isFinite, (0...4_000_000).contains(seconds) else { return "" }
        let time = Int64(seconds * 1000) - Int64(offset)
        var low = 0
        var high = cues.count
        while low < high {
            let middle = (low + high) / 2
            if cues[middle].startMs <= time { low = middle + 1 } else { high = middle }
        }
        var result: [String] = []
        var index = low - 1
        while index >= 0 && prefixEnd[index] > time {
            if cues[index].endMs > time { result.append(cues[index].text) }
            index -= 1
        }
        return result.reversed().joined(separator: "\n")
    }
}

struct TimedSidecarOverlay: View {
    let session: MediaSubtitleSession
    let mediaTime: () -> Double
    @State private var text = ""

    var body: some View {
        VStack {
            Spacer()
            if session.enabled && !text.isEmpty {
                Text(text)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(8)
                    .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 6))
                    .padding(.horizontal, 16)
                    .padding(.bottom, 52)
            }
        }
        .allowsHitTesting(false)
        .task(id: session.enabled) {
            guard session.enabled else { text = ""; return }
            while !Task.isCancelled {
                let cue = session.text(at: mediaTime())
                if cue != text { text = cue }
                do { try await Task.sleep(for: .milliseconds(50)) } catch { return }
            }
        }
    }
}

struct SidecarTimingOptions: View {
    let session: MediaSubtitleSession
    var selected: () -> Void
    @State private var picker = false
    @State private var importTask: Task<Void, Never>?
    @State private var issue: String?

    var body: some View {
        Section("External subtitles") {
            Button("Choose SRT or WebVTT") { picker = true }.disabled(importTask != nil)
            if session.ready {
                Toggle("Show external subtitles", isOn: Binding(
                    get: { session.enabled },
                    set: { session.enabled = $0; if $0 { selected() } }
                ))
                if session.enabled {
                    Text("Offset \((Double(session.offset) / 1000).formatted(.number.precision(.fractionLength(0...2)))) s")
                    HStack {
                        Button("Earlier") { session.shift(-250) }.disabled(session.offset <= -5_000)
                        Spacer()
                        Button("Later") { session.shift(250) }.disabled(session.offset >= 5_000)
                        Spacer()
                        Button("Reset") { session.shift(-session.offset) }.disabled(session.offset == 0)
                    }
                    .buttonStyle(.borderless)
                }
            }
            if importTask != nil { ProgressView("Reading subtitles") }
            if let issue { Text(issue).foregroundStyle(.secondary) }
        }
        .fileImporter(isPresented: $picker, allowedContentTypes: [.data]) { result in
            issue = nil
            importTask = Task {
                defer { importTask = nil }
                do {
                    try await session.load(result.get())
                    selected()
                } catch is CancellationError {
                    return
                } catch { issue = error.localizedDescription }
            }
        }
        .onDisappear { importTask?.cancel() }
    }
}
