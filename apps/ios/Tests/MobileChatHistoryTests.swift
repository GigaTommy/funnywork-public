import XCTest
@testable import FunnyWork

@MainActor private final class HistoryService: MobileChatService {
    var rows: [MobileChatSummary] = []
    var nextFailure = false
    var receiptLost = false
    var rejected = false
    var firstDelay = false
    var refreshFailure = false
    var calls: [String] = []
    var updates: [MobileChatHistoryUpdate] = []
    var receipts: [String: MobileChatSummary] = [:]
    func models() async throws -> [ChatModelOption] { [ChatModelOption(id: "qwen", name: "Qwen")] }
    func list() async throws -> [MobileChatSummary] { rows }
    func snapshot(_ id: String) async throws -> MobileChatSnapshot? {
        guard let chat = rows.first(where: { $0.id == id }) else { return nil }
        return try MobileChatSnapshot(["chat": ["id": chat.id, "title": chat.title, "modelId": chat.modelID, "revision": chat.revision, "archived": chat.archived ?? false, "history_revision": chat.historyRevision ?? 0], "turns": [], "ticket": [:]])
    }
    func prepare(_ send: MobileChatSend) async throws { throw ClientError.invalidResponse }
    func execute(_ send: MobileChatSend, delta: @escaping (String) -> Void) async throws { throw ClientError.invalidResponse }
    func cancel(_ send: MobileChatSend) async throws { }
    func history(archived: Bool, query: String, after: String) async throws -> MobileChatHistoryPage {
        calls.append(after)
        if refreshFailure { throw URLError(.timedOut) }
        let captured = rows.filter { $0.archived == archived && (query.isEmpty || $0.title.contains(query)) }
        if firstDelay && query == "Old" { try? await Task.sleep(for: .milliseconds(300)) }
        if nextFailure && !after.isEmpty { nextFailure = false; throw URLError(.timedOut) }
        let start = Int(after) ?? 0, end = min(start + 2, captured.count)
        return MobileChatHistoryPage(items: Array(captured[start..<end]), next: end < captured.count ? String(end) : "", supported: true)
    }
    func updateHistory(_ update: MobileChatHistoryUpdate) async throws -> MobileChatSummary {
        updates.append(update)
        if let old = receipts[update.id] { return old }
        if rejected { throw MobileChatHistoryRejected(message: "Rejected") }
        guard let index = rows.firstIndex(where: { $0.id == update.chatID }), rows[index].historyRevision == update.expectedHistoryRevision else { throw MobileChatHistoryRejected(message: "Conflict") }
        if let title = update.title { rows[index].title = title }
        if let archived = update.archived { rows[index].archived = archived }
        rows[index].historyRevision = update.expectedHistoryRevision + 1
        let result = rows[index]; receipts[update.id] = result
        if receiptLost { receiptLost = false; throw URLError(.networkConnectionLost) }
        return result
    }
}

