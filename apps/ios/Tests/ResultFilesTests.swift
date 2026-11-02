import XCTest
import CryptoKit
@testable import FunnyWork

@MainActor final class ResultFilesTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func manifest(_ hash: String = String(repeating: "a", count: 64), state: String = "pending") -> [String: Any] {
        ["schema": 1, "id": "report", "version": 1, "original": ["type": "file", "attachment": ["attachmentId": "sha256:" + hash, "name": "报告.docx", "bytes": 11]], "variants": [], "previewState": state, "warnings": []]
    }
    func testPublicationSurvivesCacheAndLatePreviewUsesOneTimelineAnchor() throws {
        let first = HistoryItem(["seq": 0, "type": "deliverables/presented", "data": ["files": [["path": "/snapshot", "result": manifest()]]]])!
        let last = HistoryItem(["seq": 2, "type": "deliverables/presented", "data": ["files": [["path": "/snapshot", "result": manifest(state: "ready")]]]])!
        XCTAssertEqual(first.results.count, 1)
        let decoded = try JSONDecoder().decode(HistoryItem.self, from: JSONEncoder().encode(first)); XCTAssertEqual(decoded.results, first.results)
        let timeline = ConversationTimeline(items: [first, last], gaps: [])
        XCTAssertEqual(timeline.rows.count, 1); XCTAssertFalse(timeline.rows[0].isActivity); XCTAssertEqual(timeline.anchor(for: 2), 0)
        let old = HistoryItem(["seq": 3, "type": "assistant/message", "data": ["content": [["text": "Old message"]]]])!
        XCTAssertTrue(old.results.isEmpty)
        var bad = manifest(); bad["id"] = "../other"; XCTAssertNil(ResultVersion.parse(bad))
        let feedback = first.results[0].feedback("改标题", location: "第 2 页")
        XCTAssertTrue(feedback.contains("resultId=report")); XCTAssertTrue(feedback.contains("version=1")); XCTAssertTrue(feedback.contains("sha256="))
    }
    func testDownloadResumesFromCommittedDiskCursorAfterReopening() async throws {
        let service = AttachmentServiceStub(), store = ConversationStore(key: "one", root: root)
        let file = try ChatAttachment(service.row()), revision = store.attachmentCacheRevision
        XCTAssertEqual(try store.beginAttachmentDownload(file, revision: revision), 0)
        try store.appendAttachmentChunk(service.payload.prefix(4), item: file, offset: 0, revision: revision)
        XCTAssertEqual(try store.downloadedFileUsage().bytes, 4)
        let reopened = ConversationStore(key: "one", root: root), model = AttachmentModel(service: service, store: reopened, sessionID: "chat")
        model.open(file); await model.waitForDownload()
        XCTAssertEqual(service.requests.first, 4); XCTAssertNotNil(model.preview)
        XCTAssertEqual(try Data(contentsOf: model.preview!), service.payload)
        XCTAssertNil(try ConversationStore(key: "two", root: root).attachmentURL(file))
    }
    func testClearRejectsLatePartialWriteAndCorruptCompleteDownloadRestarts() throws {
        let service = AttachmentServiceStub(), store = ConversationStore(key: "one", root: root)
        let file = try ChatAttachment(service.row()), revision = store.attachmentCacheRevision
        _ = try store.beginAttachmentDownload(file, revision: revision); try store.clearDownloadedFiles()
        XCTAssertThrowsError(try store.appendAttachmentChunk(Data([0]), item: file, offset: 0, revision: revision))
        let current = store.attachmentCacheRevision; _ = try store.beginAttachmentDownload(file, revision: current)
        try store.appendAttachmentChunk(Data(repeating: 0, count: file.bytes), item: file, offset: 0, revision: current)
        XCTAssertThrowsError(try store.finishAttachmentDownload(file, revision: current))
        XCTAssertEqual(try store.beginAttachmentDownload(file, revision: current), 0)
    }
    func testRecoveredAssociationsSyncWithoutASelectedPhoneChatAndKeepConcurrentLinks() async throws {
        let store = ConversationStore(key: "one", root: root)
        let first = Conversation(["id": "first", "title": "First", "device_id": "device"])!, second = Conversation(["id": "second", "title": "Second", "device_id": "device"])!
        try store.linkResultTask(first, sourceChatID: "phone-a")
        let service = LinkServiceStub()
        service.onAssociation = { _, _ in try store.linkResultTask(second, sourceChatID: "phone-b") }
        try await MobileResultLinkSync.flush(store: store, service: service)
        XCTAssertEqual(service.calls.map { $0.0 }, ["phone-a", "phone-b"])
        XCTAssertEqual(service.calls.map { $0.1 }, ["first", "second"])
        XCTAssertTrue(try store.loadResultLinks().allSatisfy(\.confirmed))
        let revision = store.resultLinksRevision
        try await MobileResultLinkSync.flush(store: store, service: service)
        XCTAssertEqual(service.calls.count, 2); XCTAssertEqual(store.resultLinksRevision, revision)
    }
    func testInterruptedAssociationAcknowledgementKeepsTheOutboxForIdempotentRetry() async throws {
        let store = ConversationStore(key: "one", root: root), service = LinkServiceStub()
        try store.linkResultTask(Conversation(["id": "task", "device_id": "device"])!, sourceChatID: "phone")
        service.fail = true
        do { try await MobileResultLinkSync.flush(store: store, service: service); XCTFail("Expected interrupted acknowledgement") } catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        XCTAssertFalse(try store.loadResultLinks()[0].confirmed)
        let reopened = ConversationStore(key: "one", root: root); service.fail = false
        try await MobileResultLinkSync.flush(store: reopened, service: service)
        XCTAssertEqual(service.calls.count, 2); XCTAssertTrue(try reopened.loadResultLinks()[0].confirmed)
        XCTAssertTrue(try ConversationStore(key: "other", root: root).loadResultLinks().isEmpty)
    }

}


@MainActor private final class LinkServiceStub: MobileResultLinkService {
    var calls: [(String, String)] = []
    var fail = false
    var onAssociation: ((String, String) throws -> Void)?
    func associate(chatID: String, sessionID: String) async throws {
        calls.append((chatID, sessionID)); try onAssociation?(chatID, sessionID)
        if fail { throw URLError(.timedOut) }
    }
}
