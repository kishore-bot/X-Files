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

    /// Removes output folders an earlier run left behind (a crash, or the app killed while a view held one).
    /// Only folders untouched for [age] go, so anything this run just made is kept.
    static func sweepStale(olderThan age: TimeInterval = 3_600) {
        let fileManager = FileManager.default
        let keys: Set<URLResourceKey> = [.contentModificationDateKey, .isDirectoryKey]
        guard let items = try? fileManager.contentsOfDirectory(
            at: URL.temporaryDirectory, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]
        ) else { return }
        let cutoff = Date().addingTimeInterval(-age)
        for item in items where item.lastPathComponent.hasPrefix("Xfiles-") {
            guard let values = try? item.resourceValues(forKeys: keys), values.isDirectory == true,
                  let modified = values.contentModificationDate, modified < cutoff else { continue }
            remove(item)
        }
    }
}
