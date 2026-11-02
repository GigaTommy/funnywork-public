import XCTest
import Combine
@testable import FunnyWork

@MainActor final class StubConversationService: ConversationService {
    var remote = try! RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["running": true, "turnStart": 42]])
    var commands: [CommandReceipt] = []
    var olderReceipts: [String: CommandReceipt] = [:]
    var sent: [RemoteCommand] = []
    var eventCount = 130
    var pageSize = 100
    var offsets: [Int] = []
    var failSend = false
    var sendError: Error?
    var snapshotBlocked = false
    var snapshotGate: CheckedContinuation<Void, Never>?
    var receiptCalls = 0
    var failSnapshot = false
    var failEvents = false
    var blockedOffset: Int?
    var eventGate: CheckedContinuation<Void, Never>?
    func snapshot() async throws -> RemoteSnapshot {
        if snapshotBlocked { await withCheckedContinuation { snapshotGate = $0 } }
        if failSnapshot { throw URLError(.notConnectedToInternet) }
        return remote
    }
    func receipts() async throws -> [CommandReceipt] { receiptCalls += 1; return commands }
    func receipt(id: String) async throws -> CommandReceipt? { olderReceipts[id] ?? commands.first { $0.id == id } }
    func events(offset: Int, limit: Int) async throws -> EventPage {
        if failEvents { throw URLError(.notConnectedToInternet) }
        offsets.append(offset)
        if offset == blockedOffset { await withCheckedContinuation { eventGate = $0 } }
        let end = min(eventCount, offset + min(limit, pageSize))
        let events: [[String: Any]] = (offset..<end).map { ["seq": $0, "type": "assistant/message", "data": ["content": [["text": "Message \($0)"]]]] }
        return try EventPage(["events": events, "nextSeq": end, "durableNextSeq": eventCount], offset: offset)
    }
    func send(_ command: RemoteCommand) async throws -> CommandReceipt {
        sent.append(command)
        if let sendError { throw sendError }
        if failSend { throw URLError(.timedOut) }
        let receipt = CommandReceipt(["id": command.id, "status": "queued"])!
        commands.append(receipt)
        return receipt
    }
}

