import PDFKit
import SwiftUI
import UIKit

struct PDFReader: View {
    let url: URL
    let page: Int
    let onPage: (Int) -> Void
    @State private var document: PDFDocument?
    @State private var failed = false
    @State private var locked = false
    @State private var password = ""
    @State private var unlockFailed = false

    var body: some View {
        Group {
            if let document {
                if locked {
                    Form {
                        SecureField("PDF password", text: $password)
                            .onSubmit { unlock(document) }
                        Button("Unlock") { unlock(document) }
                        if unlockFailed { Text("That password didn’t unlock the PDF.").foregroundStyle(.secondary) }
                    }
                } else {
                    PDFSurface(document: document, page: page, onPage: onPage)
                }
            } else if failed {
                ContentUnavailableView("PDF couldn’t be opened", systemImage: "doc.text")
            } else {
                ProgressView()
            }
        }
        .task(id: url) {
            let opened = PDFDocument(url: url)
            document = opened
            locked = opened?.isLocked == true
            failed = opened == nil
        }
    }

    private func unlock(_ document: PDFDocument) {
        let unlocked = document.unlock(withPassword: password)
        password = ""
        locked = !unlocked
        unlockFailed = !unlocked
    }
}

private struct PDFSurface: UIViewRepresentable {
    let document: PDFDocument
    let page: Int
    let onPage: (Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPage: onPage) }

    func makeUIView(context: Context) -> PositionedView {
        let view = PositionedView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.pendingPage = page
        view.document = document
        NotificationCenter.default.addObserver(
            context.coordinator, selector: #selector(Coordinator.pageChanged(_:)), name: .PDFViewPageChanged, object: view
        )
        return view
    }

    func updateUIView(_ uiView: PositionedView, context: Context) {
        context.coordinator.onPage = onPage
    }

    static func dismantleUIView(_ uiView: PositionedView, coordinator: Coordinator) {
        NotificationCenter.default.removeObserver(coordinator, name: .PDFViewPageChanged, object: uiView)
    }

    final class PositionedView: PDFView {
        var pendingPage: Int?

        override func layoutSubviews() {
            super.layoutSubviews()
            guard !bounds.isEmpty, let page = pendingPage, let document, document.pageCount > 0 else { return }
            pendingPage = nil
            if let destination = document.page(at: min(max(page, 0), document.pageCount - 1)) {
                go(to: destination)
            }
        }
    }

    @MainActor
    final class Coordinator: NSObject {
        var onPage: (Int) -> Void
        init(onPage: @escaping (Int) -> Void) { self.onPage = onPage }

        @objc func pageChanged(_ notification: Notification) {
            guard let view = notification.object as? PositionedView, view.pendingPage == nil,
                  let page = view.currentPage, let document = view.document else { return }
            let index = document.index(for: page)
            if index >= 0 && index < document.pageCount { onPage(index) }
        }
    }
}
