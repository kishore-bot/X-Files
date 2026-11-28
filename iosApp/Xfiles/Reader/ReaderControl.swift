import Foundation

enum ReaderCommand: Equatable {
    case page(Int), pageStep(Int), firstPage, lastPage
    case scroll(Int), top, bottom
    case zoom(Bool), contents, chapter(Int, relative: Bool), notebook, source(Bool)
    case sheet(Int), cell(row: Int, column: Int)
    case photo(Int), rotatePhoto
    case playback(Bool), seek(Double, relative: Bool), subtitles, audio
    case audioTrack(String), subtitlesOn, subtitlesOff

    enum Capability: String, CaseIterable {
        case pages, scroll, zoom, contents, notebook, source, sheets, cells, photos, rotate
        case playback, seek, subtitles, audio

        var example: String {
            switch self {
            case .pages: "Next page"
            case .scroll: "Scroll down"
            case .zoom: "Zoom in"
            case .contents: "Show contents"
            case .notebook: "Show notebook"
            case .source: "Show source"
            case .sheets: "Next sheet"
            case .cells: "Select cell B3"
            case .photos: "Next photo"
            case .rotate: "Rotate photo"
            case .playback: "Pause video"
            case .seek: "Seek to 2:30"
            case .subtitles: "Show subtitles"
            case .audio: "Audio track 2"
            }
        }
    }

    var capability: Capability {
        switch self {
        case .page, .pageStep, .firstPage, .lastPage: .pages
        case .scroll, .top, .bottom: .scroll
        case .zoom: .zoom
        case .contents, .chapter: .contents
        case .notebook: .notebook
        case .source: .source
        case .sheet: .sheets
        case .cell: .cells
        case .photo: .photos
        case .rotatePhoto: .rotate
        case .playback: .playback
        case .seek: .seek
        case .subtitles, .subtitlesOn, .subtitlesOff: .subtitles
        case .audio, .audioTrack: .audio
        }
    }

    static func parse(_ input: String) -> ReaderCommand? {
        let text = input.lowercased().split(whereSeparator: \.isWhitespace).joined(separator: " ")
            .trimmingCharacters(in: CharacterSet(charactersIn: " .!?"))
        switch text {
        case "next page", "next slide": return .pageStep(1)
        case "next chapter": return .chapter(1, relative: true)
        case "previous page", "previous slide": return .pageStep(-1)
        case "previous chapter": return .chapter(-1, relative: true)
        case "first page", "first slide", "go to first page", "go to the first page": return .firstPage
        case "last page", "last slide", "go to last page", "go to the last page": return .lastPage
        case "scroll down": return .scroll(1)
        case "scroll up": return .scroll(-1)
        case "scroll to top", "scroll to the top", "go to top": return .top
        case "scroll to bottom", "scroll to the bottom", "go to bottom": return .bottom
        case "zoom in": return .zoom(true)
        case "zoom out": return .zoom(false)
        case "show contents", "table of contents", "show chapters": return .contents
        case "show notebook", "open notebook": return .notebook
        case "show source", "view source": return .source(true)
        case "show preview", "view preview": return .source(false)
        case "next sheet", "next tab": return .sheet(1)
        case "previous sheet", "previous tab": return .sheet(-1)
        case "next photo", "next image": return .photo(1)
        case "previous photo", "previous image": return .photo(-1)
        case "rotate photo", "rotate image": return .rotatePhoto
        case "play", "resume", "play video", "play audio", "resume video": return .playback(true)
        case "pause", "pause video", "pause audio": return .playback(false)
        case "show subtitles", "subtitles": return .subtitles
        case "subtitles on", "turn subtitles on": return .subtitlesOn
        case "subtitles off", "hide subtitles": return .subtitlesOff
        case "audio tracks", "show audio tracks": return .audio
        default: break
        }
        for prefix in ["go to chapter ", "chapter "] where text.hasPrefix(prefix) {
            if let number = Int(text.dropFirst(prefix.count)), number > 0 { return .chapter(number - 1, relative: false) }
        }
        for prefix in ["go to page ", "go to slide ", "page ", "slide "] where text.hasPrefix(prefix) {
            if let number = Int(text.dropFirst(prefix.count)), number > 0 { return .page(number - 1) }
        }
        if text.hasPrefix("select cell "), let cell = cellAddress(String(text.dropFirst(12))) { return cell }
        if text.hasPrefix("audio track ") {
            let track = String(text.dropFirst(12))
            if !track.isEmpty { return .audioTrack(track) }
        }
        for (prefix, sign, relative) in [("seek to ", 1.0, false), ("skip forward ", 1.0, true), ("skip back ", -1.0, true)] where text.hasPrefix(prefix) {
            let value = String(text.dropFirst(prefix.count)).replacingOccurrences(of: " seconds", with: "")
            let components = value.split(separator: ":", omittingEmptySubsequences: false)
            let parts = components.compactMap { Double($0) }
            guard (1...3).contains(parts.count), parts.count == components.count,
                  parts.allSatisfy({ $0.isFinite && (0...360_000).contains($0) }),
                  parts.dropFirst().allSatisfy({ $0 < 60 }) else { return nil }
            return .seek(sign * parts.reduce(0) { $0 * 60 + $1 }, relative: relative)
        }
        return nil
    }

    private static func cellAddress(_ text: String) -> ReaderCommand? {
        let bytes = Array(text.utf8)
        let letters = bytes.prefix(while: { (97...122).contains($0) })
        guard (1...3).contains(letters.count),
              let row = Int(String(decoding: bytes.dropFirst(letters.count), as: UTF8.self)),
              (1...1_048_576).contains(row) else { return nil }
        let column = letters.reduce(0) { $0 * 26 + Int($1 - 96) } - 1
        return column < 16_384 ? .cell(row: row, column: column) : nil
    }
}

enum ReaderActionResult {
    case done
    case unavailable(String)
}

@MainActor
final class ReaderControl {
    private var owner: UUID?
    private var handler: ((ReaderCommand) -> ReaderActionResult)?
    private var supports: Set<ReaderCommand.Capability> = []

    var examples: [String] {
        ReaderCommand.Capability.allCases.filter { supports.contains($0) }.map(\.example)
    }

    func install(owner: UUID, supports: Set<ReaderCommand.Capability>,
                 handler: @escaping (ReaderCommand) -> ReaderActionResult) {
        self.owner = owner
        self.supports = supports
        self.handler = handler
    }

    func remove(owner: UUID) {
        guard self.owner == owner else { return }
        self.owner = nil
        supports.removeAll()
        handler = nil
    }

    func accepts(_ command: ReaderCommand) -> Bool { supports.contains(command.capability) }

    func send(_ command: ReaderCommand) -> ReaderActionResult {
        guard supports.contains(command.capability), let handler else {
            return .unavailable("This action isn’t available for the current file.")
        }
        return handler(command)
    }
}
