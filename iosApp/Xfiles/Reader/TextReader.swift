import SwiftUI
import UIKit

struct TextReader: View {
    let url: URL
    let textOffset: Double
    let onScroll: (Double) -> Void
    @State private var text: String?
    @State private var failed = false

    var body: some View {
        Group {
            if let text {
                TextSurface(text: text, offset: textOffset, onScroll: onScroll)
            } else if failed {
                QuickLookReader(url: url).preview
            } else {
                ProgressView("Reading text")
            }
        }
        .task(id: url) {
            let read = Task.detached(priority: .userInitiated) { [url] in Self.readText(url) }
            let loaded = await read.value
            guard !Task.isCancelled else { return }
            text = loaded
            failed = loaded == nil
        }
    }

    private nonisolated static func readText(_ url: URL) -> String? {
        let limit = 1_048_576
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        do {
            let data = try handle.read(upToCount: limit + 1) ?? Data()
            guard data.count <= limit else { return nil }
            if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]) {
                return String(data: data, encoding: .utf16)
            }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }
}

private struct TextSurface: UIViewRepresentable {
    let text: String
    let offset: Double
    let onScroll: (Double) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScroll: onScroll) }

    func makeUIView(context: Context) -> PositionedView {
        let view = PositionedView()
        view.isEditable = false
        view.isSelectable = true
        view.adjustsFontForContentSizeCategory = true
        view.font = .preferredFont(forTextStyle: .body)
        view.textColor = .label
        view.backgroundColor = .systemBackground
        view.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        view.text = text
        view.pendingOffset = offset.isFinite ? max(offset, 0) : 0
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ uiView: PositionedView, context: Context) {
        context.coordinator.onScroll = onScroll
    }

    final class PositionedView: UITextView {
        var pendingOffset: Double?

        override func layoutSubviews() {
            super.layoutSubviews()
            guard let offset = pendingOffset, !bounds.isEmpty, contentSize.height > 0 else { return }
            pendingOffset = nil
            let maximum = max(0, contentSize.height - bounds.height + adjustedContentInset.bottom)
            setContentOffset(CGPoint(x: 0, y: min(CGFloat(offset), maximum)), animated: false)
        }
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var onScroll: (Double) -> Void
        init(onScroll: @escaping (Double) -> Void) { self.onScroll = onScroll }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard let view = scrollView as? PositionedView, view.pendingOffset == nil else { return }
            onScroll(max(0, Double(view.contentOffset.y)))
        }
    }
}
