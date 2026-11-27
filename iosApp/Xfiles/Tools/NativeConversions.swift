import CoreGraphics
import CoreText
import Foundation
import ImageIO
import Vision
import os

enum NativeConversions {
    static func textPDF(_ text: String, to url: URL) async throws {
        try await offMain {
            var box = CGRect(x: 0, y: 0, width: 612, height: 792)
            guard let pdf = CGContext(url as CFURL, mediaBox: &box, nil) else {
                throw EngineError(message: "Couldn’t create the PDF.")
            }
            let attributed = NSAttributedString(string: text, attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): CTFontCreateWithName("Helvetica" as CFString, 12, nil),
                NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 0, alpha: 1)
            ])
            let framesetter = CTFramesetterCreateWithAttributedString(attributed as CFAttributedString)
            let framePath = CGPath(rect: box.insetBy(dx: 48, dy: 48), transform: nil)
            var offset = 0
            defer { pdf.closePDF() }
            while offset < attributed.length {
                try Task.checkCancellation()
                let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: offset, length: 0), framePath, nil)
                let consumed = CTFrameGetVisibleStringRange(frame).length
                guard consumed > 0 else { throw EngineError(message: "Some text didn’t fit on a PDF page.") }
                pdf.beginPDFPage(nil)
                pdf.textMatrix = .identity
                CTFrameDraw(frame, pdf)
                pdf.endPDFPage()
                offset += consumed
            }
        }
    }

    static func imagesPDF(_ images: [URL], to url: URL, searchable: Bool = false) async throws {
        try await offMain {
            var box = CGRect(x: 0, y: 0, width: 612, height: 792)
            guard let pdf = CGContext(url as CFURL, mediaBox: &box, nil) else {
                throw EngineError(message: "Couldn’t create the PDF.")
            }
            defer { pdf.closePDF() }
            for url in images {
                try Task.checkCancellation()
                guard let imageSource = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 2600
                      ] as CFDictionary) else {
                    throw EngineError(message: "Couldn’t read \(url.lastPathComponent).")
                }
                let padding = box.insetBy(dx: 24, dy: 24)
                let scale = min(padding.width / CGFloat(image.width), padding.height / CGFloat(image.height))
                let width = CGFloat(image.width) * scale
                let height = CGFloat(image.height) * scale
                let frame = CGRect(x: box.midX - width / 2, y: box.midY - height / 2, width: width, height: height)
                pdf.beginPDFPage(nil)
                pdf.draw(image, in: frame)
                if searchable {
                    let words = try recognize(image)
                    pdf.saveGState()
                    pdf.setTextDrawingMode(.invisible)
                    for word in words {
                        let bounds = word.bounds
                        let font = CTFontCreateWithName("Helvetica" as CFString, max(3, bounds.height * height), nil)
                        let text = NSAttributedString(string: word.text, attributes: [
                            NSAttributedString.Key(kCTFontAttributeName as String): font
                        ])
                        let line = CTLineCreateWithAttributedString(text as CFAttributedString)
                        pdf.textMatrix = .identity
                        pdf.textPosition = CGPoint(x: frame.minX + bounds.minX * width, y: frame.minY + bounds.minY * height)
                        CTLineDraw(line, pdf)
                    }
                    pdf.restoreGState()
                }
                pdf.endPDFPage()
            }
        }
    }

    private struct Recognition {
        let text: String
        let bounds: CGRect
    }

    private static func recognize(_ image: CGImage) throws -> [Recognition] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        try VNImageRequestHandler(cgImage: image).perform([request])
        return (request.results ?? []).compactMap { observation in
            guard let text = observation.topCandidates(1).first?.string else { return nil }
            return Recognition(text: text, bounds: observation.boundingBox)
        }
    }

    static func zip(_ files: [URL], to url: URL) async throws {
        try await offMain {
            let folder = try OutputFiles.folder()
            defer { OutputFiles.remove(folder) }
            for source in files {
                try Task.checkCancellation()
                var copy = folder.appendingPathComponent(source.lastPathComponent)
                if FileManager.default.fileExists(atPath: copy.path) {
                    copy = folder.appendingPathComponent("\(UUID().uuidString)-\(source.lastPathComponent)")
                }
                try FileManager.default.copyItem(at: source, to: copy)
            }
            let result = OSAllocatedUnfairLock(initialState: Optional<Result<Void, any Error>>.none)
            var coordinationError: NSError?
            NSFileCoordinator().coordinate(readingItemAt: folder, options: .forUploading, error: &coordinationError) { archive in
                let copied = Result { try FileManager.default.copyItem(at: archive, to: url) }
                result.withLock { $0 = copied }
            }
            if let coordinationError { throw coordinationError }
            guard let copy = result.withLock({ $0 }) else { throw EngineError(message: "Couldn’t create the ZIP file.") }
            try copy.get()
        }
    }

    private static func offMain(_ operation: @escaping @Sendable () throws -> Void) async throws {
        let worker = Task.detached(priority: .userInitiated) { try operation() }
        try await withTaskCancellationHandler {
            try await worker.value
            try Task.checkCancellation()
        } onCancel: { worker.cancel() }
    }
}
