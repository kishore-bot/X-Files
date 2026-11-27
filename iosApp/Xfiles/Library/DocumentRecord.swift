import Foundation
import UniformTypeIdentifiers

enum DocumentKind: String, CaseIterable, Codable, Sendable {
    case pdf, text, image, media, book, office, sheet, slides, archive, other

    init(type: UTType?) {
        guard let type else { self = .other; return }
        if type.conforms(to: .pdf) { self = .pdf }
        else if type.conforms(to: .plainText) || type.conforms(to: .sourceCode) ||
            type.conforms(to: .json) || type.conforms(to: .xml) { self = .text }
        else if type.conforms(to: .image) { self = .image }
        else if type.conforms(to: .audiovisualContent) { self = .media }
        else { self = .other }
    }

    static func classify(_ name: String, type: UTType? = nil) -> DocumentKind {
        if name.lowercased().hasSuffix(".tar.gz") { return .archive }
        switch (name as NSString).pathExtension.lowercased() {
        case "epub": return .book
        case "doc", "docx", "odt", "rtf": return .office
        case "xls", "xlsx", "ods", "csv", "tsv": return .sheet
        case "ppt", "pptx", "odp": return .slides
        case "zip", "tar", "tgz", "7z": return .archive
        case "mkv", "webm", "avi", "ac3", "eac3", "dts", "truehd", "m2ts", "opus", "oga", "ogg": return .media
        default:
            let declared = DocumentKind(type: type)
            return declared == .other ? DocumentKind(type: UTType(filenameExtension: (name as NSString).pathExtension)) : declared
        }
    }

    var title: String {
        switch self {
        case .pdf: "PDFs"
        case .text: "Text"
        case .image: "Images"
        case .media: "Audio & video"
        case .book: "EPUB"
        case .office: "Documents"
        case .sheet: "Spreadsheets"
        case .slides: "Presentations"
        case .archive: "ZIP archives"
        case .other: "Other"
        }
    }

    var symbol: String {
        switch self {
        case .pdf: "doc.richtext"
        case .text: "doc.text"
        case .image: "photo"
        case .media: "play.rectangle"
        case .book: "book"
        case .office: "doc.richtext"
        case .sheet: "tablecells"
        case .slides: "rectangle.on.rectangle"
        case .archive: "archivebox"
        case .other: "doc"
        }
    }
}

struct DocumentPosition: Codable, Equatable, Sendable {
    var page = 0
    var seconds = 0.0
    var textOffset = 0.0
    var block: Int?
    var section: Int?
    var bookOffset: Int?
}

struct DocumentRecord: Codable, Identifiable, Sendable {
    let schemaVersion: Int
    let id: UUID
    let name: String
    let kind: DocumentKind
    let byteCount: Int64
    let importedAt: Date
    var openedAt: Date? = nil
    var bookmarked = false
    var position = DocumentPosition()

    // Earlier iOS imports stored these formats as “Other” or “Text.”
    var viewerKind: DocumentKind {
        let classified = DocumentKind.classify(name)
        return classified == .other ? kind : classified
    }
}

struct ReaderFile: Sendable {
    let record: DocumentRecord
    let url: URL
}
