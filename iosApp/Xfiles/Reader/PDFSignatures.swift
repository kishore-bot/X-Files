import SwiftUI

struct PDFSignatures: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var signatures: [Signature]?
    @State private var error: String?

    private struct Signature: Decodable, Sendable {
        let field: String
        let signer: String?
        let date: String?
        let validRange: Bool
        let wholeFile: Bool
    }

    var body: some View {
        NavigationStack {
            Group {
                if let signatures {
                    if signatures.isEmpty {
                        ContentUnavailableView("No signatures found", systemImage: "signature")
                    } else {
                        List {
                            ForEach(Array(signatures.enumerated()), id: \.offset) { _, signature in
                                Section(signature.field) {
                                    if let signer = signature.signer { LabeledContent("Declared signer", value: signer) }
                                    if let date = signature.date { LabeledContent("Declared date", value: date) }
                                    Text(signature.validRange
                                         ? (signature.wholeFile ? "Byte range covers the file." : "The file extends beyond the declared signed revision.")
                                         : "Invalid declared byte range.")
                                }
                            }
                            Text("Signature contents and certificates are unverified.").foregroundStyle(.secondary)
                        }
                    }
                } else if let error {
                    ContentUnavailableView("Signatures unavailable", systemImage: "signature", description: Text(error))
                } else {
                    ProgressView("Reading signatures")
                }
            }
            .navigationTitle("Digital signatures")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task(id: url) {
                do { signatures = try await DocumentEngine.run(EngineRequest(operation: "signatures", path: url.path)) }
                catch is CancellationError { return }
                catch { self.error = error.localizedDescription }
            }
        }
    }
}
