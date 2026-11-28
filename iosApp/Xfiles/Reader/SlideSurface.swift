import SwiftUI

struct SlideSurface: View {
    let slide: EngineDocument.Node
    let items: [LocatedNode]
    let file: ReaderFile
    let password: String?
    let onSlideLink: (String) -> Void
    @Environment(\.openURL) private var openURL
    @State private var embeddedMedia: MediaPart?

    var body: some View {
        let aspect = max(0.2, min(5, (slide.width ?? 16) / max(1, slide.height ?? 9)))
        GeometryReader { geometry in
            let width = min(geometry.size.width, geometry.size.height * aspect)
            let height = width / aspect
            ZStack(alignment: .topLeading) {
                argb(slide.background) ?? Color.white
                ForEach(items.filter { $0.node.kind == "shape" }) { item in
                    let node = item.node
                    let scale = width / max(1, slide.width ?? 16)
                    let shapeWidth = max(1, (node.width ?? 0) * scale)
                    let shapeHeight = max(1, (node.height ?? 0) * scale)
                    slideItem(node)
                        .frame(width: shapeWidth, height: shapeHeight)
                        .clipped()
                        .rotationEffect(.degrees(node.rotation ?? 0))
                        .position(
                            x: (node.x ?? 0) * scale + shapeWidth / 2,
                            y: (node.y ?? 0) * scale + shapeHeight / 2
                        )
                }
            }
            .frame(width: width, height: height)
            .clipped()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(.black)
        .sheet(item: $embeddedMedia) { part in
            EmbeddedSlideMedia(part: part.id, file: file, password: password)
        }
    }

    @ViewBuilder
    private func slideItem(_ node: EngineDocument.Node) -> some View {
        let description = node.text?.isEmpty == false ? node.text ?? "" : (node.cells ?? []).joined(separator: "\n")
        ZStack {
            argb(node.background) ?? Color.clear
            if let name = node.resource {
                DocumentPicture(resource: name, label: node.text, file: file, password: password)
            }
            if !description.isEmpty {
                Text(description).font(.caption).foregroundStyle(.black).padding(3)
            }
            if let link = node.media ?? node.link {
                Button {
                    if node.media != nil && node.mediaExternal != true {
                        embeddedMedia = MediaPart(id: link)
                    } else if link.hasPrefix("slide:") {
                        onSlideLink(link)
                    } else if let url = URL(string: link),
                              let scheme = url.scheme?.lowercased(),
                              ["https", "http", "mailto"].contains(scheme) {
                        openURL(url)
                    }
                } label: {
                    if node.media != nil {
                        Image(systemName: "play.circle.fill").font(.title2)
                    } else {
                        Color.clear.contentShape(Rectangle())
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(node.media != nil ? "Play slide media" : "Open slide link")
            }
        }
    }

    private func argb(_ value: UInt32?) -> Color? {
        guard let value else { return nil }
        return Color(
            red: Double((value >> 16) & 255) / 255,
            green: Double((value >> 8) & 255) / 255,
            blue: Double(value & 255) / 255,
            opacity: Double(value >> 24) / 255
        )
    }

    private struct MediaPart: Identifiable { let id: String }
}

private struct EmbeddedSlideMedia: View {
    let part: String
    let file: ReaderFile
    let password: String?
    @State private var resource: URL?
    @State private var folder: URL?
    @State private var problem: String?

    var body: some View {
        Group {
            if let resource {
                MediaReader(url: resource, seconds: 0, onTime: { _ in }, onCheckpoint: {})
            } else if let problem {
                ContentUnavailableView("Media unavailable", systemImage: "play.slash", description: Text(problem))
            } else {
                ProgressView("Opening slide media")
            }
        }
        .task(id: part) {
            do {
                let staging = try OutputFiles.folder()
                defer { if folder != staging { OutputFiles.remove(staging) } }
                let output = OutputFiles.resource(in: staging, name: part)
                _ = try await DocumentEngine.output(EngineRequest(operation: "resource", path: file.url.path, output: output.path, password: password, resource: part))
                try Task.checkCancellation()
                // Switching to another media part on the same slide replaces the earlier copy; remove it.
                if let previous = folder, previous != staging { OutputFiles.remove(previous) }
                folder = staging
                resource = output
            } catch is CancellationError {
                return
            } catch {
                problem = error.localizedDescription
            }
        }
        .onDisappear {
            if let folder { OutputFiles.remove(folder) }
            folder = nil
            resource = nil
        }
    }
}
