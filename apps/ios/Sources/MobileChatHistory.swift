import Foundation
import Combine

struct MobileChatHistoryPage {
    let items: [MobileChatSummary]
    let next: String
    let supported: Bool
}
struct MobileChatHistoryUpdate: Codable, Equatable {
    let id: String
    let chatID: String
    let expectedHistoryRevision: Int64
    let title: String?
    let archived: Bool?
    init(chat: MobileChatSummary, title: String? = nil, archived: Bool? = nil) {
        id = UUID().uuidString; chatID = chat.id; expectedHistoryRevision = chat.historyRevision ?? 0
        self.title = title; self.archived = archived
    }
    var body: [String: Any] {
        var value: [String: Any] = ["id": id, "expectedHistoryRevision": expectedHistoryRevision]
        if let title { value["title"] = title }
        if let archived { value["archived"] = archived }
        return value
    }
}
struct MobileChatHistoryRejected: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
struct SavedMobileChatHistoryUpdate: Codable { let pending: MobileChatHistoryUpdate? }

@MainActor final class MobileChatHistoryModel: ObservableObject {
    @Published var query = ""
    @Published var archived = false
    @Published private(set) var rows: [MobileChatSummary] = []
    @Published private(set) var supported = false
    @Published private(set) var loading = false
    @Published private(set) var updating = false
    @Published private(set) var pending: MobileChatHistoryUpdate?
    @Published private(set) var error: String?
    @Published private(set) var operationError: String?
    private var next = ""
    private var generation = UUID()
    private var loadedQuery = ""
    private var loadedArchived = false
    private var failedMore = false
    private var fresh = false
    private var liveIDs: Set<String>?
    private let service: any MobileChatService
    private let store: ConversationStore
    private let authFailure: (Error) -> Void
    var onUpdated: ((MobileChatSummary) -> Void)?
    var hasMore: Bool { !next.isEmpty }
    var canUpdate: Bool { supported && fresh && !loading && !updating && pending == nil }

    init(service: any MobileChatService, store: ConversationStore, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.store = store; self.authFailure = authFailure
        do { pending = try store.loadMobileHistoryUpdate() } catch { operationError = ClientError.displayMessage(for: error) }
    }
    func refresh() async {
        let token = UUID(); generation = token
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines), scope = archived
        loading = true; error = nil; failedMore = false; fresh = false
        if needle != loadedQuery || scope != loadedArchived { rows = []; next = "" }
        defer { if generation == token { loading = false } }
        guard needle.utf16.count <= 200 else { error = String(localized: "搜索内容过长，请缩短后重试。"); return }
        do {
            if !needle.isEmpty { try await Task.sleep(for: .milliseconds(250)) }
            let page = try await service.history(archived: scope, query: needle, after: "")
            try Task.checkCancellation(); try store.requireActive()
            guard generation == token else { return }
            try validate(page)
            rows = page.items; next = page.next; supported = page.supported; fresh = true
            if liveIDs == nil && rows.isEmpty && next.isEmpty { liveIDs = [] }
            loadedQuery = needle; loadedArchived = scope
        } catch { if generation == token { report(error) } }
    }
    func more() async {
        guard !loading, hasMore else { return }
        let token = generation, cursor = next
        loading = true; error = nil
        defer { if generation == token { loading = false } }
        do {
            let page = try await service.history(archived: loadedArchived, query: loadedQuery, after: cursor)
            try Task.checkCancellation(); try store.requireActive()
            guard generation == token else { return }
            try validate(page)
            guard page.next.isEmpty || page.next != cursor else { throw ClientError.invalidResponse }
            let known = Set(rows.map(\.id)); rows += page.items.filter { !known.contains($0.id) }
            next = page.next; fresh = true
        } catch { if generation == token { failedMore = true; report(error) } }
    }
    func retry() async { if failedMore { await more() } else { await refresh() } }
    func receive(_ summaries: [MobileChatSummary]) {
        do { try store.requireActive() } catch { return }
        let incoming = Dictionary(summaries.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let added = liveIDs.map { Set(incoming.keys).subtracting($0) } ?? []
        liveIDs = Set(incoming.keys)
        guard loadedQuery == query.trimmingCharacters(in: .whitespacesAndNewlines), loadedArchived == archived else { return }
        rows = rows.compactMap { old in
            guard let update = incoming[old.id] else { return old }
            guard (update.historyRevision ?? -1) >= (old.historyRevision ?? -1) else { return old }
            if (update.archived == true) != archived { return nil }
            if update.revision < old.revision {
                var preserved = old; preserved.title = update.title; preserved.archived = update.archived; preserved.historyRevision = update.historyRevision
                return preserved
            }
            var merged = update
            if merged.updatedAt == nil { merged.updatedAt = old.updatedAt }
            if merged.searchMatch == nil { merged.searchMatch = old.searchMatch }
            return merged
        }
        if query.isEmpty && !archived {
            let known = Set(rows.map(\.id))
            rows += summaries.filter { added.contains($0.id) && !known.contains($0.id) && $0.archived != true }
        }
    }
    private func validate(_ page: MobileChatHistoryPage) throws {
        guard Set(page.items.map(\.id)).count == page.items.count,
              !page.supported || page.items.allSatisfy({ $0.archived == archived && $0.historyRevision != nil }) else { throw ClientError.invalidResponse }
    }
    func deactivate() { generation = UUID(); loading = false; fresh = false }

    func change(_ chat: MobileChatSummary, title: String? = nil, archived: Bool? = nil) async -> Bool {
        guard canUpdate, chat.historyRevision != nil, title != nil || archived != nil else { return false }
        let title = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard title == nil || !title!.isEmpty && title!.utf16.count <= 160 else { return false }
        let request = MobileChatHistoryUpdate(chat: chat, title: title, archived: archived)
        do { try store.saveMobileHistoryUpdate(request); pending = request }
        catch { operationError = ClientError.displayMessage(for: error); return false }
        return await reconcile()
    }
    @discardableResult func reconcile() async -> Bool {
        guard !updating, let request = pending else { return false }
        updating = true; operationError = nil
        defer { updating = false }
        do {
            let chat = try await service.updateHistory(request)
            try store.requireActive()
            guard pending?.id == request.id else { return false }
            try store.saveMobileHistoryUpdate(nil); pending = nil
            onUpdated?(chat)
            // A replayed receipt describes the original change; another device may have changed metadata since then.
            if let latest = await current(request.chatID) { onUpdated?(latest) }
            // A successful metadata change is not changed into failure by a later list-refresh error.
            await refresh()
            return true
        } catch {
            if error is MobileChatHistoryRejected {
                do { try store.saveMobileHistoryUpdate(nil); pending = nil }
                catch { operationError = ClientError.displayMessage(for: error); return false }
                await refresh()
            }
            operationError = (error as? MobileChatHistoryRejected)?.message ?? ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
            return false
        }
    }
    func current(_ chatID: String) async -> MobileChatSummary? {
        do { let value = try await service.historyMetadata(chatID); try store.requireActive(); return value }
        catch { operationError = ClientError.displayMessage(for: error); if error is AuthenticationError { authFailure(error) }; return nil }
    }
    private func report(_ failure: Error) {
        guard !(failure is CancellationError), (failure as? URLError)?.code != .cancelled else { return }
        fresh = false; error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}
