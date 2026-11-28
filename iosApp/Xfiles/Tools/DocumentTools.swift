import SwiftUI
import UniformTypeIdentifiers

struct DocumentTools: View {
    let library: LibraryModel
    let initial: ReaderFile?
    var preset: Operation? = nil
    let onOutput: (ReadDestination) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var operation: Operation?
    @State private var selected: [UUID] = []
    @State private var text = ""
    @State private var replacement = ""
    @State private var sheet = ""
    @State private var row = ""
    @State private var column = ""
    @State private var pageList = ""
    @State private var rotation = 0
    @State private var searchableImages = false
    @State private var password = ""
    @State private var newPassword = ""
    @State private var archiveFormat: ArchiveFormat = .zip
    @State private var protectArchive = false
    @State private var archiveFolder: URL?
    @State private var pickArchiveFolder = false

    private enum ArchiveFormat: String, CaseIterable, Identifiable {
        case zip, tar, gzip = "tar.gz", sevenZip = "7z"
        var id: String { rawValue }
        var label: String { self == .sevenZip ? rawValue : rawValue.uppercased() }
    }
    @State private var signature: [[CGPoint]] = []
    @State private var task: Task<Void, Never>?
    @State private var error: String?

    enum Operation: String, CaseIterable, Identifiable {
        case merge, pages, rotate, compress, protect, unlock, watermark, note, sign
        case textPDF, imagesPDF, pdfText, officeReplace, cellEdit, slides, zip
        var id: Self { self }

        var label: String {
            switch self {
            case .merge: "Merge PDFs"
            case .pages: "Extract or reorder PDF pages"
            case .rotate: "Rotate PDF pages"
            case .compress: "Compress PDF"
            case .protect: "Password protect PDF"
            case .unlock: "Remove PDF password"
            case .watermark: "Watermark PDF"
            case .note: "Add PDF note"
            case .sign: "Add drawn signature"
            case .textPDF: "Text to PDF"
            case .imagesPDF: "Images to PDF"
            case .pdfText: "Extract PDF text"
            case .officeReplace: "Office find and replace"
            case .cellEdit: "Edit XLSX cell"
            case .slides: "Reorder or remove PPTX slides"
            case .zip: "Create archive"
            }
        }

        var needsFiles: Bool { self != .textPDF }
        var multipleFiles: Bool { self == .merge || self == .imagesPDF || self == .zip }

