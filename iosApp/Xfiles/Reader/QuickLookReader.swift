import QuickLook
import SwiftUI

struct QuickLookReader: UIViewControllerRepresentable {
    let url: URL

    @ViewBuilder
    var preview: some View {
        if QLPreviewController.canPreview(url as NSURL) {
            self
        } else {
            ContentUnavailableView(
                "No preview available",
                systemImage: "doc",
                description: Text("Use Share to open the file in another app.")
            )
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {
        if context.coordinator.url != url {
            context.coordinator.url = url
            controller.reloadData()
        }
    }

    @MainActor
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL
        init(url: URL) { self.url = url }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> any QLPreviewItem {
            url as NSURL
        }
    }
}
