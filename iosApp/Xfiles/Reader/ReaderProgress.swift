import Dispatch
import Foundation

@MainActor
final class ReaderProgress {
    private let id: UUID
    private let store: DocumentStore
    private var position: DocumentPosition
    private var revision: UInt64 = 0
    private var savedRevision: UInt64 = 0
    private var checkpoint: Task<Void, Never>?

    init(record: DocumentRecord, store: DocumentStore) {
        self.id = record.id
        self.store = store
        self.position = record.position
    }

    func update(page: Int) {
        guard page >= 0 else { return }
        update { $0.page = page }
    }

    func update(seconds: Double) {
        guard seconds.isFinite, seconds >= 0 else { return }
        update { $0.seconds = seconds }
    }

    func update(textOffset: Double) {
        guard textOffset.isFinite, textOffset >= 0 else { return }
        update { $0.textOffset = textOffset }
    }

    func update(block: Int, section: Int) {
        guard block >= 0, section >= 0 else { return }
        update { $0.block = block; $0.section = section }
    }

    private func update(_ change: (inout DocumentPosition) -> Void) {
        let previous = position
        change(&position)
        guard previous != position else { return }
        revision = DispatchTime.now().uptimeNanoseconds
        if checkpoint == nil {
            checkpoint = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(2)) }
                catch { return }
                guard let self else { return }
                self.checkpoint = nil
                await self.persist()
            }
        }
    }

    func flush() {
        checkpoint?.cancel()
        checkpoint = nil
        Task { await persist() }
    }

    private func persist() async {
        let writing = revision
        guard writing > savedRevision else { return }
        do {
            try await store.savePosition(position, id: id, revision: writing)
            savedRevision = max(savedRevision, writing)
        } catch {
            // A later checkpoint or scene transition can retry the latest position.
        }
    }
}
