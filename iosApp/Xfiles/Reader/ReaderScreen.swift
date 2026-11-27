import SwiftUI

struct ReaderScreen: View {
    let id: UUID
    let library: LibraryModel
    var startPage: Int? = nil
    @Environment(\.scenePhase) private var scenePhase
    @State private var file: ReaderFile?
    @State private var progress: ReaderProgress?
    @State private var failure: String?
    @State private var panel: Panel?
    @State private var next: ReadDestination?
    @State private var pageJump: PDFPageJump?
    @State private var currentPDFPage = 0

    private enum Panel: String, Identifiable {
        case tools, fily, signatures, editText, markup
        var id: Self { self }
    }

    var body: some View {
        Group {
            if let file, let progress {
                switch file.record.viewerKind {
                case .pdf:
                    PDFReader(
                        url: file.url, page: startPage ?? file.record.position.page,
                        jump: pageJump, onPage: { page in
                            currentPDFPage = page
                            progress.update(page: page)
                        }
                    )
                case .media:
                    MediaReader(
                        url: file.url,
                        seconds: file.record.position.seconds,
                        onTime: progress.update(seconds:),
                        onCheckpoint: progress.flush
                    )
                case .text:
                    TextReader(url: file.url, textOffset: file.record.position.textOffset, onScroll: progress.update(textOffset:))
                case .book, .office, .sheet, .slides:
                    StructuredReader(file: file, onPosition: progress.update(block:section:))
                case .archive:
                    ArchiveReader(file: file, library: library)
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
                ToolbarItemGroup(placement: .topBarTrailing) {
                    ShareLink(item: file.url) { Label("Share file", systemImage: "square.and.arrow.up") }
                    Menu {
                        Button("Ask Fily", systemImage: "sparkles") { panel = .fily }
                        Button("Document tools", systemImage: "wrench.and.screwdriver") { panel = .tools }
                        if file.record.viewerKind == .pdf {
                            Button("Annotate page", systemImage: "pencil.tip") { panel = .markup }
                            Button("Digital signatures", systemImage: "signature") { panel = .signatures }
                        }
                        if file.record.viewerKind == .text {
                            Button("Edit text copy", systemImage: "square.and.pencil") { panel = .editText }
                        }
                    } label: { Label("Reader actions", systemImage: "ellipsis.circle") }
                }
            }
        }
        .navigationDestination(item: $next) { destination in
            ReaderScreen(id: destination.id, library: library, startPage: destination.page)
        }
        .sheet(item: $panel) { choice in
            if let file {
                switch choice {
                case .tools: DocumentTools(library: library, initial: file) { next = $0 }
                case .fily:
                    FilyScreen(library: library, selected: file, openFile: { next = $0 }, goToPage: { pageJump = PDFPageJump(page: $0) })
                case .signatures: PDFSignatures(url: file.url)
                case .markup: PDFMarkupEditor(file: file, page: currentPDFPage, library: library) { next = $0 }
                case .editText: TextCopyEditor(file: file, library: library) { next = $0 }
                }
            }
        }
        .task(id: id) {
            do {
                let opened = try await library.open(id)
                if Task.isCancelled { return }
                progress = ReaderProgress(record: opened.record, store: library.store)
                currentPDFPage = startPage ?? opened.record.position.page
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
