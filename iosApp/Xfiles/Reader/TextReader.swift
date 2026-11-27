import SwiftUI
import UIKit

struct TextReader: View {
    let url: URL
    let textOffset: Double
    let onScroll: (Double) -> Void
    let control: ReaderControl
    @State private var text: String?
    @State private var failed = false
    @State private var source = true
    @State private var formatted: String?
    @State private var latestOffset = 0.0

    var body: some View {
        Group {
            if let text {
                VStack(spacing: 0) {
                    if formatted != nil {
                        Picker("Text display", selection: $source) {
                            Text("Source").tag(true)
                            Text("Formatted").tag(false)
                        }.pickerStyle(.segmented).padding(.horizontal)
                    }
                    TextSurface(text: source ? text : formatted ?? text, offset: latestOffset,
                                control: control, formatted: formatted != nil, onSource: { source = $0 },
                                onScroll: { offset in latestOffset = offset; onScroll(offset) })
                }
            } else if failed {
                QuickLookReader(url: url).preview
            } else {
                ProgressView("Reading text")
            }
        }
        .task(id: url) {
            let read = Task.detached(priority: .userInitiated) { [url] in
                let text = Self.readText(url)
                var formatted: String?
                if url.pathExtension.lowercased() == "json", let text,
                   let object = try? JSONSerialization.jsonObject(with: Data(text.utf8), options: .fragmentsAllowed),
                   let encoded = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed]) {
                    formatted = String(decoding: encoded, as: UTF8.self)
                }
                return (text, formatted)
            }
            let loaded = await read.value
            guard !Task.isCancelled else { return }
            latestOffset = textOffset
            text = loaded.0
            formatted = loaded.1
            failed = text == nil
        }
    }

    nonisolated static func readText(_ url: URL) -> String? {
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
    let control: ReaderControl
    let formatted: Bool
    let onSource: (Bool) -> Void
    let onScroll: (Double) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(control: control, onScroll: onScroll) }

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
        let coordinator = context.coordinator
        coordinator.onScroll = onScroll
        if uiView.text != text {
            uiView.pendingOffset = offset
            uiView.text = text
            uiView.setNeedsLayout()
        }
        var capabilities: Set<ReaderCommand.Capability> = [.scroll, .zoom]
        if formatted { capabilities.insert(.source) }
        control.install(owner: coordinator.owner, supports: capabilities) { [weak uiView] command in
            guard let view = uiView else { return .unavailable("This file is closed.") }
            let limit = max(0, view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
            switch command {
            case .source(let show): onSource(show)
            case .zoom(let bigger):
                let base = view.font ?? .preferredFont(forTextStyle: .body)
                view.font = base.withSize(min(44, max(12, base.pointSize + (bigger ? 2 : -2))))
            case .top: view.setContentOffset(.zero, animated: false)
            case .bottom: view.setContentOffset(CGPoint(x: 0, y: limit), animated: false)
            case .scroll(let direction):
                let y = min(limit, max(0, view.contentOffset.y + view.bounds.height * 0.8 * CGFloat(direction)))
                view.setContentOffset(CGPoint(x: 0, y: y), animated: false)
            default: return .unavailable("This text view doesn’t support that action.")
            }
            return .done
        }
    }

    static func dismantleUIView(_ view: PositionedView, coordinator: Coordinator) {
        view.delegate = nil
        coordinator.control.remove(owner: coordinator.owner)
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
        let control: ReaderControl
        let owner = UUID()
        init(control: ReaderControl, onScroll: @escaping (Double) -> Void) {
            self.control = control
            self.onScroll = onScroll
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard let view = scrollView as? PositionedView, view.pendingOffset == nil else { return }
            onScroll(max(0, Double(view.contentOffset.y)))
        }
    }
}
