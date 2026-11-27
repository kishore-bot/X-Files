import Foundation
import UniformTypeIdentifiers

enum DocumentKind: String, CaseIterable, Codable, Sendable {
    case pdf, text, image, media, other

    init(type: UTType?) {
        guard let type else { self = .other; return }
        if type.conforms(to: .pdf) { self = .pdf }
        else if type.conforms(to: .plainText) || type.conforms(to: .sourceCode) ||
            type.conforms(to: .json) || type.conforms(to: .xml) { self = .text }
        else if type.conforms(to: .image) { self = .image }
        else if type.conforms(to: .audiovisualContent) { self = .media }
        else { self = .other }
    }

    var title: String {
        switch self {
        case .pdf: "PDFs"
        case .text: "Text"
        case .image: "Images"
        case .media: "Audio & video"
        case .other: "Other"
        }
    }

    var symbol: String {
        switch self {
        case .pdf: "doc.richtext"
        case .text: "doc.text"
        case .image: "photo"
        case .media: "play.rectangle"
        case .other: "doc"
        }
    }
}

struct DocumentPosition: Codable, Equatable, Sendable {
    var page = 0
    var seconds = 0.0
    var textOffset = 0.0
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
}

struct ReaderFile: Sendable {
    let record: DocumentRecord
    let url: URL
}
