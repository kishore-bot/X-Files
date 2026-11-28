import ImageIO
import SwiftUI

struct PhotoReader: View {
    let file: ReaderFile
    let photos: [DocumentRecord]
    let control: ReaderControl
    let onOpen: (ReadDestination) -> Void
    @State private var rotation = 0
    @State private var zoom = 1.0
    @GestureState private var magnification = 1.0
    @State private var owner = UUID()
    @State private var image: CGImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                GeometryReader { geometry in
                    let quarterTurn = rotation % 2 != 0
                    ScrollView([.horizontal, .vertical]) {
                        Image(image, scale: 1, label: Text(file.record.name))
                            .resizable().scaledToFit()
                            .frame(width: (quarterTurn ? geometry.size.height : geometry.size.width) * displayedZoom,
                                   height: (quarterTurn ? geometry.size.width : geometry.size.height) * displayedZoom)
                            .rotationEffect(.degrees(Double(rotation * 90)))
                            .frame(width: geometry.size.width * displayedZoom, height: geometry.size.height * displayedZoom)
                    }
                    .defaultScrollAnchor(.center)
                    .gesture(MagnifyGesture().updating($magnification) { value, state, _ in
                        state = value.magnification
                    }.onEnded { value in
                        zoom = min(4, max(1, zoom * value.magnification))
                    })
                    .simultaneousGesture(TapGesture(count: 2).onEnded { zoom = zoom == 1 ? 2 : 1 })
                }
            } else if failed {
                QuickLookReader(url: file.url).preview
            } else {
                ProgressView("Loading image")
            }
        }
        .task(id: file.record.id) {
            let url = file.url
            let decoded = await Task.detached(priority: .userInitiated) { Self.readImage(url) }.value
            guard !Task.isCancelled else { return }
            image = decoded
            failed = decoded == nil
            guard decoded != nil else { return }
            control.install(owner: owner, supports: [.photos, .rotate, .zoom]) { command in
                switch command {
                case .photo(let delta):
                    guard let index = photos.firstIndex(where: { $0.id == file.record.id }), photos.indices.contains(index + delta) else {
                        return .unavailable("There is no photo in that direction.")
                    }
                    onOpen(ReadDestination(id: photos[index + delta].id))
                case .rotatePhoto: rotation = (rotation + 1) % 4
                case .zoom(let bigger): zoom = min(4, max(1, zoom + (bigger ? 0.5 : -0.5)))
                default: return .unavailable("This photo doesn’t support that action.")
                }
                return .done
            }
        }
        .onDisappear { control.remove(owner: owner) }
    }

    private var displayedZoom: Double { min(4, max(1, zoom * magnification)) }

    private nonisolated static func readImage(_ url: URL) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              CGImageSourceGetCount(source) == 1 else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 4096
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
