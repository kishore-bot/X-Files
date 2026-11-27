import SwiftUI

struct BookAnchor: Codable, Hashable, Sendable, Comparable {
    let block: Int
    var offset: Int = 0

    static func < (lhs: BookAnchor, rhs: BookAnchor) -> Bool {
        (lhs.block, lhs.offset) < (rhs.block, rhs.offset)
    }
}

struct NotebookEntry: Codable, Sendable, Identifiable {
    enum Kind: String, Codable, Sendable {
        case bookmark, highlight, note

        var title: String {
            switch self {
            case .bookmark: "Bookmark"
            case .highlight: "Highlight"
            case .note: "Note"
            }
        }

        var icon: String {
            switch self {
            case .bookmark: "bookmark"
            case .highlight: "highlighter"
            case .note: "note.text"
            }
        }
    }

    var id = UUID()
    let kind: Kind
    let anchor: BookAnchor
    let excerpt: String
    var length: Int?
    var note: String?
}

enum NotebookChange: Sendable {
    case toggle(NotebookEntry)
    case note(NotebookEntry)
    case remove(UUID)
}

struct EpubNotebook: View {
    let entries: [NotebookEntry]
    let onOpen: (BookAnchor) -> Void
    let onRemove: (UUID) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var filter: NotebookEntry.Kind?

    var body: some View {
        NavigationStack {
            List {
                let visible = entries.filter { filter == nil || $0.kind == filter }
                if visible.isEmpty {
                    ContentUnavailableView("No entries", systemImage: "book.closed",
                                           description: Text("Long-press a passage to bookmark, highlight, or add a note."))
                }
                ForEach(visible) { entry in
                    Button {
                        dismiss()
                        onOpen(entry.anchor)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Label(entry.kind.title, systemImage: entry.kind.icon).font(.caption)
                            if let note = entry.note { Text(note).font(.body) }
                            Text(entry.excerpt).font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                        }
                    }
                    .swipeActions(allowsFullSwipe: false) {
                        Button("Remove", role: .destructive) { onRemove(entry.id) }
                    }
                }
            }
            .navigationTitle("Notebook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Picker("Filter notebook", selection: $filter) {
                        Text("All").tag(nil as NotebookEntry.Kind?)
                        Text("Bookmarks").tag(Optional(NotebookEntry.Kind.bookmark))
                        Text("Highlights").tag(Optional(NotebookEntry.Kind.highlight))
                        Text("Notes").tag(Optional(NotebookEntry.Kind.note))
                    }
                }
            }
        }
    }
}
