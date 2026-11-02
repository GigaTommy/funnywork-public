import XCTest
@testable import FunnyWork

@MainActor final class ForkConversationTests: XCTestCase {
    var root: URL!
    let source = Conversation(["id": "source", "device_id": "device", "device_name": "Mac", "next_seq": 2])!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func store(_ key: String = "account") -> ConversationStore { ConversationStore(key: key, root: root) }
    func testAcceptedForkOpensExactDestinationWithoutChangingSourceDraft() async throws {
        let service = ForkStub(), cache = store(), model = ForkConversationModel(source: source, service: service, store: store())
        var draft = SavedConversation(); draft.draft = "Original draft"; try cache.save(draft, for: source.id)
        await model.create(at: 1)
        XCTAssertEqual(model.created?.id, "child"); XCTAssertNil(model.pending)
        XCTAssertEqual(try cache.load(source.id).draft, "Original draft")
        XCTAssertNil(try cache.loadFork(source.id)?.pending)
        XCTAssertEqual(service.requests.count, 1); XCTAssertEqual(service.requests.first?.sequence, 1)
    }
    func testLostReceiptRestoresSameRequestAndReadbackNeverResubmits() async throws {
        let service = ForkStub(); service.lost = true
        let model = ForkConversationModel(source: source, service: service, store: store())
        await model.create(at: 1)
        let request = try XCTUnwrap(model.pending)
        let restored = ForkConversationModel(source: source, service: service, store: store())
        XCTAssertEqual(restored.pending, request)
        service.lost = false; await restored.refresh()
        XCTAssertEqual(restored.created?.id, "child"); XCTAssertEqual(service.requests.count, 1)
        XCTAssertNil(ForkConversationModel(source: source, service: service, store: store("other")).pending)
    }
    func testUnknownResultCannotBeReplayedButConfirmedAbsencePermitsExplicitRetry() async throws {
        let service = ForkStub(); service.lost = true; service.receiptStatus = "uncertain"
        let model = ForkConversationModel(source: source, service: service, store: store())
        await model.create(at: 1); await model.refresh(); await model.retry()
        XCTAssertFalse(model.canRetry); XCTAssertEqual(service.requests.count, 1)
        service.missing = true; await model.refresh(); XCTAssertTrue(model.canRetry)
        service.lost = false; await model.retry()
        XCTAssertEqual(service.requests.count, 2); XCTAssertEqual(service.requests[0].id, service.requests[1].id)
        XCTAssertNotNil(model.created)
    }
    func testRuntimeChangeAndUnsupportedOrOfflineSourcesPreventReplay() async throws {
        let service = ForkStub(); service.lost = true; service.missing = true
        let model = ForkConversationModel(source: source, service: service, store: store())
        await model.create(at: 1)
        service.epoch = "new"; await model.refresh(); XCTAssertFalse(model.canRetry)
        service.epoch = "epoch"; service.online = false; await model.refresh(); XCTAssertFalse(model.canRetry)
        service.online = true; service.supported = false; await model.refresh(); XCTAssertFalse(model.canRetry)
        await model.retry(); XCTAssertEqual(service.requests.count, 1)
    }
    func testClosingDuringReadbackRejectsLateResultAndLogoutPreventsNavigation() async throws {
        let service = ForkStub(); service.lost = true
        let cache = store(), model = ForkConversationModel(source: source, service: service, store: store())
        await model.create(at: 1); service.delay = true
        let read = Task { await model.refresh() }
        while service.continuation == nil { await Task.yield() }
        model.deactivate(); service.continuation?.resume(returning: try CreationReceipt(["id": model.pending!.id, "status": "accepted", "result": ["sessionId": "child"]])); await read.value
        XCTAssertNil(model.created); XCTAssertNotNil(try cache.loadFork(source.id)?.pending)
        service.delay = false; cache.invalidate()
        let invalid = ForkConversationModel(source: source, service: service, store: cache)
        await invalid.create(at: 1); XCTAssertNil(invalid.created)
    }
    func testInvalidDestinationRetainsRequestAndOldPageCannotClearNewRequest() async throws {
        let service = ForkStub(); service.wrongDevice = true
        let cache = store(), model = ForkConversationModel(source: source, service: service, store: store())
        await model.create(at: 1); XCTAssertNil(model.created); XCTAssertNotNil(model.error)
        let original = try XCTUnwrap(model.pending)
        let replacement = ForkRequest(source: source, epoch: "epoch", sequence: 0)
        try cache.saveFork(SavedFork(pending: replacement), sourceID: source.id)
        service.wrongDevice = false; await model.refresh()
        XCTAssertNil(model.created); XCTAssertEqual(try cache.loadFork(source.id)?.pending?.id, replacement.id)
        XCTAssertFalse(try cache.clearFork(source.id, matching: original.id))
    }
    func testOldMissingRequestAndInvalidCutCannotCreateAnotherFork() async throws {
        let service = ForkStub(), cache = store(); service.missing = true
        let old = ForkRequest(source: source, epoch: "epoch", sequence: 1, now: Date().addingTimeInterval(-60))
        try cache.saveFork(SavedFork(pending: old), sourceID: source.id)
        let model = ForkConversationModel(source: source, service: service, store: cache)
        await model.refresh(); await model.retry(); XCTAssertFalse(model.canRetry); XCTAssertTrue(service.requests.isEmpty)
        model.clearAfterReview(); await model.create(at: -1); XCTAssertTrue(service.requests.isEmpty)
    }
    func testCapabilityRequiresBooleanAndFreshOnlineState() throws {
        for flag: Any in [1, "true", false] {
            let remote = try RemoteSnapshot(["online": true, "epoch": "epoch", "state": [:], "sessionForks": flag])
            XCTAssertFalse(remote.sessionForks)
        }
        XCTAssertTrue(try RemoteSnapshot(["online": true, "epoch": "epoch", "state": ["controllable": true], "sessionForks": true]).sessionForks)
        XCTAssertFalse(try RemoteSnapshot(["online": true, "epoch": "epoch", "state": ["controllable": "true"], "sessionForks": true]).sessionForks)
        XCTAssertFalse(try RemoteSnapshot(["online": false, "epoch": "epoch", "state": [:], "sessionForks": true]).sessionForks)
    }
}
@MainActor private final class ForkStub: ForkConversationService {
    var lost = false, missing = false, online = true, supported = true, delay = false, wrongDevice = false
    var epoch = "epoch", receiptStatus = "accepted"
    var requests: [ForkRequest] = []
    var continuation: CheckedContinuation<CreationReceipt?, Never>?
    func forkSnapshot() async throws -> RemoteSnapshot { try RemoteSnapshot(["online": online, "epoch": epoch, "state": ["controllable": true], "sessionForks": supported]) }
    func submitFork(_ request: ForkRequest) async throws -> CreationReceipt {
        requests.append(request)
        if lost { throw URLError(.timedOut) }
        return try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "child"]])
    }
    func forkReceipt(_ request: ForkRequest) async throws -> CreationReceipt? {
        if delay { return await withCheckedContinuation { continuation = $0 } }
        if missing { return nil }
        return try CreationReceipt(["id": request.id, "status": receiptStatus, "result": ["sessionId": "child"]])
    }
    func forkDestination(_ id: String) async throws -> Conversation { Conversation(["id": id, "device_id": wrongDevice ? "other-device" : "device", "next_seq": 2])! }
}
