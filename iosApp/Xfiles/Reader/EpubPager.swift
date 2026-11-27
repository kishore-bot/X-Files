import CoreText
import SwiftUI

struct BookFragment: Identifiable, Sendable {
    let item: LocatedNode
    let id: BookAnchor
    var length: Int?
}

struct BookLeaf: Sendable {
    var fragments: [BookFragment] = []
    var start: BookAnchor { fragments.first?.id ?? BookAnchor(block: 0) }
}

struct EpubPageMetrics: Hashable, Sendable {
    let width: Int
    let height: Int
    let fontSize: Int
    let chapter: Int
}

enum EpubPaginator {
    static func paginate(_ items: [LocatedNode], using metrics: EpubPageMetrics) throws -> [BookLeaf] {
        let width = CGFloat(max(80, metrics.width))
        let height = CGFloat(max(100, metrics.height))
        let gap: CGFloat = 12
        let size = CGFloat(max(13, metrics.fontSize))
        let bodyFont = CTFontCreateWithName("TimesNewRomanPSMT" as CFString, size, nil)
        let headingFont = CTFontCreateWithName("Helvetica-Bold" as CFString, size + 5, nil)
        var leaves: [BookLeaf] = []
        var leaf = BookLeaf()
        var remaining = height

        func turn() {
            guard !leaf.fragments.isEmpty else { return }
            leaves.append(leaf)
            leaf = BookLeaf()
            remaining = height
        }

        func place(_ fragment: BookFragment, height needed: CGFloat) {
            if !leaf.fragments.isEmpty { remaining -= gap }
            leaf.fragments.append(fragment)
            remaining -= needed
        }

        for item in items {
            try Task.checkCancellation()
            let node = item.node
            let anchor = BookAnchor(block: item.id)
            let needed: CGFloat
            if node.kind == "image" { needed = min(240, height) }
            else if node.kind == "row" || node.kind == "code" { needed = min(150, height) }
            else if node.kind == "divider" { needed = 10 }
            else { needed = 0 }
            if needed > 0 {
                if remaining - gap < needed { turn() }
                place(BookFragment(item: item, id: anchor), height: needed)
                continue
            }

            guard let text = node.text, !text.isEmpty else { continue }
            let font = node.kind == "heading" ? headingFont : bodyFont
            let attributed = NSAttributedString(string: text, attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font
            ])
            let setter = CTFramesetterCreateWithAttributedString(attributed as CFAttributedString)
            let characters = text as NSString
            var offset = 0
            while offset < attributed.length {
                try Task.checkCancellation()
                let space = remaining - (leaf.fragments.isEmpty ? 0 : gap)
                if space < size * 2 && !leaf.fragments.isEmpty {
                    turn()
                    continue
                }
                let path = CGPath(rect: CGRect(x: 0, y: 0, width: width, height: max(1, space)), transform: nil)
                let frame = CTFramesetterCreateFrame(setter, CFRange(location: offset, length: 0), path, nil)
                var length = CTFrameGetVisibleStringRange(frame).length
                if length == 0 && !leaf.fragments.isEmpty {
                    turn()
                    continue
                }
                if length == 0 { length = characters.rangeOfComposedCharacterSequence(at: offset).length }
                let end = offset + length
                if end < characters.length {
                    let composed = characters.rangeOfComposedCharacterSequence(at: end)
                    if composed.location < end, composed.location > offset { length = composed.location - offset }
                }
                let measured = CTFramesetterSuggestFrameSizeWithConstraints(
                    setter, CFRange(location: offset, length: length), nil,
                    CGSize(width: width, height: .greatestFiniteMagnitude), nil
                ).height
                place(BookFragment(item: item, id: BookAnchor(block: item.id, offset: offset), length: length),
                      height: ceil(measured) + 4)
                offset += length
                if offset < attributed.length { turn() }
            }
        }
        turn()
        return leaves
    }
}

