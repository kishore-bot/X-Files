import SwiftUI
import UniformTypeIdentifiers

struct LibraryScreen: View {
    let model: LibraryModel
    @Binding var appearance: Appearance
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var route: [UUID] = []
    @State private var section: SectionFilter = .library
    @State private var kind: DocumentKind?
    @State private var query = ""
    @State private var importing = false
    @State private var settingsOpen = false
    @State private var deleteCandidate: DocumentRecord?

    private enum SectionFilter: String, CaseIterable {
        case library = "Library", recent = "Recent", bookmarks = "Bookmarks"
    }

    private var results: [DocumentRecord] {
        let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let matching = model.documents.filter { record in
            (kind == nil || kind == record.kind) &&
            (search.isEmpty || record.name.localizedStandardContains(search)) &&
            (section != .recent || record.openedAt != nil) &&
            (section != .bookmarks || record.bookmarked)
        }
        return section == .recent ? matching.sorted { ($0.openedAt ?? .distantPast) > ($1.openedAt ?? .distantPast) } : matching
    }

    var body: some View {
        NavigationStack(path: $route) {
            VStack(spacing: 0) {
                Picker("Library section", selection: $section) {
                    ForEach(SectionFilter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 8)

                if model.loading {
                    Spacer()
                    ProgressView("Loading library")
                    Spacer()
                } else {
                    fileList
                }
            }
            .navigationTitle("Xfiles")
            .searchable(text: $query, prompt: "Search file names")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Picker("File type", selection: $kind) {
                            Label("All files", systemImage: "tray").tag(nil as DocumentKind?)
                            ForEach(DocumentKind.allCases, id: \.self) { item in
                                Label(item.title, systemImage: item.symbol).tag(Optional(item))
                            }
                        }
                    } label: {
                        Label("Filter file types", systemImage: kind == nil ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
                    }
                    Button { importing = true } label: { Label("Import files", systemImage: "plus") }
                        .disabled(model.importCount > 0)
                    Button { settingsOpen = true } label: { Label("Settings", systemImage: "gearshape") }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if model.importCount > 0 {
                    ProgressView("Importing files")
                        .padding(12)
                        .frame(maxWidth: .infinity)
                        .background(.regularMaterial)
                }
            }
            .navigationDestination(for: UUID.self) { id in
                ReaderScreen(id: id, library: model)
            }
            .sheet(isPresented: $settingsOpen) { SettingsScreen(appearance: $appearance) }
            .task { await model.reload() }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): receive(urls)
                case .failure(let error): model.message = error.localizedDescription
                }
            }
            .onOpenURL { if $0.isFileURL { receive([$0]) } }
            .alert("Xfiles", isPresented: Binding(
                get: { model.message != nil },
                set: { if !$0 { model.message = nil } }
            )) {
                Button("OK", role: .cancel) { model.message = nil }
            } message: { Text(model.message ?? "") }
            .confirmationDialog("Remove imported copy?", isPresented: Binding(
                get: { deleteCandidate != nil },
                set: { if !$0 { deleteCandidate = nil } }
            ), titleVisibility: .visible, presenting: deleteCandidate) { record in
                Button("Remove from Xfiles", role: .destructive) {
                    deleteCandidate = nil
                    Task { await model.remove(record.id) }
                }
            } message: { record in
                Text("The imported copy of \(record.name) will be removed. The original file stays in Files.")
            }
        }
    }

    private var fileList: some View {
        let visible = results
        return List(visible) { record in
            NavigationLink(value: record.id) {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(record.name).font(.body.weight(.medium)).lineLimit(2)
                        Text(ByteCountFormatter.string(fromByteCount: record.byteCount, countStyle: .file))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: record.kind.symbol).foregroundStyle(.tint)
                }
                .overlay(alignment: .trailing) {
                    if record.bookmarked {
                        Image(systemName: "bookmark.fill").font(.caption).foregroundStyle(.tint)
                            .padding(.trailing, 4).accessibilityLabel("Bookmarked")
                    }
                }
                .padding(.trailing, record.bookmarked ? 20 : 0)
                .padding(.vertical, 3)
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) { deleteCandidate = record } label: { Label("Remove", systemImage: "trash") }
            }
            .swipeActions(edge: .leading, allowsFullSwipe: false) {
                Button { Task { await model.bookmark(record.id) } } label: {
                    Label(record.bookmarked ? "Remove bookmark" : "Bookmark", systemImage: record.bookmarked ? "bookmark.slash" : "bookmark")
                }
            }
            .contextMenu {
                Button { Task { await model.bookmark(record.id) } } label: {
                    Label(record.bookmarked ? "Remove bookmark" : "Bookmark", systemImage: "bookmark")
                }
                Button(role: .destructive) { deleteCandidate = record } label: { Label("Remove", systemImage: "trash") }
            }
        }
        .listStyle(.plain)
        .refreshable { await model.reload() }
        .animation(reduceMotion ? nil : .smooth(duration: 0.24), value: visible.map(\.id))
        .overlay {
            if visible.isEmpty {
                if searchOrFilterActive {
                    ContentUnavailableView("No matching files", systemImage: "magnifyingglass")
                } else if section == .library {
                    ContentUnavailableView {
                        Label("No files yet", systemImage: "tray")
                    } description: {
                        Text("Import PDFs, photos, media, and documents from Files.")
                    } actions: {
                        Button("Import files") { importing = true }.buttonStyle(.borderedProminent)
                            .disabled(model.importCount > 0)
                    }
                } else {
                    ContentUnavailableView(
                        section == .recent ? "No recent files" : "No bookmarks yet",
                        systemImage: section == .recent ? "clock" : "bookmark"
                    )
                }
            }
        }
    }

    private var searchOrFilterActive: Bool {
        kind != nil || !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func receive(_ urls: [URL]) {
        Task {
            let ids = await model.importFiles(urls)
            if urls.count == 1, let id = ids.first { route.append(id) }
        }
    }
}
