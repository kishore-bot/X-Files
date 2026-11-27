import SwiftUI
import UIKit

struct StructuredReader: View {
    let file: ReaderFile
    let store: DocumentStore
    let control: ReaderControl
    let onPosition: (Int, Int, Int?) -> Void

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
    @AppStorage("xfiles.ios.epub.paged") private var paged = true
    @Environment(\.accessibilityReduceMotion) private var reducedMotion
    @State private var bookAnchor: BookAnchor?
    @State private var leafIndex = 0
    @State private var leafCount = 0
    @State private var notebook: [NotebookEntry] = []
    @State private var notebookOpen = false
    @State private var contentsOpen = false
    @State private var noteDraft: NotebookEntry?
    @State private var draftText = ""
    @State private var notebookError: String?
    @State private var owner = UUID()
    @State private var selectedCell: SheetCell?
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
        .sheet(isPresented: $notebookOpen) {
            EpubNotebook(entries: notebook, onOpen: { go($0, pages: preparedPages) }, onRemove: {
                updateNotebook(.remove($0))
            })
        }
        .sheet(isPresented: $contentsOpen) {
            NavigationStack {
                List(preparedPages.indices, id: \.self) { index in
                    Button(preparedPages[index].title) {
                        selectSection(index, pages: preparedPages)
                        contentsOpen = false
                    }
                }
                .navigationTitle("Contents")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { contentsOpen = false } } }
            }
        }
        .alert("Reading note", isPresented: Binding(
            get: { noteDraft != nil }, set: { if !$0 { noteDraft = nil } }
        )) {
            TextField("Note", text: $draftText)
            Button("Cancel", role: .cancel) { noteDraft = nil }
            Button("Save") {
                if var entry = noteDraft {
                    let text = draftText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !text.isEmpty {
                        entry.note = String(text.prefix(4_000))
                        updateNotebook(.note(entry))
                    }
                }
                noteDraft = nil
            }
        }
        .alert("Notebook unavailable", isPresented: Binding(
            get: { notebookError != nil }, set: { if !$0 { notebookError = nil } }
        )) {
            Button("OK") { notebookError = nil }
        } message: { Text(notebookError ?? "") }
        .task(id: retry) {
            failure = nil
            do {
                let result: EngineDocument = try await DocumentEngine.run(EngineRequest(operation: "document", path: file.url.path, password: activePassword))
                preparedPages = groups(result, mode: file.record.viewerKind)
                if file.record.viewerKind == .book {
                    do { notebook = try await store.notebook(file.record.id) }
                    catch { notebookError = error.localizedDescription }
                }
                document = result
            } catch is CancellationError {
                return
            } catch {
                failure = (error as? EngineError) ?? EngineError(message: error.localizedDescription)
            }
        }
        .onDisappear { control.remove(owner: owner) }
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
                    Button("Notebook", systemImage: "book.closed") { notebookOpen = true }.labelStyle(.iconOnly)
                    Menu {
                        Picker("Reading mode", selection: $paged) {
                            Text("Paged").tag(true)
                            Text("Continuous").tag(false)
                        }
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
                             pinRow: pinRow, pinColumn: pinColumn, block: $block, target: selectedCell)
            } else if mode == .book && paged {
                EpubPager(
                    items: pages[active].items, chapter: active, textSize: bookTextSize * bodyScale / 17,
                    anchor: $bookAnchor, leafIndex: $leafIndex, leafCount: $leafCount,
                    onPreviousChapter: { selectSection(active - 1, pages: pages, atEnd: true) },
                    onNextChapter: { selectSection(active + 1, pages: pages) },
                    hasPreviousChapter: active > 0, hasNextChapter: active + 1 < pages.count
                ) { fragment in
                    bookBlock(fragment)
                }
                .environment(\.openURL, OpenURLAction { followBookLink($0, book: document.book, pages: pages) })
                .font(.system(size: bookTextSize * bodyScale / 17, design: .serif))
                .foregroundStyle(bookPaper == "System" ? Color.primary : bookPaper == "Dark" ? Color.white : Color.black)
                .background(pageColor)
                .textSelection(.enabled)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(pages[active].items) { item in
                            Group {
                                if mode == .book {
                                    bookBlock(BookFragment(item: item, id: BookAnchor(block: item.id)))
                                } else {
                                    documentBlock(item.node)
                                }
                            }.id(item.id)
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
            installControl()
            guard !restored else { return }
            restored = true
            section = min(max(file.record.position.section ?? 0, 0), pages.count - 1)
            block = file.record.position.block ?? pages[section].items.first?.id
            bookAnchor = block.map { BookAnchor(block: $0, offset: file.record.position.bookOffset ?? 0) }
        }
        .onChange(of: section) { _, next in
            guard pages.indices.contains(next) else { return }
            if !pages[next].contains(block) {
                block = pages[next].items.first?.id
            }
            if mode == .book && !pages[next].contains(bookAnchor?.block) {
                bookAnchor = block.map { BookAnchor(block: $0) }
            }
            selectedCell = nil
            onPosition(block ?? 0, next, mode == .book ? bookAnchor?.offset : nil)
        }
        .onChange(of: block) { _, next in
            if let next, pages[active].contains(next), mode != .book || !paged {
                onPosition(next, active, nil)
            }
        }
        .onChange(of: bookAnchor) { _, anchor in
            if mode == .book, paged, let anchor, pages[active].contains(anchor.block) {
                block = anchor.block
                onPosition(anchor.block, active, anchor.offset)
            }
        }
        .onChange(of: paged) { _, next in
            if mode == .book, next, let block, bookAnchor?.block != block {
                bookAnchor = BookAnchor(block: block)
            }
        }
    }

    @ViewBuilder
    private func documentBlock(_ node: EngineDocument.Node, fragment: BookFragment? = nil) -> some View {
        switch node.kind {
        case "heading", "section":
            Text(styled(node, fragment: fragment)).font((node.level ?? 1) < 2 ? .title2.bold() : .headline)
                .accessibilityAddTraits(.isHeader)
        case "list", "text":
            Text(styled(node, fragment: fragment))
        case "quote":
            Text(styled(node, fragment: fragment)).italic().foregroundStyle(.secondary)
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
                    .frame(maxHeight: fragment == nil ? nil : 240)
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

    private func styled(_ node: EngineDocument.Node, fragment: BookFragment?) -> AttributedString {
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
        if let fragment, let length = fragment.length,
           let range = Range(NSRange(location: fragment.id.offset, length: length), in: text),
           let start = AttributedString.Index(range.lowerBound, within: formatted),
           let end = AttributedString.Index(range.upperBound, within: formatted) {
            return AttributedString(formatted[start..<end])
        }
        if let fragment, let length = fragment.length {
            let value = (text as NSString).substring(with: NSRange(location: fragment.id.offset, length: length))
            return AttributedString(value)
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
        if let item = pages[selected].items.first(where: { $0.id >= position }) ?? pages[selected].items.first {
            go(BookAnchor(block: item.id), pages: pages)
        }
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

    private func bookBlock(_ fragment: BookFragment) -> some View {
        let anchor = fragment.id
        let source = fragment.item.node.text ?? "Illustration"
        let sourceLength = source.utf16.count
        let excerpt: String = {
            if let length = fragment.length,
               let range = Range(NSRange(location: anchor.offset, length: length), in: source) {
                return String(source[range].prefix(240))
            }
            return String(source.prefix(240))
        }()
        let marked = notebook.contains { entry in
            guard entry.kind == .highlight, entry.anchor.block == anchor.block else { return false }
            let markEnd = min(sourceLength, max(0, entry.anchor.offset)) + max(1, min(sourceLength, entry.length ?? sourceLength))
            let fragmentEnd = anchor.offset + max(1, fragment.length ?? sourceLength)
            return entry.anchor.offset < fragmentEnd && anchor.offset < markEnd
        }
        return documentBlock(fragment.item.node, fragment: fragment)
            .background(marked ? Color.yellow.opacity(0.3) : Color.clear)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contextMenu {
                ForEach([NotebookEntry.Kind.bookmark, .highlight], id: \.self) { kind in
                    let saved = notebook.contains { $0.kind == kind && $0.anchor == anchor }
                    Button(saved ? "Remove \(kind.title.lowercased())" : kind.title, systemImage: kind.icon) {
                        updateNotebook(.toggle(NotebookEntry(kind: kind, anchor: anchor, excerpt: excerpt, length: fragment.length)))
                    }
                }
                Button("Add or edit note", systemImage: "note.text") {
                    let entry = notebook.first { $0.kind == .note && $0.anchor == anchor }
                        ?? NotebookEntry(kind: .note, anchor: anchor, excerpt: excerpt)
                    draftText = entry.note ?? ""
                    noteDraft = entry
                }
            }
    }

    private func updateNotebook(_ change: NotebookChange) {
        Task {
            do { notebook = try await store.changeNotebook(file.record.id, change) }
            catch { notebookError = error.localizedDescription }
        }
    }

    private func go(_ anchor: BookAnchor, pages: [GroupPage]) {
        guard let index = pages.firstIndex(where: { $0.contains(anchor.block) }) else { return }
        section = index
        block = anchor.block
        bookAnchor = anchor
        onPosition(anchor.block, index, paged ? anchor.offset : nil)
    }

    private func selectSection(_ index: Int, pages: [GroupPage], atEnd: Bool = false) {
        guard pages.indices.contains(index) else { return }
        let entry = atEnd ? pages[index].items.last : pages[index].items.first
        section = index
        block = entry?.id
        if file.record.viewerKind == .book, let block {
            let lastOffset = atEnd ? (entry?.node.text?.utf16.count ?? 0) : 0
            bookAnchor = BookAnchor(block: block, offset: lastOffset)
        }
        if let block { onPosition(block, section, file.record.viewerKind == .book && paged ? bookAnchor?.offset : nil) }
    }

    private func installControl() {
        let mode = file.record.viewerKind
        let capabilities: Set<ReaderCommand.Capability>
        switch mode {
        case .book: capabilities = [.pages, .scroll, .zoom, .contents, .notebook]
        case .slides: capabilities = [.pages, .contents]
        case .sheet: capabilities = [.sheets, .cells, .scroll, .contents]
        default: capabilities = [.scroll, .contents]
        }
        control.install(owner: owner, supports: capabilities) { command in
            let pages = preparedPages
            guard pages.indices.contains(section), let last = pages.indices.last else {
                return .unavailable("This file is still loading.")
            }
            let page = pages[section]
            switch command {
            case .contents: contentsOpen = true
            case .notebook: notebookOpen = true
            case .zoom(let bigger):
                if mode == .book { bookTextSize = min(32, max(13, bookTextSize + (bigger ? 1 : -1))) }
                else { return .unavailable("Zoom isn’t available for this document.") }
            case .sheet(let delta): return move(to: section + delta, pages: pages)
            case .chapter(let value, let relative): return move(to: relative ? section + value : value, pages: pages)
            case .page(let value):
                if mode == .book && paged {
                    guard (0..<leafCount).contains(value) else { return .unavailable("That page is outside this chapter.") }
                    updateLeaf(value)
                } else { return move(to: value, pages: pages) }
            case .firstPage: selectSection(0, pages: pages)
            case .lastPage: selectSection(last, pages: pages, atEnd: true)
            case .pageStep(let delta):
                if mode == .book && paged, (0..<leafCount).contains(leafIndex + delta) { updateLeaf(leafIndex + delta) }
                else { return move(to: section + delta, pages: pages, atEnd: delta < 0) }
            case .top:
                selectSection(section, pages: pages)
                if mode == .book && paged { updateLeaf(0) }
            case .bottom:
                selectSection(section, pages: pages, atEnd: true)
                if mode == .book && paged && leafCount > 0 { updateLeaf(leafCount - 1) }
            case .scroll(let delta):
                if mode == .book && paged {
                    if (0..<leafCount).contains(leafIndex + delta) { updateLeaf(leafIndex + delta) }
                    else { return move(to: section + delta, pages: pages, atEnd: delta < 0) }
                } else {
                    let from = page.items.firstIndex { $0.id == block } ?? 0
                    let to = min(max(from + delta * 8, 0), page.items.count - 1)
                    if let item = page.items[safe: to] { block = item.id }
                }
            case .cell(let row, let column):
                guard let item = page.items.first(where: { $0.node.row == row })
                    ?? page.items[safe: row - 1].flatMap({ $0.node.row == nil ? $0 : nil }),
                    let cells = item.node.cells, cells.indices.contains(column) else {
                    return .unavailable("That cell isn’t in this sheet’s preview.")
                }
                selectedCell = SheetCell(row: item.id, column: column)
                block = item.id
            default:
                return .unavailable("This document doesn’t support that action.")
            }
            return .done
        }
    }

    private func move(to index: Int, pages: [GroupPage], atEnd: Bool = false) -> ReaderActionResult {
        guard pages.indices.contains(index) else { return .unavailable("There’s nothing in that direction.") }
        selectSection(index, pages: pages, atEnd: atEnd)
        return .done
    }

    private func updateLeaf(_ index: Int) {
        withAnimation(reducedMotion ? nil : .smooth(duration: 0.22)) { leafIndex = index }
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

struct LocatedNode: Identifiable, Sendable {
    let id: Int
    let node: EngineDocument.Node
}

private struct SheetCell: Hashable {
    let row: Int
    let column: Int
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

private struct SheetSurface: View {
    let rows: [LocatedNode]
    let columns: Int
    let pinRow: Bool
    let pinColumn: Bool
    @Binding var block: Int?
    let target: SheetCell?
    @State private var horizontalOffset: CGFloat = 0
    private let columnWidth: CGFloat = 146

    var body: some View {
        if rows.isEmpty {
            ContentUnavailableView("Empty sheet", systemImage: "tablecells")
        } else {
            ScrollViewReader { horizontal in
                ScrollView(.horizontal) {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0, pinnedViews: pinRow ? [.sectionHeaders] : []) {
                            Section {
                                ForEach(pinRow ? Array(rows.dropFirst()) : rows) { row in
                                    rowView(row).id(row.id)
                                        .onAppear { reveal(row: row.id, using: horizontal) }
                                }
                            } header: {
                                if pinRow, let first = rows.first {
                                    rowView(first, header: true).onAppear { reveal(row: first.id, using: horizontal) }
                                }
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
                .onChange(of: target) { _, target in
                    if let target { reveal(row: target.row, using: horizontal) }
                }
            }
        }
    }

    private func reveal(row: Int, using horizontal: ScrollViewProxy) {
        guard let target, target.row == row, !pinColumn || target.column > 0 else { return }
        horizontal.scrollTo(target, anchor: .leading)
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
                    .overlay {
                        let selected = target == SheetCell(row: row.id, column: index)
                        Rectangle().strokeBorder(selected ? Color.accentColor : Color.secondary.opacity(0.25), lineWidth: selected ? 2 : 0.5)
                    }
                    .id(SheetCell(row: row.id, column: index))
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