@MainActor final class ConversationTests: XCTestCase {
    private var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) } }
    private func store(_ key: String = "account") -> ConversationStore { ConversationStore(key: key, root: root) }
    private func model(_ service: StubConversationService, _ store: ConversationStore) -> ConversationModel { ConversationModel(sessionID: "chat", service: service, store: store) }
    func testAdmissionRejectionKeepsDraftAndNeverReplaysAfterRestart() async throws {
        for code in ["SYNC_DECISION_EXPIRED", "SYNC_DEVICE_OFFLINE"] {
            let service = StubConversationService(); service.sendError = CommandAdmissionRejected(code: code)
            let cache = store(code), original = model(service, cache)
            await original.refresh(); original.updateDraft("Keep this draft"); await original.prompt()
            let command = try XCTUnwrap(service.sent.first)
            XCTAssertNil(original.pending)
            XCTAssertEqual(original.receipts.first?.id, command.id)
            XCTAssertEqual(original.receipts.first?.status, "rejected")
            XCTAssertEqual(original.draft, "Keep this draft")
            XCTAssertNotNil(original.error)
            original.updateDraft("New thought after rejection")
            let restored = model(service, cache)
            XCTAssertEqual(restored.receipts.first?.status, "rejected")
            await restored.retry()
            XCTAssertNil(restored.pending)
            XCTAssertEqual(restored.draft, "New thought after rejection")
            XCTAssertEqual(service.sent.count, 1)
        }
    }
    func testUnclassifiedConflictRemainsUncertainAfterRestart() async throws {
        let service = StubConversationService(); service.sendError = ClientError(message: "Conflict")
        let cache = store(), original = model(service, cache)
        await original.refresh(); original.updateDraft("Keep this draft"); await original.prompt()
        let command = try XCTUnwrap(original.pending)
        let restored = model(service, cache)
        XCTAssertEqual(restored.pending, command)
        XCTAssertEqual(restored.draft, "Keep this draft")
        XCTAssertTrue(original.receipts.isEmpty)
        XCTAssertEqual(service.sent.count, 1)
    }
    func testLosingApprovalCannotBeSentAgainEvenIfOldSnapshotStillListsIt() async throws {
        let service = StubConversationService(); service.sendError = CommandAdmissionRejected(code: "SYNC_DECISION_EXPIRED")
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["pending": [
            ["eventId": "approval-1", "event": "approval/request", "request": ["toolName": "shell"]]
        ]]])
        let cache = store(), original = model(service, cache)
        await original.refresh(); original.updateDraft("Unsent thought")
        await original.decide(try XCTUnwrap(original.interactions.first), allow: true)
        XCTAssertNil(original.pending)
        XCTAssertEqual(original.receipts.first?.status, "rejected")
        await original.decide(try XCTUnwrap(original.interactions.first), allow: true)
        XCTAssertEqual(service.sent.count, 1, "The stale visible approval must stay blocked")
        let restored = model(service, cache); await restored.refresh()
        await restored.decide(try XCTUnwrap(restored.interactions.first), allow: true)
        XCTAssertEqual(service.sent.count, 1)
        XCTAssertEqual(restored.receipts.first?.status, "rejected")
        XCTAssertEqual(restored.draft, "Unsent thought")
    }
    func testFileNoteRemainsVisibleWhenLocalStorageFails() async throws {
        let service = StubConversationService(); let cache = store(); let first = model(service, cache)
        let message = try XCTUnwrap(HistoryItem(["seq": 1, "type": "assistant/message", "data": ["content": [["text": "```diff\n--- a/A.swift\n+++ b/A.swift\n@@ -1 +1 @@\n-old\n+new\n```"]]]]))
        let result = try XCTUnwrap(RemoteResultReview(items: [message])), change = try XCTUnwrap(result.changes.first)
        first.updateReviewDraft("Reference", key: result.key, result: result)
        try FileManager.default.removeItem(at: root); try Data("blocked directory".utf8).write(to: root)
        first.updateReviewFileNote("Do not lose this note", change: change, result: result)
        XCTAssertEqual(first.saved.reviewFileNotes[result.key]?[change.id], "Do not lose this note")
        XCTAssertNotNil(first.storageError); XCTAssertFalse(first.commitReviewFileNote(change, result: result))
        XCTAssertEqual(first.saved.reviewFileNotes[result.key]?[change.id], "Do not lose this note"); XCTAssertTrue(service.sent.isEmpty)
    }
    func testFileNoteRestartsAndCommitsWithoutChangingLineReferences() async throws {
        let service = StubConversationService(); let cache = store(); let first = model(service, cache)
        let message = try XCTUnwrap(HistoryItem(["seq": 1, "type": "assistant/message", "data": ["content": [["text": "```diff\n--- a/A.swift\n+++ b/A.swift\n@@ -1 +1 @@\n-before\n+after\n```"]]]]))
        let result = try XCTUnwrap(RemoteResultReview(items: [message])), change = try XCTUnwrap(result.changes.first)
        first.updateReviewDraft("#1 · A.swift · Line 1\n> +after\n", key: result.key, result: result)
        first.updateReviewFileNote("Keep the retry check", change: change, result: result)
        let restored = model(service, cache)
        XCTAssertEqual(restored.saved.reviewFileNotes[result.key]?[change.id], "Keep the retry check")
        XCTAssertEqual(restored.unsubmittedReviewKeys, [result.key])
        XCTAssertTrue(restored.commitReviewFileNote(change, result: result))
        let combined = restored.saved.reviewDrafts[result.key]
        XCTAssertTrue(combined?.hasPrefix("#1 · A.swift · Line 1") == true)
        XCTAssertTrue(combined?.contains("Keep the retry check") == true)
        XCTAssertTrue(restored.commitReviewFileNote(change, result: result)); XCTAssertEqual(restored.saved.reviewDrafts[result.key], combined)
        XCTAssertTrue(service.sent.isEmpty)
        restored.updateReviewFileNote("Additional unsent note", change: change, result: result)
        restored.updateReviewDraft("", key: result.key, result: result)
        XCTAssertNotNil(restored.reviewSnapshot(result.key)); XCTAssertTrue(restored.pendingReviewNotes(result.key).contains("Additional unsent note"))
        XCTAssertTrue(restored.stageReviewFeedback(result.feedback(restored.pendingReviewNotes(result.key)), key: result.key))
        XCTAssertNil(restored.saved.reviewFileNotes[result.key]); XCTAssertTrue(restored.draft.contains("Additional unsent note"))
    }
    func testQueueEditAcceptanceRequiresFreshStateBeforeNextOperation() async throws {
        let service = StubConversationService(); let first = model(service, store())
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Original", "editable": true]]]])
        await first.refresh(); let item = try XCTUnwrap(first.remote.queue.first)
        await first.updateQueue(item, action: "edit", text: "Edited")
        let id = try XCTUnwrap(service.sent.first?.id)
        service.commands = [CommandReceipt(["id": id, "status": "accepted"])!]
        await first.refresh(); XCTAssertFalse(first.canOperate)
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Edited", "editable": true]]]])
        await first.refresh(); XCTAssertTrue(first.canOperate)
        XCTAssertEqual(first.queueEditText(try XCTUnwrap(first.remote.queue.first)), "Edited")
    }
    func testAcceptedQueueEditDoesNotOverrideLaterDesktopTextWhenReopening() async throws {
        let service = StubConversationService(); let cache = store()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Later desktop edit", "editable": true]]]])
        var saved = SavedConversation()
        saved.queueEdits["item"] = QueueEditDraft(itemID: "item", text: "Earlier phone edit", command: RemoteCommand(epoch: "epoch-1", kind: "queueEdit", payload: [:]), status: "accepted")
        try cache.save(saved, for: "chat")
        let first = model(service, cache); await first.refresh()
        let item = try XCTUnwrap(first.remote.queue.first)
        XCTAssertEqual(first.queueEditText(item), "Later desktop edit")
        first.saveQueueEdit(item.id, text: "New unsent draft")
        XCTAssertEqual(first.queueEditText(item), "New unsent draft")
        XCTAssertTrue(service.sent.isEmpty)
    }
    func testQueueEditKeepsCopyUntilFinalReceiptAndDoesNotRegress() async throws {
        let service = StubConversationService(); let cache = store()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Original", "editable": true]]]])
        let first = model(service, cache); await first.refresh(); first.updateDraft("Unrelated typing")
        first.saveQueueEdit("item", text: "Edited text")
        let item = try XCTUnwrap(first.remote.queue.first)
        let accepted = await first.updateQueue(item, action: "edit", text: "Edited text")
        XCTAssertFalse(accepted); XCTAssertTrue(first.queueEditWaiting("item")); XCTAssertEqual(first.saved.queueEdits["item"]?.text, "Edited text")
        let id = try XCTUnwrap(first.saved.queueEdits["item"]?.command?.id)
        let restored = model(service, cache); await restored.refresh(); XCTAssertTrue(restored.queueEditWaiting("item"))
        service.commands = [CommandReceipt(["id": id, "status": "rejected"])!]; await restored.refresh()
        XCTAssertFalse(restored.queueEditWaiting("item")); XCTAssertEqual(restored.saved.queueEdits["item"]?.text, "Edited text")
        XCTAssertEqual(restored.draft, "Unrelated typing")
        service.commands = [CommandReceipt(["id": id, "status": "queued"])!]; await restored.refresh()
        XCTAssertEqual(restored.saved.queueEdits["item"]?.status, "rejected"); XCTAssertEqual(service.sent.count, 1)
    }
    func testOldReviewSnapshotRestoresAfterNewResultsAndAllowsLocalStagingWhilePending() async throws {
        let service = StubConversationService(); let cache = store(); let first = model(service, cache); await first.refresh()
        let old = try XCTUnwrap(first.resultReview)
        first.updateReviewDraft("Preserved review", key: old.key, result: old)
        service.eventCount += 10; await first.refresh()
        XCTAssertNotEqual(first.resultReview?.key, old.key)
        let restored = model(service, cache)
        XCTAssertEqual(restored.reviewSnapshot(old.key), old)
        XCTAssertEqual(restored.unsubmittedReviewKeys, [old.key])
        await restored.refresh(); restored.updateDraft("Original send"); service.failSend = true; await restored.prompt()
        let pending = try XCTUnwrap(restored.pending)
        XCTAssertTrue(restored.stageReviewFeedback(old.feedback("Preserved review"), key: old.key))
        XCTAssertEqual(restored.pending?.id, pending.id); XCTAssertTrue(restored.draft.contains("Original send"))
        service.commands = [CommandReceipt(["id": pending.id, "status": "accepted"])!]; await restored.refresh()
        XCTAssertTrue(restored.draft.contains("Preserved review")); XCTAssertNil(restored.saved.reviewSnapshots[old.key])
        XCTAssertNil(restored.pending); XCTAssertEqual(service.sent.count, 1)
    }
    func testWithdrawalWaitsForFinalReceiptAndSurvivesRestart() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["running": true, "queue": [["id": "item", "text": "Queued task", "editable": true]]]])
        let cache = store(); let first = model(service, cache); await first.refresh()
        first.updateDraft("New typing")
        await first.withdrawToDraft(try XCTUnwrap(first.remote.queue.first))
        XCTAssertEqual(first.draft, "New typing"); XCTAssertTrue(first.withdrawalPending)
        XCTAssertEqual(service.sent.count, 1)
        let id = try XCTUnwrap(service.sent.first?.id)
        service.commands = [CommandReceipt(["id": id, "status": "accepted"])!]
        let restored = model(service, cache); await restored.refresh()
        XCTAssertEqual(restored.draft, "New typing\n\nQueued task")
        XCTAssertFalse(restored.withdrawalPending)
        await restored.refresh(); XCTAssertEqual(restored.draft, "New typing\n\nQueued task")
        XCTAssertEqual(service.sent.count, 1)
    }
    func testUncertainAndRejectedWithdrawalNeverRestoreOrResend() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Queued task", "editable": true]]]])
        let first = model(service, store()); await first.refresh()
        await first.withdrawToDraft(try XCTUnwrap(first.remote.queue.first))
        let id = try XCTUnwrap(service.sent.first?.id)
        service.commands = [CommandReceipt(["id": id, "status": "uncertain"])!]
        await first.refresh(); XCTAssertTrue(first.withdrawalPending); XCTAssertEqual(first.draft, "")
        first.updateDraft("Do not overwrite"); await first.prompt()
        XCTAssertEqual(service.sent.count, 1)
        service.commands = [CommandReceipt(["id": id, "status": "rejected"])!]
        await first.refresh(); XCTAssertFalse(first.withdrawalPending); XCTAssertEqual(first.draft, "Do not overwrite")
        XCTAssertNotNil(first.error)
    }
    func testWithdrawalLooksUpReceiptOutsideRecentListAndEditDoesNotClearDraft() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Old task", "editable": true]]]])
        let cache = store(); let first = model(service, cache); await first.refresh()
        first.updateDraft("Same edited text")
        let item = try XCTUnwrap(first.remote.queue.first)
        await first.updateQueue(item, action: "edit", text: "Same edited text")
        XCTAssertEqual(first.draft, "Same edited text")
        let editID = try XCTUnwrap(service.sent.first?.id)
        service.commands = [CommandReceipt(["id": editID, "status": "accepted"])!]
        await first.refresh()
        await first.refresh() // Reconfirm the native queue after edit acceptance.
        await first.withdrawToDraft(item)
        let id = try XCTUnwrap(service.sent.last?.id)
        service.commands = []; service.olderReceipts[id] = CommandReceipt(["id": id, "status": "accepted"])!
        let restored = model(service, cache); await restored.refresh()
        XCTAssertEqual(restored.draft, "Same edited text\n\nOld task")
        XCTAssertFalse(restored.withdrawalPending); XCTAssertEqual(service.sent.count, 2)
    }
    func testUnknownWithdrawalCanKeepExplicitCopyWithoutAutomaticResend() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Task copy", "editable": true]]]])
        let first = model(service, store()); await first.refresh()
        await first.withdrawToDraft(try XCTUnwrap(first.remote.queue.first))
        XCTAssertFalse(first.canKeepWithdrawalCopy)
        let id = try XCTUnwrap(service.sent.first?.id)
        service.commands = [CommandReceipt(["id": id, "status": "uncertain"])!]
        await first.refresh(); XCTAssertTrue(first.canKeepWithdrawalCopy)
        first.updateDraft("Existing"); first.keepWithdrawalCopyAfterReview()
        XCTAssertEqual(first.draft, "Existing\n\nTask copy"); XCTAssertFalse(first.withdrawalPending)
        service.commands = [CommandReceipt(["id": id, "status": "accepted"])!]
        await first.refresh(); XCTAssertEqual(first.draft, "Existing\n\nTask copy"); XCTAssertEqual(service.sent.count, 1)
    }
    func testAttachmentOrTruncatedQueueCannotWithdrawToPartialDraft() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["queue": [["id": "item", "text": "Preview only", "editable": false]]]])
        let first = model(service, store()); await first.refresh()
        await first.withdrawToDraft(try XCTUnwrap(first.remote.queue.first))
        XCTAssertTrue(service.sent.isEmpty); XCTAssertFalse(first.withdrawalPending)
    }
    func testReviewFeedbackPersistsAndAppendsOnceWithoutSending() async throws {
        let service = StubConversationService(); let cache = store(); let first = model(service, cache)
        first.updateDraft("Existing task"); first.updateReviewDraft("Fix retries", key: "2")
        let restored = model(service, cache)
        XCTAssertEqual(restored.saved.reviewDrafts["2"], "Fix retries")
        XCTAssertTrue(restored.stageReviewFeedback("Message #2: Fix retries", key: "2"))
        XCTAssertEqual(restored.draft, "Existing task\n\nMessage #2: Fix retries")
        XCTAssertNil(restored.saved.reviewDrafts["2"]); XCTAssertTrue(service.sent.isEmpty)
    }
    func testQueueEditTargetsOccurrenceAndRejectsBlankText() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["running": true, "queue": [["id": "occurrence", "text": "Before", "editable": true]]]])
        let model = model(service, store()); await model.refresh()
        let item = try XCTUnwrap(model.remote.queue.first)
        await model.updateQueue(item, action: "edit", text: " ")
        XCTAssertTrue(service.sent.isEmpty)
        await model.updateQueue(item, action: "edit", text: "After")
        XCTAssertEqual(service.sent.first?.kind, "queueEdit")
        XCTAssertEqual(service.sent.first?.payload["itemId"], .string("occurrence"))
        XCTAssertEqual(service.sent.first?.payload["text"], .string("After"))
    }
    func testEmptyIncrementDoesNotRepublishEntireMessageArray() async throws {
        let service = StubConversationService(); let model = model(service, store())
        await model.refresh()
        var changes = 0
        let subscription = model.$items.dropFirst().sink { _ in changes += 1 }
        await model.refresh()
        XCTAssertEqual(changes, 0); XCTAssertEqual(model.items.count, 50)
        subscription.cancel()
    }
    func testSlowStateDoesNotBlockMessagesOrReceiptRequests() async throws {
        let service = StubConversationService(); service.snapshotBlocked = true
        let model = model(service, store())
        let request = Task { await model.refresh() }
        for _ in 0..<100 {
            if service.snapshotGate != nil && model.latest != nil && service.receiptCalls > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let messagesArrived = model.latest != nil
        let receiptRequested = service.receiptCalls > 0
        service.snapshotGate?.resume(); await request.value
        XCTAssertTrue(messagesArrived); XCTAssertTrue(receiptRequested)
        XCTAssertTrue(model.controlReady)
    }
    func testSearchLocationLoadsBoundedWindowAndKeepsExplicitGapAndLatestCache() async throws {
        let service = StubConversationService(); service.eventCount = 1000
        let cache = store(); let model = model(service, cache)
        await model.loadLatest(); model.updateDraft("Keep this draft")
        let located = await model.loadAround(12); XCTAssertTrue(located)
        XCTAssertEqual(model.items.first?.id, 0); XCTAssertEqual(model.items.last?.id, 999)
        XCTAssertEqual(model.items.count, 88)
        XCTAssertEqual(model.historyGaps[950], 38..<950)
        XCTAssertEqual(service.offsets, [0, 950, 12, 0])
        let saved = try XCTUnwrap(cache.loadHistory("chat"))
        XCTAssertEqual(saved.earliest, 950); XCTAssertEqual(saved.latest, 1000); XCTAssertEqual(saved.items.count, 50)
        let gapStart = await model.loadGap(before: 950); XCTAssertEqual(gapStart, 38)
        XCTAssertEqual(model.historyGaps[950], 88..<950)
        XCTAssertEqual(model.draft, "Keep this draft"); XCTAssertTrue(service.sent.isEmpty)
        service.eventCount += 1; await model.loadLatest()
        XCTAssertEqual(model.items.last?.id, 1000); XCTAssertEqual(model.historyGaps[950], 88..<950)
    }
    func testCancelledContextLoadDoesNotMergePartialWindow() async throws {
        let service = StubConversationService(); service.eventCount = 1000
        let model = model(service, store())
        await model.loadLatest(); let original = model.items
        service.blockedOffset = 0
        let loading = Task { await model.loadAround(12) }
        while service.eventGate == nil { await Task.yield() }
        loading.cancel(); service.eventGate?.resume()
        let loaded = await loading.value
        XCTAssertFalse(loaded); XCTAssertEqual(model.items, original)
        XCTAssertEqual(model.latest, 1000); XCTAssertFalse(model.loadingHistory)
    }
    func testSearchLocationFailurePreservesHistoryAndCachedLocationWorksOffline() async throws {
        let service = StubConversationService(); service.eventCount = 1000
        let model = model(service, store())
        await model.loadLatest(); let original = model.items
        let missing = await model.loadAround(1000); XCTAssertFalse(missing)
        XCTAssertEqual(model.items, original)
        service.failEvents = true
        let unavailable = await model.loadAround(12); XCTAssertFalse(unavailable); XCTAssertEqual(model.items, original)
        let cached = await model.loadAround(980); XCTAssertTrue(cached); XCTAssertFalse(model.loadingHistory)
    }
    func testLiveReplyRequiresCurrentOnlineTurnAndYieldsToDurableMessage() async throws {
        let service = StubConversationService()
        let preview: [String: Any] = ["attemptId": "one", "startedAfterSeq": 129, "turnStart": 42, "text": "In progress", "truncated": false]
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch", "state": ["running": true, "turnStart": 42, "liveAssistant": preview]])
        let cache = store(); let model = model(service, cache)
        await model.refresh()
        XCTAssertEqual(model.liveReply?.text, "In progress")
        let cached = try JSONEncoder().encode(XCTUnwrap(cache.loadHistory("chat")))
        XCTAssertFalse(String(decoding: cached, as: UTF8.self).contains("In progress"))
        service.failSnapshot = true; await model.refresh()
        XCTAssertNil(model.liveReply)
        service.failSnapshot = false; await model.refresh()
        XCTAssertNotNil(model.liveReply)
        service.remote.turn = 43; await model.refresh()
        XCTAssertNil(model.liveReply)
        service.remote.turn = 42; service.eventCount = 131; await model.refresh()
        XCTAssertNil(model.liveReply)
        model.deactivate(); XCTAssertNil(model.liveReply)
        for invalid in [[:], ["text": "missing fields"], ["attemptId": "x", "startedAfterSeq": 1, "turnStart": 2, "text": "old", "truncated": false]] as [[String: Any]] {
            XCTAssertNil(LiveAssistantReply(invalid))
        }
    }
    func testExecutionIndicatorRequiresFreshOnlineRunningState() async throws {
        let service = StubConversationService(); let model = model(service, store())
        XCTAssertFalse(model.showingExecution)
        await model.refresh(); XCTAssertTrue(model.showingExecution)
        service.failSnapshot = true; await model.refresh(); XCTAssertFalse(model.showingExecution)
        service.failSnapshot = false; service.remote.online = false
        await model.refresh(); XCTAssertFalse(model.showingExecution)
        service.remote.online = true; service.remote.running = false
        await model.refresh(); XCTAssertFalse(model.showingExecution)
        service.remote.running = true
        service.remote.interactions = [Interaction(id: "approval", correlation: "event", epoch: service.remote.epoch,
            kind: .approval, title: "Approval", reason: "", detail: "", questions: [])]
        await model.refresh(); XCTAssertFalse(model.showingExecution)
        service.remote.interactions = []; await model.refresh(); XCTAssertTrue(model.showingExecution)
        model.deactivate(); XCTAssertFalse(model.showingExecution)
    }
    func testQueueUsesMatchingLiveReceiptsAndStopsAtAcceptance() async throws {
        let service = StubConversationService(); let cache = store()
        let first = RemoteCommand(epoch: "epoch-1", kind: "prompt", payload: ["text": .string("first")])
        let second = RemoteCommand(epoch: "epoch-1", kind: "prompt", payload: ["text": .string("second")])
        let stale = RemoteCommand(epoch: "old", kind: "prompt", payload: ["text": .string("stale")])
        let cancel = RemoteCommand(epoch: "epoch-1", kind: "cancel", payload: [:])
        var saved = SavedConversation(); saved.recentCommands = [cancel, stale, second, first]
        try cache.save(saved, for: "chat")
        service.commands = saved.recentCommands.map { CommandReceipt(["id": $0.id, "status": "queued"])! }
        let model = model(service, cache)
        XCTAssertTrue(model.queuedPrompts.isEmpty)
        await model.refresh(); XCTAssertEqual(model.queuedPrompts.map(\.command.id), [first.id, second.id])
        service.commands = [CommandReceipt(["id": first.id, "status": "accepted"])!, CommandReceipt(["id": second.id, "status": "claimed"])!]
        await model.refresh(); XCTAssertEqual(model.queuedPrompts.map(\.command.id), [second.id])
        service.failSnapshot = true; await model.refresh(); XCTAssertTrue(model.queuedPrompts.isEmpty)
        service.failSnapshot = false; service.commands = [CommandReceipt(["id": second.id, "status": "uncertain"])!]
        await model.refresh(); XCTAssertTrue(model.queuedPrompts.isEmpty)
    }

    func testRecentWindowHandlesByteLimitedPagesWithoutGaps() async {
        let service = StubConversationService(); service.pageSize = 7
        let model = model(service, store())
        await model.refresh()
        XCTAssertEqual(model.items.map(\.id), Array(80..<130))
        XCTAssertEqual(model.earliest, 80)
        XCTAssertEqual(model.latest, 130)
        service.eventCount = 133
        await model.loadEarlier()
        await model.refresh()
        XCTAssertEqual(model.items.map(\.id), Array(30..<133))
        XCTAssertEqual(model.earliest, 30)
        XCTAssertEqual(model.latest, 133)
    }
    func testDraftAndUncertainCommandSurviveRecreation() async throws {
        let service = StubConversationService(); service.failSend = true
        let cache = store()
        let original = model(service, cache)
        await original.refresh()
        original.updateDraft("Keep this draft")
        await original.prompt(steer: true)
        let pending = try XCTUnwrap(original.pending)
        XCTAssertEqual(pending.payload["expectedTurn"], .number(42))
        let recreated = model(service, cache)
        XCTAssertEqual(recreated.draft, "Keep this draft")
        XCTAssertEqual(recreated.pending, pending)
        service.failSend = false
        await recreated.retry()
        XCTAssertEqual(service.sent.map(\.id), [pending.id, pending.id])
        XCTAssertNil(recreated.pending)
        XCTAssertEqual(recreated.draft, "")
    }
    func testReceiptRecoveryDoesNotResendOrEraseNewDraft() async throws {
        let service = StubConversationService(); service.failSend = true
        let cache = store(); let original = model(service, cache)
        await original.refresh(); original.updateDraft("Original"); await original.prompt()
        let pending = try XCTUnwrap(original.pending)
        original.updateDraft("New thought")
        service.commands = [CommandReceipt(["id": pending.id, "status": "accepted"])!]
        let recreated = model(service, cache)
        await recreated.retry()
        XCTAssertEqual(service.sent.count, 1)
        XCTAssertNil(recreated.pending)
        XCTAssertEqual(recreated.draft, "New thought")
    }
    func testLateReceiptCannotOverwriteNewerPageDraftOrPendingCommand() async throws {
        let service = StubConversationService(); service.failSend = true
        let cache = store(); let original = model(service, cache)
        await original.refresh(); original.updateDraft("Original"); await original.prompt()
        let old = try XCTUnwrap(original.pending)
        var newer = try cache.load("chat")
        newer.draft = "New page draft"
        newer.pending = RemoteCommand(epoch: "epoch-1", kind: "prompt", payload: ["text": .string(newer.draft)])
        try cache.save(newer, for: "chat")
        service.commands = [CommandReceipt(["id": old.id, "status": "accepted"])!]
        await original.refresh()
        XCTAssertEqual(try cache.load("chat").pending, newer.pending)
        XCTAssertEqual(try cache.load("chat").draft, newer.draft)
        XCTAssertEqual(service.sent.count, 1)
    }
    func testEpochChangeAndExpiredRequestNeverReplay() async throws {
        for expired in [false, true] {
            let service = StubConversationService(); let cache = store(UUID().uuidString)
            var saved = SavedConversation()
            saved.pending = RemoteCommand(epoch: expired ? "epoch-1" : "old-epoch", kind: "cancel", payload: ["expectedTurn": .number(42)], now: Date().addingTimeInterval(expired ? -60 : 0))
            try cache.save(saved, for: "chat")
            let restored = model(service, cache)
            await restored.retry()
            XCTAssertTrue(service.sent.isEmpty)
            XCTAssertNotNil(restored.pending)
            XCTAssertFalse(restored.canRetry)
        }
    }
    func testUncertainReceiptDoesNotBecomeSuccess() async throws {
        let service = StubConversationService(); let cache = store()
        var saved = SavedConversation()
        saved.pending = RemoteCommand(epoch: "epoch-1", kind: "cancel", payload: ["expectedTurn": .number(42)])
        try cache.save(saved, for: "chat")
        service.commands = [CommandReceipt(["id": saved.pending!.id, "status": "uncertain"])!]
        let restored = model(service, cache)
        await restored.refresh()
        XCTAssertNotNil(restored.pending)
        XCTAssertTrue(service.sent.isEmpty)
        XCTAssertFalse(restored.canRetry)
    }
    func testApprovalAndQuestionCorrelation() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["pending": [
            ["eventId": "approval-1", "event": "approval/request", "request": ["toolName": "shell", "reason": "Run tests"]],
            ["eventId": "question-1", "event": "user-questions/request", "request": ["questions": [["id": "q", "question": "Choose", "options": [["label": "A"], ["label": "B"]]]]]]
        ]]])
        let model = model(service, store()); await model.refresh()
        let approval = try XCTUnwrap(model.interactions.first)
        await model.decide(approval, allow: true)
        XCTAssertEqual(service.sent.last?.payload["eventId"], .string("approval-1"))
        XCTAssertEqual(service.sent.last?.payload["outcome"], .string("allowed-once"))
        await model.decide(approval, allow: true)
        XCTAssertEqual(service.sent.count, 1, "Queued approvals must not be submitted twice")
        let interaction = model.interactions[1]; let question = interaction.questions[0]
        XCTAssertFalse(model.answersValid(interaction))
        model.updateAnswer(QuestionDraft(selected: ["A"], custom: "Details"), interaction: interaction, question: question)
        await model.reply(interaction)
        XCTAssertEqual(service.sent.last?.kind, "decision")
        XCTAssertEqual(service.sent.last?.payload["eventId"], .string("question-1"))
        XCTAssertEqual(service.sent.last?.payload["answer"], .object(["answers": .array([.object(["id": .string("q"), "selected": .array([.string("A")]), "custom": .string("Details")])])]))
    }
    func testStaleApprovalAndOfflineActionsAreBlocked() async throws {
        let service = StubConversationService()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["pending": [["eventId": "approval-1", "event": "approval/request", "request": ["toolName": "shell"]]]]])
        let model = model(service, store()); await model.refresh()
        let stale = try XCTUnwrap(model.interactions.first)
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-2", "state": [:]])
        await model.refresh(); await model.decide(stale, allow: true)
        XCTAssertTrue(service.sent.isEmpty)
        service.failSnapshot = true; await model.refresh()
        model.updateDraft("Do not send offline"); await model.prompt()
        XCTAssertTrue(service.sent.isEmpty)
        XCTAssertFalse(model.canOperate)
    }
    func testContinuedQuestionsUseCallIDAndPreserveAnswers() async throws {
        let service = StubConversationService(); let cache = store()
        service.remote = try RemoteSnapshot(["online": true, "epoch": "epoch-1", "state": ["projections": ["userQuestions": ["active": [["state": "continued", "callId": "call-1", "questions": [["id": "q", "question": "Why?"]]]]]]]])
        let model = model(service, cache); await model.refresh()
        let interaction = model.interactions[0]; let question = interaction.questions[0]
        model.updateAnswer(QuestionDraft(custom: "Because"), interaction: interaction, question: question)
        let recreated = self.model(service, cache); await recreated.refresh()
        XCTAssertEqual(recreated.answer(interaction, question).custom, "Because")
        await recreated.reply(interaction)
        XCTAssertEqual(service.sent.last?.kind, "answer")
        XCTAssertEqual(service.sent.last?.payload["callId"], .string("call-1"))
    }
    func testStorageIsolationAndLogoutInvalidateOldWriters() throws {
        let first = store("first"), second = store("second")
        var saved = SavedConversation(); saved.draft = "Private"
        try first.save(saved, for: "chat")
        XCTAssertEqual(try first.load("chat").draft, "Private")
        XCTAssertEqual(try second.load("chat").draft, "")
        XCTAssertEqual(try first.load("other-chat").draft, "")
        try first.invalidateAndClear()
        XCTAssertThrowsError(try first.save(saved, for: "chat"))
        XCTAssertEqual(try store("first").load("chat").draft, "")
    }
    func testIdentityScopeIncludesTenantPrincipalAndServer() throws {
        let server = try API.serverURL("https://example.com")
        let first = ConversationStore.accountKey(server: server, tenant: "a", principal: "user")
        XCTAssertNotEqual(first, ConversationStore.accountKey(server: server, tenant: "b", principal: "user"))
        XCTAssertNotEqual(first, ConversationStore.accountKey(server: server, tenant: "a", principal: "other"))
        XCTAssertNotEqual(first, ConversationStore.accountKey(server: try API.serverURL("https://other.example.com"), tenant: "a", principal: "user"))
    }
    func testPersistenceFailurePreventsSending() async throws {
        let service = StubConversationService(); let cache = store()
        let model = model(service, cache); await model.refresh()
        model.updateDraft("Do not lose this")
        try cache.invalidateAndClear()
        await model.prompt()
        XCTAssertTrue(service.sent.isEmpty)
        XCTAssertNotNil(model.storageError)
    }
    func testExpiredQueuedPromptCanRestoreItsText() async throws {
        let service = StubConversationService(); let cache = store()
        let model = model(service, cache); await model.refresh()
        model.updateDraft("Restore later"); await model.prompt()
        XCTAssertEqual(model.draft, "")
        let id = try XCTUnwrap(service.sent.first?.id)
        let expired = CommandReceipt(["id": id, "status": "expired"])!
        service.commands = [expired]
        let restored = self.model(service, cache); await restored.refresh()
        restored.restoreDraft(expired)
        XCTAssertEqual(restored.draft, "Restore later")
    }
    func testOldSavedDraftSchemaRemainsReadable() throws {
        let data = Data(#"{"draft":"Saved before update","answers":{},"interactionRequests":{}}"#.utf8)
        let decoded = try JSONDecoder().decode(SavedConversation.self, from: data)
        XCTAssertEqual(decoded.draft, "Saved before update")
        XCTAssertTrue(decoded.recentCommands.isEmpty)
    }
    func testMalformedEventPageCannotAdvanceCursor() {
        XCTAssertThrowsError(try EventPage(["events": [["seq": 8]], "nextSeq": 10, "durableNextSeq": 10], offset: 8))
        XCTAssertThrowsError(try EventPage(["events": [["seq": 9]], "nextSeq": 9, "durableNextSeq": 10], offset: 8))
    }
    func testOfflineHistoryRestoresWithoutEnablingCommandsAndReconnectHasNoGaps() async throws {
        let service = StubConversationService(); let cache = store()
        let first = model(service, cache); await first.refresh(); await first.loadEarlier()
        service.failSnapshot = true; service.failEvents = true
        let restored = model(service, cache)
        XCTAssertEqual(restored.items.map(\.id), Array(30..<130))
        XCTAssertTrue(restored.showingCachedHistory)
        await restored.refresh()
        restored.updateDraft("Keep offline")
        await restored.prompt()
        XCTAssertFalse(restored.canOperate)
        XCTAssertTrue(service.sent.isEmpty)
        XCTAssertEqual(restored.items.count, 100)
        service.failSnapshot = false; service.failEvents = false; service.eventCount = 135
        await restored.refresh()
        XCTAssertEqual(restored.items.map(\.id), Array(30..<135))
        XCTAssertFalse(restored.showingCachedHistory)
        XCTAssertEqual(restored.draft, "Keep offline")
    }
    func testCacheIsBoundedIsolatedAndClearedWithLogout() throws {
        let rows = (0..<300).map { HistoryItem(["seq": $0, "type": "assistant/message", "data": ["content": [["text": "Message"]]]])! }
        let cached = CachedHistory(items: rows, latest: 300)
        XCTAssertEqual(cached.items.map(\.id), Array(100..<300))
        XCTAssertEqual(cached.earliest, 100)
        let cache = store()
        try cache.saveHistory(cached, for: "chat")
        XCTAssertNil(try store("another").loadHistory("chat"))
        try cache.invalidateAndClear()
        XCTAssertThrowsError(try cache.saveHistory(cached, for: "chat"))
        XCTAssertNil(try store().loadHistory("chat"))
    }
    func testLegacyPresentationCacheIsRefetchedWithoutDiscardingDrafts() throws {
        let cache = store(); var saved = SavedConversation(); saved.draft = "Keep my next instruction"
        try cache.save(saved, for: "chat")
        let item = try XCTUnwrap(HistoryItem(["seq": 0, "type": "assistant/message", "data": ["content": [["text": "Legacy merged reasoning"]]]]))
        var payload = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(CachedHistory(items: [item], latest: 1))) as? [String: Any])
        payload.removeValue(forKey: "presentationVersion")
        let legacy = try JSONDecoder().decode(CachedHistory.self, from: JSONSerialization.data(withJSONObject: payload))
        XCTAssertEqual(legacy.presentationVersion, 0)
        try cache.saveHistory(legacy, for: "chat")
        XCTAssertNil(try cache.loadHistory("chat"))
        let restored = model(StubConversationService(), cache)
        XCTAssertTrue(restored.items.isEmpty); XCTAssertEqual(restored.draft, saved.draft)
        let fresh = try XCTUnwrap(HistoryItem(["seq": 0, "type": "assistant/message", "data": ["content": [["type": "reasoning", "text": "Internal"], ["type": "text", "text": "Public result"]]]]))
        try cache.saveHistory(CachedHistory(items: [fresh], latest: 1), for: "chat")
        XCTAssertEqual(try cache.loadHistory("chat")?.items.first?.text, "Public result")
    }
    func testOversizedHistoryStillResumesFromSavedCursor() {
        let huge = HistoryItem(["seq": 1, "data": ["content": [["text": String(repeating: "a", count: 2_000_001)]]]])!
        let small = HistoryItem(["seq": 2, "data": ["content": [["text": "latest"]]]])!
        let cache = CachedHistory(items: [huge, small], latest: 3)
        XCTAssertEqual(cache.items.map(\.id), [2])
        XCTAssertEqual(cache.earliest, 2)
        XCTAssertEqual(cache.latest, 3)
    }
    func testReadingPositionPersistsWithinAccountAndSurvivesHistoryRefresh() throws {
        let rows = (0..<300).map { HistoryItem(["seq": $0, "data": ["content": [["text": "Message"]]]])! }
        let cache = store()
        try cache.saveHistory(CachedHistory(items: Array(rows.prefix(150)), latest: 150), for: "chat")
        try cache.saveReadingPosition(80, for: "chat")
        XCTAssertEqual(try store().loadHistory("chat")?.readingPosition, 80)
        XCTAssertNil(try store("another").loadHistory("chat"))
        XCTAssertNil(try cache.loadHistory("other"))
        try cache.saveHistory(CachedHistory(items: Array(rows.prefix(180)), latest: 180), for: "chat")
        XCTAssertEqual(try cache.loadHistory("chat")?.readingPosition, 80)
        try cache.saveHistory(CachedHistory(items: rows, latest: 300), for: "chat")
        XCTAssertNil(try cache.loadHistory("chat")?.readingPosition)
        try cache.saveReadingPosition(999, for: "chat")
        XCTAssertNil(try cache.loadHistory("chat")?.readingPosition)
        try cache.saveReadingPosition(200, for: "chat")
        try cache.saveReadingPosition(nil, for: "chat")
        XCTAssertNil(try cache.loadHistory("chat")?.readingPosition)
        try cache.invalidateAndClear()
        XCTAssertThrowsError(try cache.saveReadingPosition(200, for: "chat"))
    }
    func testHistoryBudgetIncludesToolDetailsAndJSONEscaping() throws {
        let hugeCall = HistoryItem(["seq": 0, "type": "tool/call", "data": ["name": "shell", "arguments": String(repeating: "参", count: 700_000)]])!
        let latest = HistoryItem(["seq": 1, "type": "assistant/message", "data": ["content": [["text": "Done"]]]])!
        let cache = CachedHistory(items: [hugeCall, latest], latest: 2)
        XCTAssertEqual(cache.items.map(\.id), [1])
        XCTAssertEqual(cache.earliest, 1)
        XCTAssertEqual(cache.latest, 2)
        let escaped = (0..<4).map { index in
            HistoryItem(["seq": index, "type": "tool/call", "data": ["name": "shell", "arguments": String(repeating: "\"\n\\", count: 100_000)]])!
        }
        let bounded = CachedHistory(items: escaped, latest: 4)
        XCTAssertEqual(bounded.items.map(\.id), [1, 2, 3])
        XCTAssertLessThanOrEqual(try JSONEncoder().encode(bounded).count, CachedHistory.maximumBytes)
        try store().saveHistory(bounded, for: "chat")
        try store().saveReadingPosition(2, for: "chat")
        XCTAssertEqual(try store().loadHistory("chat")?.readingPosition, 2)
    }
    func testOversizedLegacyHistoryIsRejectedBeforeDecodingAndCanBeReplaced() throws {
        let cache = store()
        let item = HistoryItem(["seq": 0, "data": ["content": [["text": "hello"]]]])!
        let history = CachedHistory(items: [item], latest: 1)
        try cache.saveHistory(history, for: "chat")
        let folder = root.appendingPathComponent("account")
        let file = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).first { $0.lastPathComponent.hasPrefix("history-") })
        // An oversized file is ignored even if it is not valid JSON; it is never decoded.
        try Data(repeating: 0x78, count: CachedHistory.maximumBytes + 1).write(to: file)
        XCTAssertNil(try cache.loadHistory("chat"))
        try cache.saveHistory(history, for: "chat")
        XCTAssertEqual(try cache.loadHistory("chat")?.items, [item])
    }

}
