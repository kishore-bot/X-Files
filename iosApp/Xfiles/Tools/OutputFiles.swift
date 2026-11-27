import Foundation

enum OutputFiles {
    static func folder() throws -> URL {
        let url = URL.temporaryDirectory.appendingPathComponent("Xfiles-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func resource(in parent: URL, name: String) -> URL {
        let suffix = (name as NSString).pathExtension
        let safe = (1...10).contains(suffix.count) && suffix.utf8.allSatisfy {
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0)
        }
        return parent.appendingPathComponent(safe ? "resource.\(suffix)" : "resource")
    }

    static func copyURL(in parent: URL, file: ReaderFile, suffix: String, extension ext: String? = nil) -> URL {
        let original = file.url.deletingPathExtension().lastPathComponent
        let outputExtension = ext ?? file.url.pathExtension
        let name = "\(original)-\(suffix)" + (outputExtension.isEmpty ? "" : ".\(outputExtension)")
        return parent.appendingPathComponent(name)
    }

    static func remove(_ folder: URL) { try? FileManager.default.removeItem(at: folder) }
}
