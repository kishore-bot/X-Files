import SwiftUI

struct ReadDestination: Hashable {
    let id: UUID
    var page: Int? = nil
}

struct ContentSearch: View {
    let library: LibraryModel
    let openFile: (ReadDestination) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var hits: [FileSearchHit] = []
    @State private var busy = false
    @State private var searched = false
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            List {
                if busy {
                    ProgressView("Searching document content")
                } else if let problem {
                    ContentUnavailableView("Search unavailable", systemImage: "exclamationmark.magnifyingglass", description: Text(problem))
                } else if hits.isEmpty {
                    ContentUnavailableView(searched ? "No matching passages" : "Search inside files", systemImage: "magnifyingglass")
                } else {
                    ForEach(Array(hits.enumerated()), id: \.offset) { _, hit in
                        if let record = library.documents.first(where: { $0.id == hit.id }) {
                            Button {
                                openFile(ReadDestination(id: hit.id, page: hit.page))
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(record.name).font(.headline)
                                    if let page = hit.page { Text("Page \(page + 1)").font(.caption).foregroundStyle(.secondary) }
                                    Text(hit.snippet).font(.subheadline).foregroundStyle(.secondary).lineLimit(4)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .navigationTitle("Search contents")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Words in your documents")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
            }
            .task(id: query) {
                let terms = query.trimmingCharacters(in: .whitespacesAndNewlines)
                hits = []
                problem = nil
                searched = false
                busy = !terms.isEmpty
                guard !terms.isEmpty else { return }
                do {
                    try await Task.sleep(for: .milliseconds(350))
                    let matches = try await library.store.findContents(terms)
                    try Task.checkCancellation()
                    hits = matches
                    searched = true
                    busy = false
                } catch is CancellationError {
                    return
                } catch {
                    if !Task.isCancelled {
                        busy = false
                        problem = error.localizedDescription
                    }
                }
            }
        }
    }
}