@MainActor final class MobileChatHistoryTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func store(_ key: String = "one") -> ConversationStore { ConversationStore(key: key, root: root) }
    func chat(_ id: String, _ title: String = "Chat") throws -> MobileChatSummary {
        try MobileChatSummary(["id": id, "title": title, "modelId": "qwen", "revision": 5, "history_revision": 0, "archived": false, "updated_at": 1000])
    }
    func testSearchReferenceSurvivesLiveSummaryRefresh() async throws {
        let service = HistoryService(); var value = try chat("chat", "needle title")
        value.searchMatch = try MobileChatSearchMatch(["field": "reply", "turnId": "turn", "excerpt": "Found needle here", "created_at": 1000])
        service.rows = [value]; let model = MobileChatHistoryModel(service: service, store: store()); model.query = "needle"; await model.refresh()
        var live = value; live.searchMatch = nil; model.receive([live])
        XCTAssertEqual(model.rows.first?.searchMatch, value.searchMatch)
    }
    func testPaginationFailureKeepsRowsAndRetriesTheSameCursor() async throws {
        let service = HistoryService(); service.rows = try (0..<5).map { try chat("chat-\($0)") }
        let model = MobileChatHistoryModel(service: service, store: store()); await model.refresh()
        XCTAssertEqual(model.rows.count, 2); XCTAssertTrue(model.hasMore)
        service.nextFailure = true; await model.more()
        XCTAssertEqual(model.rows.count, 2); XCTAssertNotNil(model.error)
        await model.retry(); await model.more()
        XCTAssertEqual(model.rows.map(\.id), service.rows.map(\.id)); XCTAssertFalse(model.hasMore)
        XCTAssertEqual(service.calls, ["", "2", "2", "4"])
    }
    func testLiveUpdatesKeepLoadedPagesAndDoNotInjectNonmatchingSearchResults() async throws {
        let service = HistoryService(); service.rows = try (0..<5).map { try chat("chat-\($0)", "Matching") }
        let model = MobileChatHistoryModel(service: service, store: store()); await model.refresh()
        model.receive(service.rows); await model.more()
        XCTAssertEqual(model.rows.count, 4)
        var changed = service.rows; changed[0].title = "Updated"; changed[0].historyRevision = 1; changed[0].updatedAt = nil
        let newChat = try chat("new", "New"); model.receive(changed + [newChat])
        XCTAssertEqual(model.rows.count, 5); XCTAssertEqual(model.rows.first?.title, "Updated")
        XCTAssertEqual(model.rows.first?.updatedAt?.timeIntervalSince1970, 1)
        model.query = "Matching"; await model.refresh()
        model.receive(changed + [newChat, try chat("unrelated", "Unrelated")])
        XCTAssertFalse(model.rows.contains(where: { $0.id == "unrelated" }))
    }
    func testFirstCompletedChatAppearsAfterInitiallyEmptyHistory() async throws {
        let service = HistoryService(); let model = MobileChatHistoryModel(service: service, store: store()); await model.refresh()
        model.receive([try chat("first")]); XCTAssertEqual(model.rows.map(\.id), ["first"])
    }
    func testLateSearchCannotOverwriteNewQueryOrArchivedScope() async throws {
        let service = HistoryService(); service.rows = try [chat("old", "Old"), chat("new", "New")]; service.firstDelay = true
        let model = MobileChatHistoryModel(service: service, store: store()); model.query = "Old"
        let old = Task { await model.refresh() }; try await Task.sleep(for: .milliseconds(280))
        model.query = "New"; await model.refresh(); await old.value
        XCTAssertEqual(model.rows.map(\.id), ["new"])
        model.archived = true; await model.refresh(); XCTAssertTrue(model.rows.isEmpty)
    }
    func testLostMetadataReceiptPersistsAndRelaunchReusesOriginalRequest() async throws {
        let service = HistoryService(); service.rows = try [chat("chat")]; let cache = store()
        let model = MobileChatHistoryModel(service: service, store: cache); await model.refresh(); service.receiptLost = true
        let before = try XCTUnwrap(model.rows.first)
        let changed = await model.change(before, title: "Renamed"); XCTAssertFalse(changed)
        let original = try XCTUnwrap(model.pending); XCTAssertEqual(service.rows[0].title, "Renamed")
        XCTAssertEqual(try cache.loadMobileHistoryUpdate(), original)
        XCTAssertNil(try store("other").loadMobileHistoryUpdate())
        let restored = MobileChatHistoryModel(service: service, store: cache)
        let confirmed = await restored.reconcile(); XCTAssertTrue(confirmed); XCTAssertNil(restored.pending)
        XCTAssertEqual(service.updates.map(\.id), [original.id, original.id]); XCTAssertEqual(service.rows[0].historyRevision, 1)
        XCTAssertNil(try cache.loadMobileHistoryUpdate())
        XCTAssertNil(MobileChatHistoryModel(service: service, store: cache).operationError)
    }
    func testConfirmedChangeSucceedsEvenWhenDirectoryRefreshFails() async throws {
        let service = HistoryService(); service.rows = try [chat("chat")]
        let model = MobileChatHistoryModel(service: service, store: store()); await model.refresh()
        var applied: MobileChatSummary?; model.onUpdated = { applied = $0 }; service.refreshFailure = true
        let changed = await model.change(service.rows[0], archived: true); XCTAssertTrue(changed)
        XCTAssertNil(model.pending); XCTAssertTrue(applied?.archived == true); XCTAssertNotNil(model.error)
    }
    func testReplayedReceiptUsesLatestMetadataFromAnotherDevice() async throws {
        let service = HistoryService(); service.rows = try [chat("chat")]; let cache = store()
        let model = MobileChatHistoryModel(service: service, store: cache); await model.refresh(); service.receiptLost = true
        let uncertain = await model.change(service.rows[0], title: "Original change"); XCTAssertFalse(uncertain)
        service.rows[0].title = "Changed elsewhere"; service.rows[0].archived = true; service.rows[0].historyRevision = 2
        let restored = MobileChatHistoryModel(service: service, store: cache)
        var applied: MobileChatSummary?; restored.onUpdated = { applied = $0 }
        let confirmed = await restored.reconcile(); XCTAssertTrue(confirmed)
        XCTAssertEqual(applied?.title, "Changed elsewhere"); XCTAssertEqual(applied?.historyRevision, 2)
        XCTAssertTrue(applied?.archived == true); XCTAssertTrue(restored.rows.isEmpty)
    }
    func testRejectedUpdateClearsPendingAndInvalidAccountNeverPublishes() async throws {
        let service = HistoryService(); service.rows = try [chat("chat")]; let cache = store()
        let model = MobileChatHistoryModel(service: service, store: cache); await model.refresh(); service.rejected = true
        let refused = await model.change(service.rows[0], title: "No"); XCTAssertFalse(refused); XCTAssertNil(model.pending); XCTAssertEqual(model.operationError, "Rejected")
        service.rejected = false; service.receiptLost = true
        let uncertain = await model.change(service.rows[0], title: "Maybe"); XCTAssertFalse(uncertain)
        var applied = false; model.onUpdated = { _ in applied = true }; cache.invalidate()
        let confirmed = await model.reconcile(); XCTAssertFalse(confirmed); XCTAssertFalse(applied); XCTAssertNotNil(model.pending)
    }
    func testMetadataDoesNotRollBackInferenceRevisionAndArchivedDraftIsPreserved() async throws {
        let service = HistoryService(); service.rows = try [chat("chat")]; let model = MobileChatModel(service: service, store: store())
        await model.refresh(); await model.open(service.rows[0]); model.updateDraft("Keep draft")
        var stale = try MobileChatSummary(["id": "chat", "title": "Renamed", "modelId": "qwen", "revision": 1, "history_revision": 1, "archived": true])
        model.applyHistoryUpdate(stale)
        XCTAssertEqual(model.snapshot?.chat.revision, 5); XCTAssertEqual(model.snapshot?.chat.title, "Renamed")
        XCTAssertEqual(model.chats.first?.updatedAt?.timeIntervalSince1970, 1)
        XCTAssertEqual(model.draft, "Keep draft"); XCTAssertFalse(model.canSend)
        stale.archived = false; stale.historyRevision = 2; model.applyHistoryUpdate(stale)
        XCTAssertEqual(model.snapshot?.chat.revision, 5); XCTAssertTrue(model.canSend)
        // A later inference refresh carrying older metadata must not undo the confirmed rename/restore.
        await model.refresh()
        XCTAssertEqual(model.snapshot?.chat.title, "Renamed"); XCTAssertEqual(model.snapshot?.chat.historyRevision, 2)
        XCTAssertFalse(model.snapshot?.chat.archived == true)
    }
    func testLegacySummaryAndPendingMigrationRemainReadable() throws {
        let legacy = try JSONDecoder().decode(MobileChatSummary.self, from: Data(#"{"id":"chat","title":"Chat","modelID":"qwen","revision":0}"#.utf8))
        XCTAssertNil(legacy.archived); XCTAssertNil(legacy.historyRevision)
        XCTAssertThrowsError(try MobileChatSummary(["id": "chat", "title": "Chat", "modelId": "qwen", "revision": 0, "archived": 1]))
    }
    func testMetadataWithNewerInferenceRevisionRequiresSnapshotRefreshBeforeSending() async throws {
        let service = HistoryService(); service.rows = try [chat("chat")]
        let model = MobileChatModel(service: service, store: store()); await model.refresh(); await model.open(service.rows[0]); model.updateDraft("Next")
        let newer = try MobileChatSummary(["id": "chat", "title": "New", "modelId": "qwen", "revision": 7, "history_revision": 1, "archived": false])
        model.applyHistoryUpdate(newer)
        XCTAssertEqual(model.snapshot?.chat.revision, 5); XCTAssertFalse(model.canSend)
        service.rows = [newer]; await model.refresh()
        XCTAssertEqual(model.snapshot?.chat.revision, 7); XCTAssertTrue(model.canSend)
    }
}
