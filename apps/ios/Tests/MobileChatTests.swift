import XCTest
@testable import FunnyWork

@MainActor private final class MultiChatService: MobileChatService {
    var children: [String: MobileChatFixtureService] = [:]
    func models() async throws -> [ChatModelOption] { try await MobileChatFixtureService().models() }
    func list() async throws -> [MobileChatSummary] {
        var result: [MobileChatSummary] = []
        for child in children.values { result += try await child.list() }
        return result
    }
    func snapshot(_ id: String) async throws -> MobileChatSnapshot? { try await children[id]?.snapshot(id) }
    func prepare(_ send: MobileChatSend) async throws {
        let child = children[send.chatID] ?? MobileChatFixtureService(); child.waiting = true; children[send.chatID] = child
        try await child.prepare(send)
    }
    func execute(_ send: MobileChatSend, delta: @escaping (String) -> Void) async throws {
        guard let child = children[send.chatID] else { throw ClientError.invalidResponse }
        try await child.execute(send, delta: delta)
    }
    func cancel(_ send: MobileChatSend) async throws { try await children[send.chatID]?.cancel(send) }
}

@MainActor final class MobileChatTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func store(_ key: String = "a") -> ConversationStore { ConversationStore(key: key, root: root) }
    func testThinkingChoicePersistsPerModelAndIsFrozenInPendingSend() async throws {
        let service = MobileChatFixtureService(); service.waiting = true
        service.modelOptions = [ChatModelOption(id: "flash", name: "Flash", reasoningEfforts: ["off", "low", "high", "max"]), ChatModelOption(id: "plain", name: "Plain")]
        let cache = store(); let model = MobileChatModel(service: service, store: cache); await model.refresh()
        XCTAssertEqual(model.reasoningEffort, "off")
        model.selectReasoningEffort("max"); model.selectModel("plain"); model.selectReasoningEffort("high")
        XCTAssertNil(model.saved.reasoningEfforts["plain"])
        model.selectModel("flash"); XCTAssertEqual(model.reasoningEffort, "max")
        model.updateDraft("Test thinking"); model.send()
        try await wait { service.request != nil }
        XCTAssertEqual(service.request?.body["reasoningEffort"] as? String, "max")
        XCTAssertEqual(try cache.loadMobileChat()?.pending?.reasoningEffort, "max")
        model.selectReasoningEffort("off"); XCTAssertEqual(model.reasoningEffort, "max")
        await model.stop(); model.deactivate()
        let old = MobileChatSend(chatID: "c", turnID: "t", text: "Old", modelID: "plain", revision: 0)
        XCTAssertNil(old.body["reasoningEffort"]); XCTAssertNil(old.body["maxOutputTokens"])
        var bounded = old; bounded.maxOutputTokens = 128; XCTAssertEqual(bounded.body["maxOutputTokens"] as? Int, 128)
        XCTAssertNil(try JSONDecoder().decode(MobileChatSend.self, from: JSONEncoder().encode(old)).reasoningEffort)
    }
    func testHistorySortsByUpdatedTimeWithStableLegacyFallbackAndRunningPriority() async throws {
        let service = MobileChatFixtureService()
        func chat(_ id: String, _ time: Any? = nil) throws -> MobileChatSummary {
            var row: [String: Any] = ["id": id, "title": id, "modelId": "qwen", "revision": 1]
            row["updated_at"] = time
            return try MobileChatSummary(row)
        }
        service.listedChats = try [chat("a-old", 1000), chat("z-new", 2000), chat("b-legacy"), chat("a-legacy")]
        let cache = store(); var saved = SavedMobileChat()
        saved.pendingChats["a-old"] = MobileChatSend(chatID: "a-old", turnID: "turn", text: "Keep", modelID: "qwen", revision: 0, executionRequested: true)
        try cache.saveMobileChat(saved)
        let model = MobileChatModel(service: service, store: cache); await model.refresh()
        XCTAssertEqual(model.navigationChats.map(\.id), ["a-old", "z-new", "a-legacy", "b-legacy"])
        XCTAssertEqual(model.navigationGroups().first?.id, .running)
        XCTAssertNil(try chat("invalid", true).updatedAt)
        XCTAssertNil(try chat("invalid", Double.infinity).updatedAt)
        let decoded = try JSONDecoder().decode(MobileChatSummary.self, from: Data(#"{"id":"legacy","title":"Legacy","modelID":"qwen","revision":0}"#.utf8))
        XCTAssertNil(decoded.updatedAt)
        XCTAssertEqual(try JSONDecoder().decode(MobileChatSummary.self, from: JSONEncoder().encode(chat("dated", 2000))).updatedAt?.timeIntervalSince1970, 2)
        model.deactivate()
    }
    func testReadingPositionsPersistPerChatAndRespectAccountInvalidation() throws {
        let cache = store(); let model = MobileChatModel(service: MobileChatFixtureService(), store: cache)
        model.saveReadingPosition("turn-a", for: "chat-a"); model.saveReadingPosition("turn-b", for: "chat-b")
        let restored = MobileChatModel(service: MobileChatFixtureService(), store: cache)
        XCTAssertEqual(restored.saved.readingPositions, ["chat-a": "turn-a", "chat-b": "turn-b"])
        XCTAssertNil(try store("other").loadMobileChat())
        restored.saveReadingPosition(nil, for: "chat-a")
        XCTAssertNil(try cache.loadMobileChat()?.readingPositions["chat-a"])
        cache.invalidate(); restored.saveReadingPosition("late", for: "chat-b")
        XCTAssertEqual(restored.saved.readingPositions["chat-b"], "turn-b")
    }
    func testRunningDraftSurvivesStopAndCanBeSentAfterTerminalState() async throws {
        let service = MobileChatFixtureService(); service.waiting = true
        let model = MobileChatModel(service: service, store: store()); await model.refresh()
        model.updateDraft("Original"); model.send()
        try await wait { model.status == String(localized: "正在等待模型容量…") }
        model.updateDraft("Next draft"); XCTAssertFalse(model.canSend)
        await model.stop(); try await wait { !model.busy }
        XCTAssertEqual(model.draft, "Next draft"); XCTAssertTrue(model.canSend)
        XCTAssertEqual(service.executions, 0)
        model.deactivate()
    }
    func testConcurrentWaitingChatsStopIndependentlyAndPersistRecovery() async throws {
        let service = MultiChatService(); let cache = store()
        let model = MobileChatModel(service: service, store: cache); await model.refresh()
        model.updateDraft("First"); model.send()
        try await wait { service.children.count == 1 && model.status == String(localized: "正在等待模型容量…") }
        let first = try XCTUnwrap(model.saved.selected)
        model.newChat(); model.updateDraft("Second"); XCTAssertTrue(model.canSend); model.send()
        try await wait { service.children.count == 2 && model.status == String(localized: "正在等待模型容量…") }
        let second = try XCTUnwrap(model.saved.selected)
        XCTAssertNotEqual(first, second); XCTAssertEqual(try cache.loadMobileChat()?.pendingChats.count, 2)
        await model.stop(); try await wait { model.saved.pendingChats[second] == nil }
        XCTAssertNotNil(model.saved.pendingChats[first]); XCTAssertEqual(service.children[first]?.state, "WAITING")
        model.deactivate()
        let restored = MobileChatModel(service: service, store: cache); await restored.refresh()
        XCTAssertNotNil(restored.saved.pendingChats[first])
        await restored.openRunningChat(); await restored.stop(); try await wait { !restored.busy }
        XCTAssertEqual(service.children.values.reduce(0) { $0 + $1.executions }, 0)
        restored.deactivate()
    }
    func testLegacySinglePendingMigratesIntoPerChatRegistry() throws {
        let request = MobileChatSend(chatID: "legacy", turnID: "turn", text: "Keep", modelID: "qwen", revision: 0, executionRequested: true)
        let requestJSON = try JSONSerialization.jsonObject(with: JSONEncoder().encode(request))
        let data = try JSONSerialization.data(withJSONObject: ["drafts": [:], "modelID": "qwen", "pending": requestJSON])
        let migrated = try JSONDecoder().decode(SavedMobileChat.self, from: data)
        XCTAssertEqual(migrated.pendingChats["legacy"]?.turnID, "turn")
        XCTAssertTrue(migrated.pendingChats["legacy"]?.executionRequested == true)
        XCTAssertEqual(try JSONDecoder().decode(SavedMobileChat.self, from: JSONEncoder().encode(migrated)).pendingChats.count, 1)
    }
    func testCachedChatAppearsBeforeNetworkButCannotSendUntilConfirmed() async throws {
        let service = MobileChatFixtureService()
        service.request = MobileChatSend(chatID: "cached", turnID: "turn", text: "Hello", modelID: "qwen", revision: 0)
        service.state = "SUCCEEDED"; service.reply = "Saved reply"
        let model = MobileChatModel(service: service, store: store())
        await model.refresh()
        let chat = try XCTUnwrap(model.chats.first)
        await model.open(chat); model.updateDraft("Keep draft"); model.newChat()
        service.snapshotDelay = .milliseconds(150)
        let opening = Task { await model.open(chat) }
        for _ in 0..<10 {
            if model.saved.selected == chat.id { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertEqual(model.snapshot?.turns.last?.reply, "Saved reply")
        XCTAssertEqual(model.draft, "Keep draft"); XCTAssertFalse(model.canSend)
        await opening.value
        XCTAssertTrue(model.canSend)
    }
    func testWaitingReplyDoesNotStealNewChatOrEraseItsDraft() async throws {
        let service = MobileChatFixtureService(); service.waiting = true
        let model = MobileChatModel(service: service, store: store())
        await model.refresh(); model.updateDraft("First task"); model.send()
        try await wait { model.activeBusy && model.status == String(localized: "正在等待模型容量…") }
        let runningID = model.saved.pending?.chatID
        model.newChat(); model.updateDraft("Next draft")
        XCTAssertNil(model.saved.selected); XCTAssertFalse(model.activeBusy)
        XCTAssertNil(model.status); XCTAssertTrue(model.canSend)
        try await Task.sleep(for: .milliseconds(1200))
        XCTAssertNil(model.saved.selected); XCTAssertEqual(model.draft, "Next draft")
        XCTAssertEqual(model.saved.pending?.chatID, runningID)
        model.deactivate()
    }
    func testNetworkRefreshPreservesDraftAndSeparatesConnectionStatus() async throws {
        let service = MobileChatFixtureService(); let model = MobileChatModel(service: service, store: store())
        model.updateDraft("Keep my draft"); service.networkFailure = true
        await model.refresh()
        XCTAssertNotNil(model.connectionIssue); XCTAssertNil(model.error)
        XCTAssertEqual(model.draft, "Keep my draft"); XCTAssertFalse(model.canSend)
        service.networkFailure = false; await model.refresh()
        XCTAssertNil(model.connectionIssue); XCTAssertTrue(model.canSend)
    }
    func testHomeSendCreatesStandaloneConversationAndSavesReply() async throws {
        let service = MobileChatFixtureService(); let cache = store(); let model = MobileChatModel(service: service, store: cache)
        await model.refresh(); model.updateDraft("Hello"); XCTAssertTrue(model.canSend); model.send()
        try await wait { !model.busy && model.snapshot?.turns.last?.reply.isEmpty == false }
        XCTAssertEqual(service.executions, 1); XCTAssertEqual(service.request?.text, "Hello")
        XCTAssertTrue(model.draft.isEmpty); XCTAssertEqual(try cache.loadMobileChat()?.selected, service.request?.chatID)
        XCTAssertNil(try store("other").loadMobileChat())
    }
    func testWaitingCanStopWithoutExecuting() async throws {
        let service = MobileChatFixtureService(); service.waiting = true
        let model = MobileChatModel(service: service, store: store()); await model.refresh(); model.updateDraft("Keep me"); model.send()
        try await wait { model.status == String(localized: "正在等待模型容量…") }
        await model.stop(); try await wait { !model.busy }
        XCTAssertEqual(service.executions, 0); XCTAssertEqual(model.draft, "Keep me")
    }
    func testRecoveryNeverExecutesAnAmbiguousAttemptAgain() async throws {
        let service = MobileChatFixtureService(); let cache = store()
        var pending = MobileChatSend(chatID: "chat", turnID: "turn", text: "Keep", modelID: "qwen", revision: 0)
        pending.executionRequested = true; service.request = pending; service.state = "SUCCEEDED"; service.reply = "Recovered"
        var saved = SavedMobileChat(); saved.pending = pending; saved.modelID = "qwen"; try cache.saveMobileChat(saved)
        let model = MobileChatModel(service: service, store: cache); await model.refresh(); try await wait { !model.busy }
        XCTAssertEqual(service.executions, 0); XCTAssertEqual(model.snapshot?.turns.last?.reply, "Recovered")
    }
    func testUnknownResultRemainsUnresolvedAndDoesNotResend() async throws {
        let service = MobileChatFixtureService(); let cache = store()
        let pending = MobileChatSend(chatID: "chat", turnID: "turn", text: "Unknown", modelID: "qwen", revision: 0, executionRequested: true)
        service.request = pending; service.state = "UNKNOWN"
        var saved = SavedMobileChat(); saved.pending = pending; saved.modelID = "qwen"; try cache.saveMobileChat(saved)
        let model = MobileChatModel(service: service, store: cache); await model.refresh(); try await wait { model.status == String(localized: "结果待确认") }
        XCTAssertTrue(model.busy); XCTAssertFalse(model.canSend); XCTAssertEqual(service.executions, 0); model.deactivate()
        XCTAssertEqual(model.navigationGroups().first?.id, .attention)
    }
    func testHomeDraftSurvivesRelaunchAndNoModelsDoNotEraseText() async throws {
        let service = MobileChatFixtureService(); service.noModels = true; let cache = store()
        let model = MobileChatModel(service: service, store: cache); model.updateDraft("Draft before configuration")
        await model.refresh(); XCTAssertFalse(model.canSend); XCTAssertNotNil(model.error)
        let restored = MobileChatModel(service: service, store: cache); XCTAssertEqual(restored.draft, "Draft before configuration")
        cache.invalidate(); restored.updateDraft("Late write"); XCTAssertEqual(restored.draft, "Draft before configuration")
    }
    func testInvalidRevisionAndOversizeDraftDoNotStartARequest() async throws {
        XCTAssertThrowsError(try MobileChatSummary(["id": "chat", "title": "Title", "modelId": "qwen", "revision": true]))
        let service = MobileChatFixtureService(); let model = MobileChatModel(service: service, store: store()); await model.refresh()
        model.updateDraft(String(repeating: "a", count: 16001)); XCTAssertFalse(model.canSend); model.send(); XCTAssertNil(service.request)
    }
    func testFailedPreparationCanReturnToEditingWithoutLosingDraft() async throws {
        let service = MobileChatFixtureService(); service.failPrepare = true
        let model = MobileChatModel(service: service, store: store()); await model.refresh(); model.updateDraft("Editable")
        model.send(); try await wait { model.error != nil }
        XCTAssertTrue(model.busy); await model.editUnsent()
        XCTAssertFalse(model.busy); XCTAssertEqual(model.draft, "Editable"); XCTAssertTrue(model.canSend); XCTAssertEqual(service.executions, 0)
    }
    func testRecoveredReplySaveFailureUnblocksChatAndRestoresDraftWithoutExecuting() async throws {
        let service = MobileChatFixtureService(); let cache = store()
        let pending = MobileChatSend(chatID: "chat", turnID: "turn", text: "Keep my message", modelID: "qwen", revision: 0, executionRequested: true)
        service.request = pending; service.state = "SUCCEEDED"; service.replyState = "FAILED"
        var saved = SavedMobileChat(); saved.pending = pending; saved.modelID = "qwen"; try cache.saveMobileChat(saved)
        let model = MobileChatModel(service: service, store: cache); await model.refresh(); try await wait { !model.busy }
        XCTAssertEqual(service.executions, 0); XCTAssertEqual(model.snapshot?.turns.last?.state, "FAILED")
        XCTAssertEqual(model.draft, "Keep my message"); XCTAssertNotNil(model.error); XCTAssertTrue(model.canSend)
    }
    func testUsageIncludesCachedInputOnceAndUnknownDoesNotBecomeZero() throws {
        let row: [String: Any] = ["usage_quality": "COMPLETE", "input_tokens": 10, "output_tokens": 5, "cached_tokens": 3]
        let usage = try XCTUnwrap(MobileChatUsage(row)); XCTAssertEqual(usage.total, 15); XCTAssertEqual(usage.cached, 3)
        var missing = row; missing["usage_quality"] = "UNKNOWN"; XCTAssertNil(MobileChatUsage(missing))
        missing = row; missing["input_tokens"] = true; XCTAssertNil(MobileChatUsage(missing))
        missing = row; missing["cached_tokens"] = 11; XCTAssertNil(MobileChatUsage(missing))
    }
    func testWeightedCacheRateAndPartialCoverage() throws {
        func usage(_ input: Int, _ cached: Int?) throws -> MobileChatUsage {
            var row: [String: Any] = ["usage_quality": "COMPLETE", "input_tokens": input, "output_tokens": 5]
            if let cached { row["cached_tokens"] = cached }
            return try XCTUnwrap(MobileChatUsage(row))
        }
        var summary = ChatUsageSummary(); summary.add(try usage(100, 90)); summary.add(try usage(900, 90))
        XCTAssertEqual(summary.total, 1010); XCTAssertEqual(summary.cacheKnownInput, 1000)
        XCTAssertEqual(summary.cacheRead, 180); XCTAssertFalse(summary.partialCache)
        summary.add(try usage(100, nil)); XCTAssertTrue(summary.partialCache)
        XCTAssertEqual(summary.cacheKnownInput, 1000); XCTAssertEqual(summary.cacheRead, 180)
        XCTAssertNil(ChatUsageSummary(try usage(0, 0)).cachePercent)
        XCTAssertNil(ChatUsageSummary(try usage(100, nil)).cachePercent)
        let remote = try XCTUnwrap(RemoteTokenUsage(["uncachedInputTokens": 10, "outputTokens": 5, "cacheReadTokens": 20, "cacheWriteTokens": 3]))
        let mapped = ChatUsageSummary(remote); XCTAssertEqual(mapped.total, 38); XCTAssertEqual(mapped.input, 33)
        XCTAssertEqual(mapped.cacheRead, 20); XCTAssertEqual(mapped.cacheWrite, 3)
        var bad: [String: Any] = ["usage_quality": "COMPLETE", "input_tokens": 10, "output_tokens": 5, "cached_tokens": true]
        XCTAssertNil(MobileChatUsage(bad)); bad["cached_tokens"] = NSNull(); XCTAssertNotNil(MobileChatUsage(bad))
    }
    func testNearFullCacheHitNeverRoundsToFullAndUnknownCallsAreMarked() throws {
        let usage = try XCTUnwrap(MobileChatUsage(["usage_quality": "COMPLETE", "input_tokens": 1000000, "output_tokens": 1, "cached_tokens": 999999]))
        var summary = ChatUsageSummary(usage)
        XCTAssertNotEqual(summary.cachePercent, "100.0%")
        XCTAssertNotNil(summary.cachePercent)
        summary.unconfirmedCalls = 1; XCTAssertTrue(summary.partialCache)
        XCTAssertEqual(summary.total, 1000001)
    }
    func testActualModelIdentifierIsPreservedSeparatelyFromDisplayName() throws {
        let turn = try MobileChatTurn(["id": "turn", "user_text": "Hi", "reply_text": "Ok", "state": "SUCCEEDED", "ticket_state": "SUCCEEDED", "model_name": "DeepSeek", "model": "deepseek-flash"])
        XCTAssertEqual(turn.modelLabel, "DeepSeek · deepseek-flash")
    }
    private func wait(_ predicate: () -> Bool) async throws {
        for _ in 0..<80 { if predicate() { return }; try await Task.sleep(for: .milliseconds(50)) }
        XCTFail("The chat state did not settle")
    }
}
