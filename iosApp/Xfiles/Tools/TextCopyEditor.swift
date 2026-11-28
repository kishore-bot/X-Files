import SwiftUI

struct TextCopyEditor: View {
    let file: ReaderFile
    let library: LibraryModel
    let onSaved: (ReadDestination) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var ready = false
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Group {
                if ready {
                    TextEditor(text: $draft).padding(.horizontal)
                } else if let error {
                    ContentUnavailableView("Text cannot be edited", systemImage: "doc.text", description: Text(error))
                } else {
                    ProgressView("Loading text")
                }
            }
            .navigationTitle("Edit copy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                if ready {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { save() }.disabled(saving)
                    }
                }
            }
            .interactiveDismissDisabled(saving)
            .task(id: file.record.id) {
                let url = file.url
                let worker = Task.detached(priority: .userInitiated) { TextReader.readText(url) }
                let value = await worker.value
                guard !Task.isCancelled else { return }
                if let value { draft = value; ready = true }
                else { error = "Only UTF‑8 and UTF‑16 text up to 1 MiB can be edited." }
            }
            .alert("Couldn’t save the copy", isPresented: Binding(
                get: { ready && error != nil }, set: { if !$0 { error = nil } }
            )) {
                Button("OK", role: .cancel) { error = nil }
            } message: { Text(error ?? "") }
        }
    }

    private func save() {
        let contents = draft
        saving = true
        Task {
            defer { saving = false }
            do {
                let directory = try OutputFiles.folder()
                defer { OutputFiles.remove(directory) }
                let target = OutputFiles.copyURL(in: directory, file: file, suffix: "edited")
                try contents.write(to: target, atomically: true, encoding: .utf8)
                let id = try await library.importOutput(target)
                onSaved(ReadDestination(id: id))
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
