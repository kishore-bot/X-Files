import SwiftUI

struct ArchiveReader: View {
    let file: ReaderFile
    let library: LibraryModel
    @State private var entries: [ArchiveItem]?
    @State private var selected: ArchiveItem?
    @State private var password = ""
    @State private var job: Task<Void, Never>?
    @State private var problem: String?
    @State private var directory = ""
    @State private var openFile: UUID?

    private struct Row: Identifiable {
        let name: String
        let path: String
        let entry: ArchiveItem?
        var id: String { path }
    }

    var body: some View {
        Group {
            if let entries {
                List {
                    if !directory.isEmpty {
                        Button { directory = directory.split(separator: "/").dropLast().joined(separator: "/") } label: {
                            Label("Parent folder", systemImage: "chevron.up")
                        }
                    }
                    ForEach(rows(entries)) { row in
                        Button {
                            if let entry = row.entry {
                                selected = entry
                                if !entry.protected { extract(entry, password: nil) }
                            } else {
                                directory = row.path
                            }
                        } label: {
                            HStack {
                                Label(row.name, systemImage: row.entry == nil ? "folder" : row.entry?.protected == true ? "lock.doc" : "doc")
                                Spacer()
                                if let size = row.entry?.size {
                                    Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .disabled(job != nil)
                    }
                }
                .overlay {
                    if entries.isEmpty { ContentUnavailableView("Empty ZIP", systemImage: "archivebox") }
                }
            } else if let problem {
                ContentUnavailableView("ZIP unavailable", systemImage: "archivebox", description: Text(problem))
            } else {
                ProgressView("Reading ZIP")
            }
        }
        .navigationDestination(item: $openFile) { id in
            ReaderScreen(id: id, library: library)
        }
        .safeAreaInset(edge: .bottom) {
            if job != nil {
                HStack {
                    ProgressView("Extracting entry")
                    Spacer()
                    Button("Cancel") { job?.cancel() }
                }
                .padding().background(.regularMaterial)
            }
        }
        .alert("Unlock ZIP entry", isPresented: Binding(
            get: { selected?.protected == true && job == nil && problem == nil },
            set: { if !$0 && job == nil { selected = nil } }
        )) {
            SecureField("ZIP password", text: $password)
            Button("Cancel", role: .cancel) { selected = nil; password = "" }
            Button("Extract") {
                if let selected { extract(selected, password: password) }
                password = ""
            }
        }
        .alert("Extraction failed", isPresented: Binding(
            get: { entries != nil && problem != nil },
            set: { if !$0 { problem = nil } }
        )) {
            Button("OK") { problem = nil; selected = nil }
        } message: { Text(problem ?? "") }
        .task(id: file.record.id) {
            do { entries = try await DocumentEngine.run(EngineRequest(operation: "archive", path: file.url.path)) }
            catch is CancellationError { return }
            catch { problem = error.localizedDescription }
        }
        .onDisappear { job?.cancel() }
    }

    private func rows(_ entries: [ArchiveItem]) -> [Row] {
        let prefix = directory.isEmpty ? "" : directory + "/"
        var result: [String: Row] = [:]
        for entry in entries where entry.path.hasPrefix(prefix) {
            let remainder = entry.path.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: true)
            guard let component = remainder.first else { continue }
            let name = String(component)
            let path = prefix + name
            let isFolder = remainder.count > 1 || entry.directory
            result[path] = Row(name: name, path: path, entry: isFolder ? nil : entry)
        }
        return result.values.sorted {
            if ($0.entry == nil) != ($1.entry == nil) { return $0.entry == nil }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    private func extract(_ entry: ArchiveItem, password: String?) {
        selected = nil
        job = Task {
            defer { job = nil }
            do {
                let staging = try OutputFiles.folder()
                defer { OutputFiles.remove(staging) }
                _ = try await DocumentEngine.output(EngineRequest(
                    operation: "extract", path: file.url.path, output: staging.path, password: password, entries: [entry.path]
                ))
                let relative = entry.path.replacingOccurrences(of: "\\", with: "/")
                let output = staging.appendingPathComponent(relative).standardizedFileURL
                guard output.path.hasPrefix(staging.standardizedFileURL.path + "/") else {
                    throw EngineError(message: "The ZIP entry path is invalid.")
                }
                openFile = try await library.importOutput(output)
            } catch is CancellationError {
                return
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}
