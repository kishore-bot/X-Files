import PDFKit
import SwiftUI
import UIKit

struct PDFPageJump: Equatable {
    let id = UUID()
    let page: Int
}

struct PDFReader: View {
    let url: URL
    let page: Int
    let jump: PDFPageJump?
    let control: ReaderControl
    let onPage: (Int) -> Void
    @State private var document: PDFDocument?
    @State private var failed = false
    @State private var locked = false
    @State private var password = ""
    @State private var unlockFailed = false
    @State private var outlineOpen = false
    @State private var outlineJump: PDFPageJump?
    @State private var outline: [OutlineEntry] = []

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
                    PDFSurface(document: document, page: page, jump: jump, outlineJump: outlineJump,
                               control: control, hasOutline: !outline.isEmpty, onOutline: { outlineOpen = true }, onPage: onPage)
                }
            } else if failed {
                ContentUnavailableView("PDF couldn’t be opened", systemImage: "doc.text")
            } else {
                ProgressView()
            }
        }
        .sheet(isPresented: $outlineOpen) {
            NavigationStack {
                List(outline) { entry in
                    Button(entry.title) {
                        outlineJump = PDFPageJump(page: entry.page)
                        outlineOpen = false
                    }
                    .padding(.leading, CGFloat(entry.depth * 12))
                }
                .navigationTitle("Contents")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { outlineOpen = false } } }
            }
        }
        .task(id: url) {
            let opened = PDFDocument(url: url)
            document = opened
            locked = opened?.isLocked == true
            failed = opened == nil
            if let opened, !locked { outline = readOutline(opened) }
        }
    }

    private func unlock(_ document: PDFDocument) {
        let unlocked = document.unlock(withPassword: password)
        password = ""
        locked = !unlocked
        unlockFailed = !unlocked
        if unlocked { outline = readOutline(document) }
    }

    private struct OutlineEntry: Identifiable {
        let id: Int
        let title: String
        let page: Int
        let depth: Int
    }

    private func readOutline(_ document: PDFDocument) -> [OutlineEntry] {
        guard let root = document.outlineRoot else { return [] }
        var entries: [OutlineEntry] = []
        var visited = 0
        func collect(_ node: PDFOutline, depth: Int) {
            guard depth <= 20, visited < 2_000 else { return }
            visited += 1
            if let page = node.destination?.page, let label = node.label {
                let index = document.index(for: page)
                if index >= 0 && index < document.pageCount {
                    entries.append(OutlineEntry(id: entries.count, title: label, page: index, depth: max(0, depth - 1)))
                }
            }
            for index in 0..<min(node.numberOfChildren, 2_000) {
                if visited >= 2_000 { break }
                if let child = node.child(at: index) { collect(child, depth: depth + 1) }
            }
        }
        collect(root, depth: 0)
        return entries
    }
}

private struct PDFSurface: UIViewRepresentable {
    let document: PDFDocument
    let page: Int
    let jump: PDFPageJump?
    let outlineJump: PDFPageJump?
    let control: ReaderControl
    let hasOutline: Bool
    let onOutline: () -> Void
    let onPage: (Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(control: control, onPage: onPage) }

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
        let coordinator = context.coordinator
        coordinator.onPage = onPage
        if let outlineJump, outlineJump.id != coordinator.lastOutlineJump {
            coordinator.lastOutlineJump = outlineJump.id
            uiView.pendingPage = outlineJump.page
            uiView.setNeedsLayout()
        }
        if let jump, jump.id != coordinator.lastJump {
            coordinator.lastJump = jump.id
            uiView.pendingPage = jump.page
            uiView.setNeedsLayout()
        }
        coordinator.install(for: uiView, outline: hasOutline ? onOutline : nil)
    }

    static func dismantleUIView(_ uiView: PositionedView, coordinator: Coordinator) {
        NotificationCenter.default.removeObserver(coordinator, name: .PDFViewPageChanged, object: uiView)
        coordinator.control.remove(owner: coordinator.owner)
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
        var lastJump: UUID?
        var lastOutlineJump: UUID?
        let control: ReaderControl
        let owner = UUID()
        init(control: ReaderControl, onPage: @escaping (Int) -> Void) {
            self.control = control
            self.onPage = onPage
        }

        func install(for view: PDFView, outline: (() -> Void)?) {
            var actions: Set<ReaderCommand.Capability> = [.pages, .scroll, .zoom]
            if outline != nil { actions.insert(.contents) }
            control.install(owner: owner, supports: actions) { [weak view] command in
                guard let view, let doc = view.document else { return .unavailable("This PDF is closed.") }
                if case .contents = command, let outline { outline(); return .done }
                if case .zoom(let bigger) = command {
                    let scale = view.scaleFactor * (bigger ? 1.25 : 0.8)
                    view.scaleFactor = min(max(scale, view.minScaleFactor), view.maxScaleFactor)
                    return .done
                }
                if case .scroll(let direction) = command, let scroll = Self.scrollView(in: view) {
                    let minimum = -scroll.adjustedContentInset.top
                    let maximum = max(minimum, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
                    let point = CGPoint(x: scroll.contentOffset.x,
                                       y: min(maximum, max(minimum, scroll.contentOffset.y + scroll.bounds.height * 0.8 * CGFloat(direction))))
                    scroll.setContentOffset(point, animated: false)
                    return .done
                }
                let current = view.currentPage.map { doc.index(for: $0) } ?? 0
                let page: Int
                switch command {
                case .page(let number): page = number
                case .pageStep(let delta), .scroll(let delta): page = current + delta
                case .top, .firstPage: page = 0
                case .bottom, .lastPage: page = doc.pageCount - 1
                default: return .unavailable("This PDF doesn’t support that action.")
                }
                guard (0..<doc.pageCount).contains(page), let destination = doc.page(at: page) else {
                    return .unavailable("This PDF has \(doc.pageCount) pages.")
                }
                view.go(to: destination)
                return .done
            }
        }

        private static func scrollView(in view: UIView) -> UIScrollView? {
            if let scroll = view as? UIScrollView { return scroll }
            for child in view.subviews {
                if let scroll = scrollView(in: child) { return scroll }
            }
            return nil
        }

        @objc func pageChanged(_ notification: Notification) {
            guard let view = notification.object as? PositionedView, view.pendingPage == nil,
                  let page = view.currentPage, let document = view.document else { return }
            let index = document.index(for: page)
            if index >= 0 && index < document.pageCount { onPage(index) }
        }
    }
}
