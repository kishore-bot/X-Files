import Foundation
import Observation

@MainActor
@Observable
final class LibraryModel {
    let store: DocumentStore
    private(set) var documents: [DocumentRecord] = []
    private(set) var loading = true
    private(set) var importCount = 0
    var message: String?

    init(store: DocumentStore = DocumentStore()) {
        self.store = store
    }

    func reload() async {
        do { documents = try await store.records() }
        catch { message = error.localizedDescription }
        loading = false
    }

    func importFiles(_ urls: [URL]) async -> [UUID] {
        guard !urls.isEmpty else { return [] }
        importCount += urls.count
        defer { importCount -= urls.count }
        var imported: [UUID] = []
        var failures: [String] = []
        for url in urls {
            do {
                let record = try await store.importFile(url)
                update(record)
                imported.append(record.id)
            } catch {
                failures.append("\(url.lastPathComponent) — \(error.localizedDescription)")
            }
        }
        if let first = failures.first {
            message = failures.count == 1 ? first : "\(first)\n\(failures.count - 1) more files could not be imported."
        }
        return imported
    }

    func importOutput(_ url: URL) async throws -> UUID {
        let record = try await store.importFile(url)
        update(record)
        return record.id
    }

    func open(_ id: UUID) async throws -> ReaderFile {
        let file = try await store.open(id)
        update(file.record)
        return file
    }

    func bookmark(_ id: UUID) async {
        do { update(try await store.toggleBookmark(id)) }
        catch { message = error.localizedDescription }
    }

    func setBookmark(_ id: UUID, saved: Bool) async throws {
        update(try await store.setBookmark(id, saved: saved))
    }

    func remove(_ id: UUID) async {
        do {
            try await store.remove(id)
            documents.removeAll { $0.id == id }
        } catch { message = error.localizedDescription }
    }

    private func update(_ record: DocumentRecord) {
        if let index = documents.firstIndex(where: { $0.id == record.id }) {
            documents[index] = record
        } else {
            documents.insert(record, at: 0)
        }
    }
}
