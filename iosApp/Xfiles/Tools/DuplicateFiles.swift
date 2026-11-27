import CryptoKit
import SwiftUI

struct DuplicateFiles: View {
    let library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var groups: [[DocumentRecord]]?
    @State private var error: String?
    @State private var selected: DocumentRecord?

    var body: some View {
        NavigationStack {
            Group {
                if let error {
                    ContentUnavailableView("Couldn’t compare files", systemImage: "doc.on.doc", description: Text(error))
                } else if let groups {
                    if groups.isEmpty {
                        ContentUnavailableView("No identical files", systemImage: "checkmark.circle")
                    } else {
                        List {
                            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                                Section("Identical copies \(index + 1)") {
                                    ForEach(group) { record in
                                        HStack {
                                            Text(record.name)
                                            Spacer()
                                            Button(role: .destructive) { selected = record } label: {
                                                Label("Remove", systemImage: "trash")
                                            }
                                            .labelStyle(.iconOnly)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    ProgressView("Comparing files on device")
                }
            }
            .navigationTitle("Find duplicates")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Remove this imported copy?", isPresented: Binding(
                get: { selected != nil }, set: { if !$0 { selected = nil } }
            ), titleVisibility: .visible, presenting: selected) { record in
                Button("Remove from Xfiles", role: .destructive) {
                    selected = nil
                    Task { await library.remove(record.id) }
                }
            } message: { record in Text(record.name) }
            .task(id: library.documents.map(\.id)) { await compare() }
        }
    }

    private func compare() async {
        let buckets = Dictionary(grouping: library.documents, by: \.byteCount).values.filter { $0.count > 1 }
        groups = nil
        error = nil
        do {
            var files: [ReaderFile] = []
            for record in buckets.flatMap({ $0 }) {
                try Task.checkCancellation()
                files.append(try await library.store.file(record.id))
            }
            groups = try await Self.match(files)
        } catch is CancellationError {
            return
        } catch {
            self.error = error.localizedDescription
        }
    }

    private nonisolated static func match(_ files: [ReaderFile]) async throws -> [[DocumentRecord]] {
        let worker = Task.detached(priority: .utility) {
            var hashes: [SHA256.Digest: [DocumentRecord]] = [:]
            for file in files {
                try Task.checkCancellation()
                let handle = try FileHandle(forReadingFrom: file.url)
                defer { try? handle.close() }
                var digest = SHA256()
                while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
                    try Task.checkCancellation()
                    digest.update(data: data)
                }
                hashes[digest.finalize(), default: []].append(file.record)
            }
            return hashes.values.filter { $0.count > 1 }
                .map { $0.sorted { $0.importedAt < $1.importedAt } }
                .sorted { ($0.first?.name ?? "") < ($1.first?.name ?? "") }
        }
        return try await withTaskCancellationHandler {
            let result = try await worker.value
            try Task.checkCancellation()
            return result
        } onCancel: { worker.cancel() }
    }
}
