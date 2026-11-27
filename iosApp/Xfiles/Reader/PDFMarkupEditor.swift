import PDFKit
import SwiftUI
import UIKit

struct PDFMarkupEditor: View {
    let file: ReaderFile
    let page: Int
    let library: LibraryModel
    let onSaved: (ReadDestination) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var mode: Mode = .pen
    @State private var marks: [EngineRequest.Markup] = []
    @State private var current: [CGPoint] = []
    @State private var noteAt: CGPoint?
    @State private var note = ""
    @State private var error: String?
    @State private var loadError: String?
    @State private var task: Task<Void, Never>?

    private enum Mode: String, CaseIterable {
        case pen = "Pen", highlight = "Highlight", note = "Note"
    }

    var body: some View {
        NavigationStack {
            VStack {
                if let image {
                    Picker("Annotation tool", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }.pickerStyle(.segmented).padding(.horizontal)
                    if mode == .note { Text("Tap to place a note").font(.caption).foregroundStyle(.secondary) }
                    drawing(image)
                    Button("Undo", systemImage: "arrow.uturn.backward") { if !marks.isEmpty { marks.removeLast() } }
                        .disabled(marks.isEmpty || task != nil)
                } else if let loadError {
                    ContentUnavailableView("Can’t annotate this PDF", systemImage: "pencil.slash", description: Text(loadError))
                } else {
                    ProgressView("Loading page")
                }
            }
            .navigationTitle("Annotate page \(page + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        task?.cancel()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if task != nil {
                        ProgressView()
                    } else {
                        Button("Save copy") { save() }.disabled(marks.isEmpty)
                    }
                }
            }
            .task(id: page) {
                if let document = PDFDocument(url: file.url), !document.isLocked,
                   let source = document.page(at: page) {
                    image = source.thumbnail(of: CGSize(width: 1024, height: 1400), for: .cropBox)
                } else {
                    loadError = "Open an unlocked PDF and choose a valid page."
                }
            }
            .alert("Add a note", isPresented: Binding(
                get: { noteAt != nil }, set: { if !$0 { noteAt = nil } }
            )) {
                TextField("Note", text: $note)
                Button("Cancel", role: .cancel) { noteAt = nil }
                Button("Add") {
                    if let position = noteAt, !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        add(.init(kind: "note", left: position.x, top: position.y, text: String(note.prefix(2_000))))
                    }
                    noteAt = nil
                }
            }
            .alert("Couldn’t save the copy", isPresented: Binding(
                get: { error != nil }, set: { if !$0 { error = nil } }
            )) {
                Button("OK") { error = nil }
            } message: { Text(error ?? "") }
        }
        .onDisappear { task?.cancel() }
    }

    private func drawing(_ image: UIImage) -> some View {
        GeometryReader { geometry in
            let scale = min(geometry.size.width / max(1, image.size.width), geometry.size.height / max(1, image.size.height))
            let width = image.size.width * scale
            let height = image.size.height * scale
            Image(uiImage: image)
                .resizable()
                .frame(width: width, height: height)
                .overlay {
                    Canvas { context, size in
                        for mark in marks + [draft()].compactMap({ $0 }) {
                            draw(mark, context: context, size: size)
                        }
                    }
                }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if mode != .note && current.count < 1_000 {
                            current.append(normalize(value.location, width: width, height: height))
                        }
                    }
                    .onEnded { value in
                        if mode == .note {
                            note = ""
                            noteAt = normalize(value.location, width: width, height: height)
                        } else if let mark = draft() {
                            add(mark)
                        }
                        current = []
                    }
                )
                .allowsHitTesting(task == nil)
                .accessibilityLabel("PDF annotation surface")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func normalize(_ location: CGPoint, width: CGFloat, height: CGFloat) -> CGPoint {
        CGPoint(x: min(max(location.x / max(width, 1), 0), 1), y: min(max(location.y / max(height, 1), 0), 1))
    }

    private func draft() -> EngineRequest.Markup? {
        guard let start = current.first, let end = current.last, current.count > 1 else { return nil }
        if mode == .highlight {
            guard abs(start.x - end.x) > 0.002, abs(start.y - end.y) > 0.002 else { return nil }
            return .init(kind: "highlight", left: min(start.x, end.x), top: min(start.y, end.y),
                         right: max(start.x, end.x), bottom: max(start.y, end.y))
        }
        return .init(kind: "pen", points: current.flatMap { [Double($0.x), Double($0.y)] })
    }

    private func add(_ mark: EngineRequest.Markup) {
        if marks.count < 64 { marks.append(mark) }
        else { error = "Save this copy before adding more annotations." }
    }

    private func draw(_ mark: EngineRequest.Markup, context: GraphicsContext, size: CGSize) {
        if mark.kind == "pen", let points = mark.points, points.count >= 4 {
            var path = Path()
            path.move(to: CGPoint(x: points[0] * size.width, y: points[1] * size.height))
            for index in stride(from: 2, to: points.count - 1, by: 2) {
                path.addLine(to: CGPoint(x: points[index] * size.width, y: points[index + 1] * size.height))
            }
            context.stroke(path, with: .color(.red), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        } else if let left = mark.left, let top = mark.top {
            let x = left * size.width
            let y = top * size.height
            if let right = mark.right, let bottom = mark.bottom {
                context.fill(Path(CGRect(x: x, y: y, width: (right - left) * size.width, height: (bottom - top) * size.height)),
                             with: .color(.yellow.opacity(0.45)))
            } else {
                context.fill(Path(ellipseIn: CGRect(x: x - 5, y: y - 5, width: 10, height: 10)), with: .color(.yellow))
            }
        }
    }

    private func save() {
        let annotations = marks
        task = Task {
            defer { task = nil }
            do {
                let directory = try OutputFiles.folder()
                defer { OutputFiles.remove(directory) }
                let output = OutputFiles.copyURL(in: directory, file: file, suffix: "annotated")
                _ = try await DocumentEngine.output(EngineRequest(
                    operation: "markup", path: file.url.path, output: output.path, page: page, marks: annotations
                ))
                try Task.checkCancellation()
                let id = try await library.importOutput(output)
                onSaved(ReadDestination(id: id, page: page))
                dismiss()
            } catch is CancellationError {
                return
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