        func accepts(_ record: DocumentRecord) -> Bool {
            let ext = (record.name as NSString).pathExtension.lowercased()
            switch self {
            case .textPDF: false
            case .imagesPDF: record.viewerKind == .image
            case .officeReplace: ["docx", "pptx", "odt", "odp"].contains(ext)
            case .slides: ext == "pptx"
            case .cellEdit: ext == "xlsx"
            case .zip: true
            default: record.viewerKind == .pdf
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Operation", selection: $operation) {
                    Text("Choose a tool").tag(nil as Operation?)
                    ForEach(Operation.allCases) { item in Text(item.label).tag(Optional(item)) }
                }
                .disabled(task != nil)
                if let operation {
                    if operation.needsFiles {
                        Section(operation.multipleFiles ? "Files in selection order" : "File") {
                            ForEach(library.documents.filter(operation.accepts)) { record in
                                Button { pick(record.id, multiple: operation.multipleFiles) } label: {
                                    HStack {
                                        Label(record.name, systemImage: record.viewerKind.symbol)
                                        Spacer()
                                        if let order = selected.firstIndex(of: record.id) {
                                            Image(systemName: operation.multipleFiles ? "\(order + 1).circle.fill" : "checkmark")
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                            if !library.documents.contains(where: operation.accepts) {
                                Text("Import a compatible file in Library first.").foregroundStyle(.secondary)
                            }
                        }
                    }
                    fields(operation)
                    Section {
                        if task != nil {
                            ProgressView("Preparing copy")
                            Button("Cancel", role: .cancel) { task?.cancel() }
                        } else {
                            Button("Create copy") { start(operation) }
                                .disabled((operation.needsFiles && selected.isEmpty && (operation != .zip || archiveFolder == nil))
                                    || (operation == .zip && protectArchive && newPassword.isEmpty))
                        }
                    }
                }
            }
            .fileImporter(isPresented: $pickArchiveFolder, allowedContentTypes: [.folder]) { result in
                do { archiveFolder = try result.get() }
                catch { self.error = error.localizedDescription }
            }
            .navigationTitle("Document tools")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .onAppear {
                if operation == nil, let preset { operation = preset }
            }
            .onChange(of: operation) { _, next in
                selected = initial.flatMap { next?.accepts($0.record) == true ? [$0.record.id] : nil } ?? []
                password = ""
                newPassword = ""
                signature = []
                protectArchive = false
                archiveFolder = nil
            }
            .alert("Couldn’t create copy", isPresented: Binding(
                get: { error != nil }, set: { if !$0 { error = nil } }
            )) {
                Button("OK", role: .cancel) { error = nil }
            } message: { Text(error ?? "") }
        }
        .onDisappear { task?.cancel() }
    }

    @ViewBuilder
    private func fields(_ operation: Operation) -> some View {
        switch operation {
        case .pages, .rotate, .slides:
            Section {
                TextField(operation == .slides ? "Slide numbers" : "Page numbers", text: $pageList)
                    .keyboardType(.numbersAndPunctuation)
                if operation == .rotate {
                    Picker("Clockwise rotation", selection: $rotation) {
                        Text("90°").tag(0)
                        Text("180°").tag(1)
                        Text("270°").tag(2)
                    }
                }
            } footer: {
                Text(operation == .rotate ? "Enter 1-based pages separated by commas. Leave blank to rotate every page." :
                    "Enter 1-based numbers separated by commas, in the order you want them.")
            }
        case .sign:
            Section("Draw signature") {
                SignaturePad(strokes: $signature).frame(height: 120)
                Button("Clear") { signature = [] }
                TextField("Page number", text: $pageList).keyboardType(.numberPad)
            }
        case .note:
            Section("Note") {
                TextField("Page number", text: $pageList).keyboardType(.numberPad)
                TextField("Note text", text: $text, axis: .vertical)
            }
        case .watermark:
            TextField("Watermark text", text: $text)
        case .zip:
            if let archiveFolder {
                LabeledContent("Folder", value: archiveFolder.lastPathComponent)
                Button("Remove folder", role: .destructive) { self.archiveFolder = nil }
            } else {
                Button("Add a folder from Files") { pickArchiveFolder = true }
            }
            Picker("Archive format", selection: $archiveFormat) {
                ForEach(ArchiveFormat.allCases) { Text($0.label).tag($0) }
            }
            .onChange(of: archiveFormat) { _, _ in protectArchive = false; newPassword = "" }
            if archiveFormat == .zip {
                Toggle("Protect with password", isOn: $protectArchive)
                    .onChange(of: protectArchive) { _, value in if !value { newPassword = "" } }
                if protectArchive {
                    SecureField("ZIP password", text: $newPassword)
                    Text("File names remain visible.").foregroundStyle(.secondary)
                }
            }
        case .imagesPDF:
            Toggle("Recognize text on device", isOn: $searchableImages)
        case .textPDF:
            TextField("Document text", text: $text, axis: .vertical).lineLimit(4...12)
        case .officeReplace:
            TextField("Find", text: $text)
            TextField("Replace with", text: $replacement)
        case .cellEdit:
            TextField("Sheet name", text: $sheet)
            TextField("Row (1-based)", text: $row).keyboardType(.numberPad)
            TextField("Column (1-based)", text: $column).keyboardType(.numberPad)
            TextField("Cell text", text: $text)
        default:
            EmptyView()
        }
        if [.merge, .pages, .rotate, .compress, .protect, .unlock, .watermark, .sign, .pdfText].contains(operation) {
            SecureField(operation == .merge ? "Source password, if shared by PDFs" : "Current PDF password, if needed", text: $password)
        }
        if operation == .protect { SecureField("New PDF password", text: $newPassword) }
    }

    private func pick(_ id: UUID, multiple: Bool) {
        if let index = selected.firstIndex(of: id) { selected.remove(at: index) }
        else if multiple { selected.append(id) }
        else { selected = [id] }
    }

    private func start(_ operation: Operation) {
        let sourceIDs = selected
        let sourcePassword = password
        let targetPassword = operation == .zip && !protectArchive ? "" : newPassword
        let format = archiveFormat
        let folder = archiveFolder
        let body = text
        let replace = replacement
        let sheetName = sheet
        let rowText = row
        let columnText = column
        let requestedPages = pageList
        let degrees = (rotation + 1) * 90
        let searchable = searchableImages
        let lines = signature.map { $0.flatMap { [Double($0.x), Double($0.y)] } }
        password = ""
        newPassword = ""
        task = Task {
            defer { task = nil }
            do {
                var files: [ReaderFile] = []
                for id in sourceIDs { files.append(try await library.store.file(id)) }
                let directory = try OutputFiles.folder()
                defer { OutputFiles.remove(directory) }
                let result = try await prepare(
                    operation, files: files, in: directory, sourcePassword: sourcePassword,
                    targetPassword: targetPassword, text: body, replacement: replace, sheetName: sheetName,
                    row: rowText, column: columnText, pages: requestedPages, degrees: degrees, strokes: lines, searchable: searchable, archiveFormat: format, archiveFolder: folder
                )
                try Task.checkCancellation()
                let id = try await library.importOutput(result)
                onOutput(ReadDestination(id: id))
                dismiss()
            } catch is CancellationError {
                return
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func prepare(
        _ operation: Operation, files: [ReaderFile], in directory: URL,
        sourcePassword: String, targetPassword: String, text: String, replacement: String,
        sheetName: String, row: String, column: String, pages: String, degrees: Int, strokes: [[Double]], searchable: Bool, archiveFormat: ArchiveFormat, archiveFolder: URL?
    ) async throws -> URL {
        switch operation {
        case .textPDF:
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw EngineError(message: "Enter the text to convert.")
            }
            let output = directory.appendingPathComponent("Document.pdf")
            try await NativeConversions.textPDF(text, to: output)
            return output
        case .imagesPDF:
            let output = directory.appendingPathComponent("Images.pdf")
            try await NativeConversions.imagesPDF(files.map(\.url), to: output, searchable: searchable)
            return output
        case .zip:
            let output = directory.appendingPathComponent("Xfiles archive.\(archiveFormat.rawValue)")
            let scope = archiveFolder?.startAccessingSecurityScopedResource() ?? false
            defer { if scope { archiveFolder?.stopAccessingSecurityScopedResource() } }
            let sources = files.map(\.url) + (archiveFolder.map { [$0] } ?? [])
            _ = try await DocumentEngine.output(EngineRequest(
                operation: "createArchive", output: output.path,
                password: targetPassword.isEmpty ? nil : targetPassword,
                resource: archiveFormat.rawValue, inputs: sources.map { .init(path: $0.path) }
            ))
            return output
        default:
            break
        }

        guard let file = files.first else { throw EngineError(message: "Choose a file first.") }
        let ext = operation == .pdfText ? "txt" : nil
        let output = OutputFiles.copyURL(in: directory, file: file, suffix: operation.rawValue, extension: ext)
        let currentPassword = sourcePassword.isEmpty ? nil : sourcePassword
        var request = EngineRequest(operation: operation == .rotate ? "pages" : operation.rawValue,
                                    path: file.url.path, output: output.path, password: currentPassword)
        switch operation {
        case .pdfText:
            let contents: String = try await DocumentEngine.run(EngineRequest(operation: "text", path: file.url.path, password: currentPassword))
            guard !contents.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw EngineError(message: "This PDF has no extractable text.")
            }
            try contents.write(to: output, atomically: true, encoding: .utf8)
            return output
        case .merge:
            guard files.count > 1 else { throw EngineError(message: "Choose at least two PDFs.") }
            request.inputs = files.map { .init(path: $0.url.path, password: currentPassword) }
        case .pages, .slides:
            request.pages = try pageNumbers(pages).map { .init(page: $0, rotation: 0) }
        case .rotate:
            struct PDFInfo: Decodable, Sendable { let pageCount: Int }
            let info: PDFInfo = try await DocumentEngine.run(EngineRequest(
                operation: "pdfInfo", path: file.url.path, password: currentPassword
            ))
            guard (1...5_000).contains(info.pageCount) else { throw EngineError(message: "This PDF has too many pages for one operation.") }
            let chosen = Set(pages.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                             ? Array(0..<info.pageCount) : try pageNumbers(pages))
            guard chosen.allSatisfy({ $0 < info.pageCount }) else { throw EngineError(message: "A page number is outside the PDF.") }
            request.pages = (0..<info.pageCount).map { .init(page: $0, rotation: chosen.contains($0) ? degrees : 0) }
        case .protect:
            guard !targetPassword.isEmpty else { throw EngineError(message: "Enter a new PDF password.") }
            request.newPassword = targetPassword
        case .officeReplace:
            request.find = text
            request.replacement = replacement
        case .cellEdit:
            guard let r = Int(row), (1...1_048_576).contains(r),
                  let c = Int(column), (1...16_384).contains(c) else {
                throw EngineError(message: "Enter a valid row and column.")
            }
            request.sheet = sheetName
            request.row = r - 1
            request.column = c - 1
            request.text = text
        case .watermark, .note:
            request.text = text
            if operation == .note { request.page = try singlePage(pages) }
        case .sign:
            request.page = try singlePage(pages)
            request.strokes = strokes
        default:
            break
        }
        _ = try await DocumentEngine.output(request)
        return output
    }

    private func pageNumbers(_ value: String) throws -> [Int] {
        let fields = value.split(separator: ",", omittingEmptySubsequences: false)
        let numbers = fields.compactMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
        guard !numbers.isEmpty, numbers.count == fields.count, numbers.count <= 5_000,
              numbers.allSatisfy({ (1...Int(UInt32.max)).contains($0) }) else {
            throw EngineError(message: "Enter valid positive numbers separated by commas.")
        }
        return numbers.map { $0 - 1 }
    }

    private func singlePage(_ value: String) throws -> Int {
        let numbers = try pageNumbers(value)
        guard numbers.count == 1, let page = numbers.first else { throw EngineError(message: "Enter one page number.") }
        return page
    }
}

private struct SignaturePad: View {
    @Binding var strokes: [[CGPoint]]
    @State private var current: [CGPoint] = []

    var body: some View {
        GeometryReader { geometry in
            Canvas { context, size in
                for stroke in strokes + [current] {
                    guard let first = stroke.first else { continue }
                    var path = Path()
                    path.move(to: CGPoint(x: first.x * size.width, y: first.y * size.height))
                    for point in stroke.dropFirst() {
                        path.addLine(to: CGPoint(x: point.x * size.width, y: point.y * size.height))
                    }
                    context.stroke(path, with: .color(.primary), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                }
            }
            .background(.quaternary.opacity(0.35))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                if strokes.count < 36 && current.count < 300 {
                    current.append(CGPoint(x: min(1, max(0, value.location.x / max(1, geometry.size.width))),
                                           y: min(1, max(0, value.location.y / max(1, geometry.size.height)))))
                }
            }.onEnded { _ in
                if current.count > 1 { strokes.append(current) }
                current = []
            })
        }
        .accessibilityLabel("Signature drawing area")
    }
}