struct EpubPager<Content: View>: View {
    let items: [LocatedNode]
    let chapter: Int
    let textSize: Double
    @Binding var anchor: BookAnchor?
    @Binding var leafIndex: Int
    @Binding var leafCount: Int
    let onPreviousChapter: () -> Void
    let onNextChapter: () -> Void
    let hasPreviousChapter: Bool
    let hasNextChapter: Bool
    @ViewBuilder let content: (BookFragment) -> Content
    @Environment(\.accessibilityReduceMotion) private var reducedMotion
    @State private var leaves: [BookLeaf] = []
    @State private var laidOutChapter: Int?

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                let metrics = EpubPageMetrics(
                    width: max(1, Int(min(740, geometry.size.width) - 44)),
                    height: max(1, Int(geometry.size.height - 38)),
                    fontSize: Int(textSize.rounded()), chapter: chapter
                )
                Group {
                    if laidOutChapter != chapter {
                        ProgressView("Laying out pages").frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if leaves.isEmpty {
                        ContentUnavailableView("Empty chapter", systemImage: "book")
                    } else {
                        TabView(selection: $leafIndex) {
                            if hasPreviousChapter { Color.clear.tag(-1).accessibilityHidden(true) }
                            ForEach(leaves.indices, id: \.self) { index in
                                ScrollView {
                                    VStack(alignment: .leading, spacing: 12) {
                                        ForEach(leaves[index].fragments) { fragment in
                                            content(fragment)
                                        }
                                    }
                                    .frame(maxWidth: 696, alignment: .leading)
                                    .padding(.horizontal, 22)
                                    .padding(.vertical, 18)
                                }.tag(index)
                            }
                            if hasNextChapter { Color.clear.tag(leaves.count).accessibilityHidden(true) }
                        }
                        .tabViewStyle(.page(indexDisplayMode: .never))
                    }
                }
                .task(id: metrics) {
                    let worker = Task.detached(priority: .userInitiated) { [items, metrics] in
                        try EpubPaginator.paginate(items, using: metrics)
                    }
                    do {
                        let result = try await withTaskCancellationHandler {
                            let pages = try await worker.value
                            try Task.checkCancellation()
                            return pages
                        } onCancel: { worker.cancel() }
                        let index = locate(anchor, in: result)
                        leaves = result
                        laidOutChapter = chapter
                        leafCount = result.count
                        leafIndex = index
                        if anchor == nil { anchor = result.first?.start }
                    } catch {
                        return
                    }
                }
            }
            HStack {
                Button("Previous page", systemImage: "chevron.left") {
                    if leafIndex > 0 { move(-1) } else { onPreviousChapter() }
                }
                .disabled(leafIndex == 0 && !hasPreviousChapter)
                Spacer()
                if laidOutChapter == chapter && !leaves.isEmpty {
                    Text("Page \(leafIndex + 1) of \(leaves.count)").font(.caption.monospacedDigit())
                }
                Spacer()
                Button("Next page", systemImage: "chevron.right") {
                    if leafIndex + 1 < leaves.count { move(1) } else { onNextChapter() }
                }
                .disabled(leafIndex + 1 >= leaves.count && !hasNextChapter)
            }
            .labelStyle(.iconOnly)
            .padding(.horizontal, 18)
            .padding(.vertical, 8)
        }
        .onChange(of: anchor) { _, target in
            guard laidOutChapter == chapter, let target else { return }
            let index = locate(target, in: leaves)
            if leafIndex != index { leafIndex = index }
        }
        .onChange(of: leafIndex) { _, index in
            guard laidOutChapter == chapter else { return }
            if leaves.indices.contains(index) {
                if locate(anchor, in: leaves) != index { anchor = leaves[index].start }
            } else if index == -1 && hasPreviousChapter {
                onPreviousChapter()
            } else if index == leaves.count && hasNextChapter {
                onNextChapter()
            }
        }
    }

    private func locate(_ anchor: BookAnchor?, in pages: [BookLeaf]) -> Int {
        guard let anchor else { return 0 }
        return pages.lastIndex { $0.start <= anchor } ?? 0
    }

    private func move(_ amount: Int) {
        withAnimation(reducedMotion ? nil : .smooth(duration: 0.22)) { leafIndex += amount }
    }
}
