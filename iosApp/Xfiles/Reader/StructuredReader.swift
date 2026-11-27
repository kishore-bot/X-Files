import SwiftUI
import UIKit

struct StructuredReader: View {
    let file: ReaderFile
    let onPosition: (Int, Int) -> Void

    @State private var document: EngineDocument?
    @State private var preparedPages: [GroupPage] = []
    @State private var failure: EngineError?
    @State private var password = ""
    @State private var activePassword: String?
    @State private var retry = 0
    @State private var section = 0
    @State private var block: Int?
    @State private var restored = false
    @State private var pinRow = true
    @State private var pinColumn = false
    @AppStorage("xfiles.ios.epub.textSize") private var bookTextSize = 17.0
    @AppStorage("xfiles.ios.epub.paper") private var bookPaper = "System"
    @State private var note: BookNote?
    @ScaledMetric(relativeTo: .body) private var bodyScale = 17.0

    private struct BookNote: Identifiable {
        let id = UUID()
        let text: String
    }

    var body: some View {
        Group {
            if let document {
                if preparedPages.isEmpty {
                    QuickLookReader(url: file.url).preview
                } else {
                    content(document, pages: preparedPages)
                }
            } else if let failure {
                if failure.needsPassword {
                    Form {
                        SecureField("Document password", text: $password)
                        Text(failure.message).foregroundStyle(.secondary)
                        Button("Unlock") {
                            activePassword = password
                            password = ""
                            retry += 1
                        }
                    }
                } else {
                    VStack {
                        Text(failure.message).font(.footnote).padding()
                        QuickLookReader(url: file.url).preview
                    }
                }
            } else {
                ProgressView("Reading document")
            }
        }
        .sheet(item: $note) { item in
            NavigationStack {
                ScrollView { Text(item.text).textSelection(.enabled).padding() }
                    .navigationTitle("Footnote")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { note = nil } } }
            }
        }
        .task(id: retry) {
            failure = nil
            do {
                let result: EngineDocument = try await DocumentEngine.run(EngineRequest(operation: "document", path: file.url.path, password: activePassword))
                preparedPages = groups(result, mode: file.record.viewerKind)
                document = result
            } catch is CancellationError {
                return
            } catch {
                failure = (error as? EngineError) ?? EngineError(message: error.localizedDescription)
            }
        }
    }

    private func content(_ document: EngineDocument, pages: [GroupPage]) -> some View {
        let mode = file.record.viewerKind
        let active = min(max(section, 0), pages.count - 1)
        return VStack(spacing: 0) {
            if pages.count > 1 {
                Picker(mode == .sheet ? "Sheet" : "Section", selection: $section) {
                    ForEach(pages.indices, id: \.self) { index in
                        Text(pages[index].title).tag(index)
                    }
                }
                .pickerStyle(.menu)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
            }
            if mode == .book {
                HStack {
                    Text("Chapter \(active + 1) of \(pages.count)").font(.caption)
                    Spacer()
                    Menu {
                        Button("Smaller text", systemImage: "textformat.size.smaller") { bookTextSize = max(13, bookTextSize - 1) }
                        Button("Larger text", systemImage: "textformat.size.larger") { bookTextSize = min(32, bookTextSize + 1) }
                        Picker("Paper", selection: $bookPaper) {
                            Text("System").tag("System")
                            Text("Light").tag("Light")
                            Text("Sepia").tag("Sepia")
                            Text("Dark").tag("Dark")
                        }
                    } label: { Label("Reading settings", systemImage: "textformat.size") }
                }
                .padding(.horizontal)
                .padding(.bottom, 6)
            }
            if document.truncated {
                Text("Preview limited to the first part of this file.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if mode == .slides, let slide = pages[active].items.first(where: { $0.node.kind == "slide" })?.node {
                SlideSurface(slide: slide, items: pages[active].items, file: file, password: activePassword) { link in
                    followSlide(link, count: pages.count)
                }
            } else if mode == .sheet {
                HStack {
                    Toggle("Freeze first row", isOn: $pinRow)
                    Toggle("Freeze first column", isOn: $pinColumn)
                }
                .toggleStyle(.button)
                .font(.caption)
                .padding(.horizontal)
                .padding(.bottom, 6)
                SheetSurface(rows: pages[active].items, columns: pages[active].columns,
                             pinRow: pinRow, pinColumn: pinColumn, block: $block)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(pages[active].items) { item in
                            documentBlock(item.node).id(item.id)
                        }
                    }
                    .scrollTargetLayout()
                    .frame(maxWidth: 740, alignment: .leading)
                    .padding()
                }
                .scrollPosition(id: $block, anchor: .top)
                .environment(\.openURL, OpenURLAction { url in
                    followBookLink(url, book: document.book, pages: pages)
                })
                .font(mode == .book ? .system(size: bookTextSize * bodyScale / 17, design: .serif) : .body)
                .foregroundStyle(mode == .book && bookPaper != "System" ? (bookPaper == "Dark" ? .white : .black) : .primary)
                .background(mode == .book ? pageColor : .clear)
                .textSelection(.enabled)
            }
        }
        .onAppear {
            guard !restored else { return }
            restored = true
            section = min(max(file.record.position.section ?? 0, 0), pages.count - 1)
            block = file.record.position.block
        }
        .onChange(of: section) { _, next in
            guard pages.indices.contains(next) else { return }
            if !pages[next].contains(block) {
                block = pages[next].items.first?.id
            }
            onPosition(block ?? 0, next)
        }
        .onChange(of: block) { _, next in
            if let next, pages[active].contains(next) {
                onPosition(next, active)
            }
        }
    }

    @ViewBuilder
    private func documentBlock(_ node: EngineDocument.Node) -> some View {
        switch node.kind {
        case "heading", "section":
            Text(styled(node)).font((node.level ?? 1) < 2 ? .title2.bold() : .headline)
                .accessibilityAddTraits(.isHeader)
        case "list", "text":
            Text(styled(node))
        case "quote":
            Text(styled(node)).italic().foregroundStyle(.secondary)
        case "code":
            ScrollView(.horizontal) { Text(node.text ?? "").font(.system(.body, design: .monospaced)) }
        case "row":
            ScrollView(.horizontal) {
                HStack(alignment: .top) {
                    ForEach(Array((node.cells ?? []).enumerated()), id: \.offset) { _, cell in
                        Text(cell).padding(6).frame(minWidth: 86, alignment: .leading)
                    }
                }
            }
        case "divider":
            Divider()
        case "image":
            if let resource = node.resource {
                DocumentPicture(resource: resource, label: node.text, file: file, password: activePassword)
            } else if let caption = node.text {
                Label(caption, systemImage: "photo")
            }
        default:
            EmptyView()
        }
    }

    private var pageColor: Color {
        switch bookPaper {
        case "Light": .white
        case "Sepia": Color(red: 0.96, green: 0.91, blue: 0.81)
        case "Dark": Color(white: 0.12)
        default: .clear
        }
    }

    private func styled(_ node: EngineDocument.Node) -> AttributedString {
        let text = node.text ?? ""
        var formatted = AttributedString(text)
        let count = text.utf16.count
        for span in node.spans ?? [] {
            guard span.start >= 0, span.start < span.end, span.end <= count,
                  let range = Range(NSRange(location: span.start, length: span.end - span.start), in: text),
                  let start = AttributedString.Index(range.lowerBound, within: formatted),
                  let end = AttributedString.Index(range.upperBound, within: formatted) else { continue }
            var intent = InlinePresentationIntent()
            if span.style & 1 != 0 { intent.insert(.stronglyEmphasized) }
            if span.style & 2 != 0 { intent.insert(.emphasized) }
            if span.style & 8 != 0 { intent.insert(.strikethrough) }
            if span.style & 16 != 0 { intent.insert(.code) }
            if !intent.isEmpty { formatted[start..<end].inlinePresentationIntent = intent }
            if let link = span.link {
                formatted[start..<end].link = readerLink(link)
            }
        }
        return formatted
    }

    private func readerLink(_ link: String) -> URL? {
        if link.hasPrefix("book:") || link.hasPrefix("note:") {
            var parts = URLComponents()
            parts.scheme = "xfiles-reader"
            parts.host = "book"
            parts.queryItems = [URLQueryItem(name: "target", value: link)]
            return parts.url
        }
        guard let url = URL(string: link), let scheme = url.scheme?.lowercased(),
              ["https", "http", "mailto"].contains(scheme) else { return nil }
        return url
    }

    private func followBookLink(_ url: URL, book: EngineDocument.Book?, pages: [GroupPage]) -> OpenURLAction.Result {
        guard url.scheme == "xfiles-reader" else { return .systemAction }
        guard let book, let target = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "target" })?.value else { return .discarded }
        if target.hasPrefix("note:"), let text = book.notes[String(target.dropFirst(5))] {
            note = BookNote(text: text)
            return .handled
        }
        let href = String(target.dropFirst(5))
        let path = String(href.split(separator: "#", maxSplits: 1).first ?? "")
        guard let position = book.anchors[href] ?? book.chapters.first(where: { $0.href == path })?.block,
              position >= 0 else { return .discarded }
        let selected = pages.lastIndex(where: { ($0.items.first?.id ?? Int.max) <= position }) ?? 0
        section = selected
        block = (pages[selected].items.first(where: { $0.id >= position }) ?? pages[selected].items.first)?.id
        return .handled
    }

    private func groups(_ document: EngineDocument, mode: DocumentKind) -> [GroupPage] {
        var result: [GroupPage] = []
        var current = GroupPage(title: mode == .sheet ? "Sheet 1" : "Beginning")
        var chapterTitles: [Int: String] = [:]
        if let book = document.book {
            for chapter in book.chapters where chapter.block >= 0 {
                let title = book.toc.first { $0.href.components(separatedBy: "#").first == chapter.href }?.title
                chapterTitles[chapter.block] = title ?? URL(fileURLWithPath: chapter.href).deletingPathExtension().lastPathComponent
            }
        }
        for node in document.nodes {
            let id = node.sourceIndex
            let chapter = chapterTitles[id]
            let begins = chapter != nil || ((mode == .sheet || mode == .slides) && node.kind == "section")
                || (mode == .book && chapterTitles.isEmpty && node.kind == "heading" && (node.level ?? 1) == 1)
            if begins {
                if !current.items.isEmpty { result.append(current) }
                current = GroupPage(title: chapter ?? node.text ?? "Section \(result.count + 1)")
            }
            if mode != .sheet || node.kind == "row" {
                current.items.append(LocatedNode(id: id, node: node))
                current.columns = max(current.columns, node.cells?.count ?? 0)
            }
        }
        if !current.items.isEmpty { result.append(current) }
        return result
    }

    private func followSlide(_ target: String, count: Int) {
        let next: Int?
        switch target {
        case "slide:first": next = 0
        case "slide:last": next = count - 1
        case "slide:next": next = section + 1
        case "slide:previous": next = section - 1
        default: next = Int(target.dropFirst("slide:".count)).map { $0 - 1 }
        }
        if let next, (0..<count).contains(next) { section = next }
    }
}

