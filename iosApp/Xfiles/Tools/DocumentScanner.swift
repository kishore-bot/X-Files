import SwiftUI
import VisionKit

struct DocumentScanner: View {
    let library: LibraryModel
    let onSaved: (ReadDestination) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var task: Task<Void, Never>?
    @State private var received = false
    @State private var error: String?

    var body: some View {
        Group {
            if let error {
                NavigationStack {
                    ContentUnavailableView("Scan could not be saved", systemImage: "doc.viewfinder", description: Text(error))
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                }
            } else if received {
                VStack(spacing: 20) {
                    ProgressView("Creating a searchable PDF")
                    Button("Cancel", role: .cancel) {
                        task?.cancel()
                        dismiss()
                    }
                }
            } else if VNDocumentCameraViewController.isSupported {
                Camera(onScan: save, onCancel: { dismiss() }, onError: { error = $0.localizedDescription })
            } else {
                NavigationStack {
                    ContentUnavailableView(
                        "Scanner unavailable", systemImage: "doc.viewfinder",
                        description: Text("Scan on a device with a supported camera, or import photos and convert them to PDF.")
                    )
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                }
            }
        }
        .onDisappear { task?.cancel() }
    }

    private func save(_ scan: VNDocumentCameraScan) {
        guard scan.pageCount > 0 else { return }
        received = true
        task = Task {
            defer { task = nil }
            do {
                let directory = try OutputFiles.folder()
                defer { OutputFiles.remove(directory) }
                var images: [URL] = []
                for page in 0..<scan.pageCount {
                    try Task.checkCancellation()
                    let path = directory.appendingPathComponent("page-\(page + 1).jpg")
                    try autoreleasepool {
                        guard let data = scan.imageOfPage(at: page).jpegData(compressionQuality: 0.9) else {
                            throw EngineError(message: "Couldn’t read scan page \(page + 1).")
                        }
                        try data.write(to: path)
                    }
                    images.append(path)
                    await Task.yield()
                }
                let output = directory.appendingPathComponent("Scanned document.pdf")
                try await NativeConversions.imagesPDF(images, to: output, searchable: true)
                try Task.checkCancellation()
                let id = try await library.importOutput(output)
                onSaved(ReadDestination(id: id))
                dismiss()
            } catch is CancellationError {
                return
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private struct Camera: UIViewControllerRepresentable {
        let onScan: (VNDocumentCameraScan) -> Void
        let onCancel: () -> Void
        let onError: (any Error) -> Void

        func makeCoordinator() -> Coordinator { Coordinator(self) }

        func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
            let controller = VNDocumentCameraViewController()
            controller.delegate = context.coordinator
            return controller
        }

        func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {
            context.coordinator.owner = self
        }

        @MainActor
        final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
            var owner: Camera
            init(_ owner: Camera) { self.owner = owner }

            func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
                owner.onScan(scan)
            }
            func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) { owner.onCancel() }
            func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: any Error) {
                owner.onError(error)
            }
        }
    }
}
