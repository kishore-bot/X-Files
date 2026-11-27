import Foundation
import UniformTypeIdentifiers
import os

actor DocumentStore {
    private let root: URL
    private let files = FileManager.default
    private var lastPositionWrite: [UUID: UInt64] = [:]
    private var prepared = false
    private var staging: URL { root.appendingPathComponent(".staging", isDirectory: true) }

    init(root: URL = URL.applicationSupportDirectory.appendingPathComponent("Xfiles", isDirectory: true)) {
        self.root = root
    }

    func records() throws -> [DocumentRecord] {
        try prepare()
        return try files.contentsOfDirectory(at: root, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
            .compactMap { directory in
                guard let id = UUID(uuidString: directory.lastPathComponent) else { return nil }
                return try? read(id)
            }
            .sorted { $0.importedAt > $1.importedAt }
    }

    func importFile(_ source: URL) throws -> DocumentRecord {
        try prepare()
        guard source.isFileURL else { throw StoreError.invalidFile }
        let name = source.lastPathComponent
        guard Self.isFileName(name) else { throw StoreError.invalidFile }

        let id = UUID()
        let pending = staging.appendingPathComponent(id.uuidString, isDirectory: true)
        let target = payload(id, name: name, directory: pending)
        defer { try? files.removeItem(at: pending) }
        try files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)

        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let result = OSAllocatedUnfairLock(initialState: Optional<Result<DocumentRecord, any Error>>.none)
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { readableURL in
            let outcome = Result {
                let values = try readableURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .contentTypeKey])
                guard values.isRegularFile == true, values.isSymbolicLink != true else { throw StoreError.invalidFile }
                try FileManager.default.copyItem(at: readableURL, to: target)
                let copy = try target.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
                guard copy.isRegularFile == true, copy.isSymbolicLink != true else { throw StoreError.invalidFile }
                let declaredKind = DocumentKind(type: values.contentType)
                let kind = declaredKind == .other ? DocumentKind(type: UTType(filenameExtension: source.pathExtension)) : declaredKind
                return DocumentRecord(
                    schemaVersion: 1,
                    id: id,
                    name: name,
                    kind: kind,
                    byteCount: Int64(copy.fileSize ?? 0),
                    importedAt: .now
                )
            }
            result.withLock { $0 = outcome }
        }
        if let coordinationError { throw coordinationError }
        guard let outcome = result.withLock({ $0 }) else { throw StoreError.invalidFile }
        let record = try outcome.get()
        try write(record, directory: pending)
        try files.moveItem(at: pending, to: directory(id))
        return record
    }

    func open(_ id: UUID) throws -> ReaderFile {
        var record = try read(id)
        record.openedAt = .now
        try write(record, directory: directory(id))
        return ReaderFile(record: record, url: payload(id, name: record.name))
    }

    func toggleBookmark(_ id: UUID) throws -> DocumentRecord {
        var record = try read(id)
        record.bookmarked.toggle()
        try write(record, directory: directory(id))
        return record
    }

    func savePosition(_ position: DocumentPosition, id: UUID, revision: UInt64) throws {
        guard revision > lastPositionWrite[id, default: 0] else { return }
        var record = try read(id)
        if record.position != position {
            record.position = position
            try write(record, directory: directory(id))
        }
        lastPositionWrite[id] = revision
    }

    func remove(_ id: UUID) throws {
        try prepare()
        let deleted = staging.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try files.moveItem(at: directory(id), to: deleted)
        lastPositionWrite.removeValue(forKey: id)
        try? files.removeItem(at: deleted)
    }

    private func prepare() throws {
        guard !prepared else { return }
        try files.createDirectory(at: staging, withIntermediateDirectories: true)
        for abandoned in try files.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil) {
            try? files.removeItem(at: abandoned)
        }
        prepared = true
    }

    private func directory(_ id: UUID) -> URL {
        root.appendingPathComponent(id.uuidString, isDirectory: true)
    }

    private func payload(_ id: UUID, name: String, directory pending: URL? = nil) -> URL {
        (pending ?? directory(id)).appendingPathComponent("payload", isDirectory: true).appendingPathComponent(name)
    }

    private func read(_ id: UUID) throws -> DocumentRecord {
        let record = try JSONDecoder().decode(DocumentRecord.self, from: Data(contentsOf: metadata(in: directory(id))))
        guard record.schemaVersion == 1, record.id == id, Self.isFileName(record.name) else { throw StoreError.invalidRecord }
        let values = try payload(id, name: record.name).resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else { throw StoreError.invalidFile }
        return record
    }

    private func write(_ record: DocumentRecord, directory: URL) throws {
        try JSONEncoder().encode(record).write(to: metadata(in: directory), options: .atomic)
    }

    private func metadata(in directory: URL) -> URL { directory.appendingPathComponent("record.json") }

    private static func isFileName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && !name.contains("/")
    }

    private enum StoreError: LocalizedError {
        case invalidFile, invalidRecord

        var errorDescription: String? {
            switch self {
            case .invalidFile: "Xfiles could not read this file. Choose an available file in Files."
            case .invalidRecord: "Xfiles could not read the library entry."
            }
        }
    }
}