private struct GroupPage {
    let title: String
    var items: [LocatedNode] = []
    var columns = 0

    func contains(_ id: Int?) -> Bool {
        guard let id, let first = items.first?.id, let last = items.last?.id else { return false }
        return id >= first && id <= last
    }
}

struct LocatedNode: Identifiable {
    let id: Int
    let node: EngineDocument.Node
}

private struct SheetSurface: View {
    let rows: [LocatedNode]
    let columns: Int
    let pinRow: Bool
    let pinColumn: Bool
    @Binding var block: Int?
    @State private var horizontalOffset: CGFloat = 0
    private let columnWidth: CGFloat = 146

    var body: some View {
        if rows.isEmpty {
            ContentUnavailableView("Empty sheet", systemImage: "tablecells")
        } else {
            ScrollView(.horizontal) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: pinRow ? [.sectionHeaders] : []) {
                        Section {
                            ForEach(pinRow ? Array(rows.dropFirst()) : rows) { row in
                                rowView(row).id(row.id)
                            }
                        } header: {
                            if pinRow, let first = rows.first { rowView(first, header: true) }
                        }
                    }
                    .scrollTargetLayout()
                    .background(alignment: .topLeading) {
                        GeometryReader { geometry in
                            Color.clear.preference(
                                key: SheetOffset.self,
                                value: max(0, -geometry.frame(in: .named("sheet-horizontal")).minX)
                            )
                        }
                    }
                }
                .scrollPosition(id: $block, anchor: .top)
                .frame(width: CGFloat(max(columns, 1)) * columnWidth)
            }
            .coordinateSpace(name: "sheet-horizontal")
            .onPreferenceChange(SheetOffset.self) { value in
                horizontalOffset = value
            }
        }
    }

    private func rowView(_ row: LocatedNode, header: Bool = false) -> some View {
        HStack(spacing: 0) {
            ForEach(Array((row.node.cells ?? []).enumerated()), id: \.offset) { index, cell in
                let frozen = pinColumn && index == 0
                Text(cell)
                    .lineLimit(2)
                    .padding(.horizontal, 6)
                    .frame(width: columnWidth, height: 40, alignment: .leading)
                    .font(header ? .caption.bold() : .caption)
                    .background(header || frozen ? Color(uiColor: .secondarySystemBackground) : Color(uiColor: .systemBackground))
                    .overlay { Rectangle().strokeBorder(.quaternary, lineWidth: 0.5) }
                    .offset(x: frozen ? horizontalOffset : 0)
                    .zIndex(frozen ? 1 : 0)
            }
        }
    }

    private struct SheetOffset: PreferenceKey {
        static let defaultValue: CGFloat = 0
        static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
    }
}

struct DocumentPicture: View {
    let resource: String
    let label: String?
    let file: ReaderFile
    let password: String?
    @State private var picture: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let picture {
                Image(uiImage: picture).resizable().scaledToFit()
                    .accessibilityLabel(label ?? "Document image")
            } else if failed {
                Label(label ?? "Image unavailable", systemImage: "photo")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .task(id: resource) {
            do {
                let folder = try OutputFiles.folder()
                defer { OutputFiles.remove(folder) }
                let url = OutputFiles.resource(in: folder, name: resource)
                _ = try await DocumentEngine.output(EngineRequest(operation: "resource", path: file.url.path, output: url.path, password: password, resource: resource))
                picture = UIImage(contentsOfFile: url.path)
                failed = picture == nil
            } catch is CancellationError {
                return
            } catch {
                failed = true
            }
        }
    }
}
