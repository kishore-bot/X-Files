import SwiftUI
import UIKit

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
    @State private var picking = false
    @State private var picked = Set<String>()
    @State private var exportFolder: URL?
    @State private var exportPresented = false
    @State private var passwordForExport = false
    @State private var pendingExport: [ArchiveItem] = []

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
                            if picking {
                                if picked.contains(row.path) { picked.remove(row.path) } else { picked.insert(row.path) }
                            } else if let entry = row.entry {
                                selected = entry
                                if !entry.protected { extract(entry, password: nil) }
                            } else {
                                directory = row.path
                            }
                        } label: {
                            HStack {
                                Label(row.name, systemImage: row.entry == nil ? "folder" : row.entry?.protected == true ? "lock.doc" : "doc")
                                Spacer()
                                if picking && picked.contains(row.path) { Image(systemName: "checkmark.circle.fill") }
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
                    if entries.isEmpty { ContentUnavailableView("Empty archive", systemImage: "archivebox") }
                }
            } else if let problem {
                ContentUnavailableView("Archive unavailable", systemImage: "archivebox", description: Text(problem))
            } else {
                ProgressView("Reading archive")
            }
        }
        .toolbar {
            if entries?.isEmpty == false {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(picking ? "Stop selecting" : "Select entries") {
                            picking.toggle()
                            picked.removeAll()
                        }
                        if picking {
                            Button("Select all") { picked = Set(rows(entries ?? []).map(\.path)) }
                            Button("Extract selected") { requestExport(selectedEntries) }.disabled(picked.isEmpty)
                        }
                        Button("Extract all") { requestExport(entries ?? []) }
                    } label: { Label("Archive actions", systemImage: "ellipsis.circle") }
                    .disabled(job != nil)
                }
            }
        }
        .sheet(isPresented: $exportPresented, onDismiss: {
            if let exportFolder { OutputFiles.remove(exportFolder.deletingLastPathComponent()) }
            exportFolder = nil
        }) {
            if let exportFolder {
                ArchiveExportPicker(folder: exportFolder) { exportPresented = false }
            }
        }
        .navigationDestination(item: $openFile) { id in
            ReaderScreen(id: id, library: library)
        }
        .safeAreaInset(edge: .bottom) {
            if job != nil {
                HStack {
                    ProgressView("Extracting archive")
                    Spacer()
                    Button("Cancel") { job?.cancel() }
                }
                .padding().background(.regularMaterial)
            }
        }
        .alert("Unlock ZIP", isPresented: Binding(
            get: { (passwordForExport || selected?.protected == true) && job == nil && problem == nil },
            set: { if !$0 && job == nil { clearPassword() } }
        )) {
            SecureField("ZIP password", text: $password)
            Button("Cancel", role: .cancel) { clearPassword() }
            Button("Extract") {
                if passwordForExport {
                    export(pendingExport, password: password)
                    passwordForExport = false
                    pendingExport = []
                } else if let selected { extract(selected, password: password) }
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

    private func clearPassword() {
        selected = nil
        passwordForExport = false
        pendingExport = []
        password = ""
    }

    private var selectedEntries: [ArchiveItem] {
        (entries ?? []).filter { entry in
            let path = entry.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return picked.contains { path == $0 || path.hasPrefix($0 + "/") }
        }
    }

    private func requestExport(_ items: [ArchiveItem]) {
        guard !items.isEmpty else { return }
        if items.contains(where: \.protected) {
            pendingExport = items
            passwordForExport = true
        } else {
            export(items, password: nil)
        }
    }

    private func export(_ items: [ArchiveItem], password: String?) {
        job = Task {
            defer { job = nil }
            do {
                let staging = try OutputFiles.folder()
                var published = false
                defer { if !published { OutputFiles.remove(staging) } }
                let folder = staging.appendingPathComponent("Extracted files", isDirectory: true)
                _ = try await DocumentEngine.output(EngineRequest(
                    operation: "extract", path: file.url.path, output: folder.path,
                    password: password, entries: items.map(\.path)
                ))
                try Task.checkCancellation()
                exportFolder = folder
                exportPresented = true
                published = true
            } catch is CancellationError {
                return
            } catch { problem = error.localizedDescription }
        }
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
                    throw EngineError(message: "The archive entry path is invalid.")
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


private struct ArchiveExportPicker: UIViewControllerRepresentable {
    let folder: URL
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forExporting: [folder], asCopy: true)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onFinish() }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onFinish() }
    }
}
