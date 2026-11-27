import SwiftUI

struct ReaderScreen: View {
    let id: UUID
    let library: LibraryModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var file: ReaderFile?
    @State private var progress: ReaderProgress?
    @State private var failure: String?

    var body: some View {
        Group {
            if let file, let progress {
                switch file.record.kind {
                case .pdf:
                    PDFReader(url: file.url, page: file.record.position.page, onPage: progress.update(page:))
                case .media:
                    MediaReader(
                        url: file.url,
                        seconds: file.record.position.seconds,
                        onTime: progress.update(seconds:),
                        onCheckpoint: progress.flush
                    )
                case .text:
                    TextReader(url: file.url, textOffset: file.record.position.textOffset, onScroll: progress.update(textOffset:))
                case .image, .other:
                    QuickLookReader(url: file.url).preview
                }
            } else if let failure {
                ContentUnavailableView("File unavailable", systemImage: "doc.badge.ellipsis", description: Text(failure))
            } else {
                ProgressView("Opening file")
            }
        }
        .navigationTitle(file?.record.name ?? "Reader")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let file {
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: file.url) { Label("Share file", systemImage: "square.and.arrow.up") }
                }
            }
        }
        .task(id: id) {
            do {
                let opened = try await library.open(id)
                if Task.isCancelled { return }
                progress = ReaderProgress(record: opened.record, store: library.store)
                file = opened
            } catch {
                failure = error.localizedDescription
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { progress?.flush() }
        }
        .onDisappear { progress?.flush() }
    }
}
