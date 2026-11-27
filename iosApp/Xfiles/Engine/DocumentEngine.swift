import Darwin
import Foundation

struct EngineRequest: Encodable, Sendable {
    let operation: String
    var path: String?
    var output: String?
    var password: String?
    var newPassword: String?
    var resource: String?
    var entries: [String]?
    var inputs: [Source]?
    var pages: [Page]?
    var page: Int?
    var strokes: [[Double]]?
    var marks: [Markup]?
    var text: String?
    var find: String?
    var replacement: String?
    var caseSensitive: Bool?
    var sheet: String?
    var row: Int?
    var column: Int?
    var index: String?
    var files: [IndexFile]?
    var query: String?
    var id: String?
    var size: Int64?
    var stamp: Int64?
    var quality: String?

    struct Markup: Encodable, Sendable {
        let kind: String
        var points: [Double]?
        var left: Double?
        var top: Double?
        var right: Double?
        var bottom: Double?
        var text: String?
    }

    struct Source: Encodable, Sendable {
        let path: String
        var password: String? = nil
    }

    struct Page: Encodable, Sendable {
        let page: Int
        let rotation: Int
    }

    struct IndexFile: Encodable, Sendable {
        let id: String
        let path: String
        let size: Int64
        let stamp: Int64
    }
}

struct EngineError: LocalizedError, Sendable {
    let message: String
    var code = "engine_error"
    var errorDescription: String? { message }
    var needsPassword: Bool { code == "password_required" || code == "wrong_password" }
}

enum DocumentEngine {
    private struct Response<Value: Decodable>: Decodable {
        var value: Value?
        var error: String?
        var code: String?
    }

    private final class Job: @unchecked Sendable {
        let handle = xfiles_ios_job_new()
        func cancel() { xfiles_ios_job_cancel(handle) }
        deinit { xfiles_ios_job_free(handle) }
    }

    static func run<Value: Decodable & Sendable>(_ request: EngineRequest) async throws -> Value {
        let job = Job()
        let task = Task.detached(priority: .userInitiated) { () throws -> Value in
            try Task.checkCancellation()
            let json = String(decoding: try JSONEncoder().encode(request), as: UTF8.self)
            guard let output = json.withCString({ xfiles_ios_call($0, job.handle) }) else {
                throw EngineError(message: "The document engine returned no response.")
            }
            defer { xfiles_ios_string_free(output) }
            let data = Data(bytes: output, count: strlen(output))
            try Task.checkCancellation()
            let response = try JSONDecoder().decode(Response<Value>.self, from: data)
            if response.code == "cancelled" { throw CancellationError() }
            guard let value = response.value else {
                throw EngineError(message: response.error ?? "The document engine returned no result.", code: response.code ?? "engine_error")
            }
            return value
        }
        return try await withTaskCancellationHandler {
            let result = try await task.value
            try Task.checkCancellation()
            return result
        } onCancel: {
            job.cancel()
            task.cancel()
        }
    }

    static func output(_ request: EngineRequest) async throws -> Int {
        struct Result: Decodable, Sendable { let count: Int }
        let result: Result = try await run(request)
        return result.count
    }
}

struct EngineDocument: Decodable, Sendable {
    let nodes: [Node]
    let truncated: Bool
    let book: Book?

    struct Book: Decodable, Sendable {
        let chapters: [Chapter]
        let toc: [Title]
        let anchors: [String: Int]
        let notes: [String: String]

        struct Chapter: Decodable, Sendable { let href: String; let block: Int }
        struct Title: Decodable, Sendable { let title: String; let href: String }
    }

    struct Span: Decodable, Sendable {
        let start: Int
        let end: Int
        let style: UInt8
        let link: String?
    }

    struct Node: Decodable, Sendable {
        let kind: String
        let sourceIndex: Int
        let text: String?
        let spans: [Span]?
        let level: Int?
        let row: Int?
        let cells: [String]?
        let resource: String?
        let media: String?
        let mediaExternal: Bool?
        let link: String?
        let header: Bool?
        let x: Double?
        let y: Double?
        let width: Double?
        let height: Double?
        let rotation: Double?
        let background: UInt32?
    }
}

struct ArchiveItem: Decodable, Sendable, Identifiable {
    let path: String
    let size: Int64
    let directory: Bool
    let protected: Bool
    var id: String { path }
}

struct FileSearchHit: Decodable, Sendable, Hashable {
    let id: UUID
    let snippet: String
    let page: Int?
}
