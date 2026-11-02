import XCTest
import CryptoKit
@testable import FunnyWork

@MainActor final class AttachmentServiceStub: AttachmentService {
    var payload = Data("Hello files".utf8)
    var filename = "result.txt"
    var chunkSize = 4
    var fail = false
    var corrupt = false
    var pause = false
    var delayLookup = false
    var delayChunk = false
    var chunkContinuation: CheckedContinuation<AttachmentChunk, Error>?
    var lookupContinuation: CheckedContinuation<ChatAttachment?, Error>?
    var requests: [Int] = []
    var lookups = 0
    var hash: String { SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined() }
    func row(name: String? = nil) -> [String: Any] {
        ["hash": hash, "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + hash, "bytes": payload.count, "name": name ?? filename]]]
    }
    func page(after: String) async throws -> AttachmentPage {
        if fail { throw URLError(.notConnectedToInternet) }
        return try AttachmentPage(["items": after.isEmpty ? [row()] : [], "after": ""], after: after)
    }
    func lookup(hash: String) async throws -> ChatAttachment? {
        lookups += 1
        if delayLookup { return try await withCheckedThrowingContinuation { lookupContinuation = $0 } }
        return try await page(after: "").items.first(where: { $0.hash == hash })
    }
    func chunk(_ attachment: ChatAttachment, offset: Int) async throws -> AttachmentChunk {
        requests.append(offset)
        if delayChunk { return try await withCheckedThrowingContinuation { chunkContinuation = $0 } }
        if pause { try await Task.sleep(for: .seconds(10)) }
        if fail { throw URLError(.notConnectedToInternet) }
        let count = min(chunkSize, payload.count - offset)
        let data = corrupt ? Data(repeating: 42, count: count) : payload.subdata(in: offset..<(offset + count))
        return try AttachmentChunk(["data": data.base64EncodedString(), "nextOffset": offset + count, "complete": offset + count == payload.count], offset: offset, expected: payload.count)
    }
}

@MainActor final class AttachmentTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func store(_ key: String = "account") -> ConversationStore { ConversationStore(key: key, root: root) }
    func testClearDownloadsPreservesDraftsHistoryAndOtherAccounts() async throws {
        let service = AttachmentServiceStub(); let file = try ChatAttachment(service.row())
        let cache = store(); let other = store("other")
        _ = try cache.saveAttachment(service.payload, item: file)
        _ = try other.saveAttachment(service.payload, item: file)
        var saved = SavedConversation(); saved.draft = "Keep this"
        saved.pending = RemoteCommand(epoch: "epoch", kind: "prompt", payload: ["text": .string(saved.draft)])
        let upload = try OutgoingAttachment(data: service.payload, name: "draft.txt", mediaType: "text/plain")
        saved.files = [upload]
        try cache.saveUpload(service.payload, file: upload, sessionID: "chat"); try cache.save(saved, for: "chat")
        let message = HistoryItem(["seq": 0, "data": ["content": [["text": "Saved message"]]]])!
        try cache.saveHistory(CachedHistory(items: [message], latest: 1), for: "chat")
        try cache.saveAttachments(items: [file], after: "", sessionID: "chat")
        XCTAssertEqual(try cache.downloadedFileUsage().bytes, service.payload.count)
        XCTAssertEqual(try cache.downloadedFileUsage().count, 1)
        try cache.clearDownloadedFiles()
        XCTAssertEqual(try cache.downloadedFileUsage().count, 0)
        XCTAssertNil(try cache.attachmentURL(file)); XCTAssertNotNil(try other.attachmentURL(file))
        XCTAssertEqual(try cache.load("chat"), saved)
        XCTAssertEqual(try cache.loadUpload(upload, sessionID: "chat"), service.payload)
        XCTAssertEqual(try cache.loadHistory("chat")?.items, [message])
        XCTAssertEqual(try cache.loadAttachments("chat")?.items, [file])
        try cache.clearDownloadedFiles(); cache.invalidate()
        XCTAssertThrowsError(try cache.clearDownloadedFiles())
        XCTAssertThrowsError(try cache.downloadedFileUsage())
    }
    func testClearDuringDownloadRejectsLateCacheWrite() async throws {
        let service = AttachmentServiceStub(); service.delayChunk = true
        let file = try ChatAttachment(service.row()); let cache = store()
        let model = AttachmentModel(service: service, store: cache, sessionID: "chat")
        model.open(file)
        while service.chunkContinuation == nil { await Task.yield() }
        try cache.clearDownloadedFiles()
        service.chunkContinuation?.resume(returning: try AttachmentChunk(["data": service.payload.base64EncodedString(), "nextOffset": file.bytes, "complete": true], offset: 0, expected: file.bytes))
        await model.waitForDownload()
        XCTAssertNil(model.preview); XCTAssertEqual(try cache.downloadedFileUsage().count, 0)
        service.delayChunk = false; model.open(file); await model.waitForDownload()
        XCTAssertNotNil(model.preview)
    }
    func testMessageReferenceOpensVerifiedFileAndReopensWithoutNetwork() async throws {
        let service = AttachmentServiceStub(); let cache = store()
        let file = try ChatAttachment(service.row())
        let reference = try ChatAttachment(reference: ["attachmentId": "sha256:" + file.hash, "name": file.name, "bytes": file.bytes])
        let model = AttachmentModel(service: service, store: cache, sessionID: "chat")
        await model.openReference(reference); await model.waitForDownload()
        XCTAssertNotNil(model.preview); XCTAssertEqual(model.items.map(\.id), [reference.id])
        XCTAssertNil(try cache.loadAttachments("chat"))
        service.fail = true
        let offline = AttachmentModel(service: service, store: cache, sessionID: "chat")
        await offline.openReference(reference)
        XCTAssertNotNil(offline.preview); XCTAssertNil(offline.error)
        XCTAssertEqual(service.requests, [0, 4, 8])
        let other = AttachmentModel(service: service, store: store("other"), sessionID: "chat")
        await other.openReference(reference)
        XCTAssertNil(other.preview); XCTAssertNotNil(other.error)
    }
    func testCancelledMessageLookupCannotStartDownloadAfterDismissal() async throws {
        let service = AttachmentServiceStub(); service.delayLookup = true
        let reference = try ChatAttachment(service.row())
        let model = AttachmentModel(service: service, store: store(), sessionID: "chat")
        let task = Task { await model.openReference(reference) }
        while service.lookupContinuation == nil { await Task.yield() }
        model.cancel()
        service.lookupContinuation?.resume(returning: reference)
        await task.value
        XCTAssertNil(model.preview); XCTAssertNil(model.downloading)
        XCTAssertTrue(service.requests.isEmpty)
    }
    func testMessageReferenceRejectsSizeMismatchAndRetriesLookup() async throws {
        let service = AttachmentServiceStub(); let cache = store()
        let reference = try ChatAttachment(reference: ["attachmentId": "sha256:" + service.hash, "name": "notes.txt", "bytes": 999])
        let model = AttachmentModel(service: service, store: cache, sessionID: "chat")
        await model.openReference(reference)
        XCTAssertNotNil(model.error); XCTAssertNil(model.preview); XCTAssertTrue(service.requests.isEmpty)
        await model.retry()
        XCTAssertTrue(service.requests.isEmpty)
        let valid = try ChatAttachment(service.row())
        service.fail = true; await model.openReference(valid)
        XCTAssertNotNil(model.error)
        service.fail = false; await model.retry(); await model.waitForDownload()
        XCTAssertNotNil(model.preview); XCTAssertNil(model.error)
    }
    func testDownloadChecksHashAndCanReopenOffline() async throws {
        let service = AttachmentServiceStub(); let store = store()
        let model = AttachmentModel(service: service, store: store, sessionID: "chat")
        await model.refresh()
        let item = try XCTUnwrap(model.items.first)
        model.open(item); await model.waitForDownload()
        XCTAssertEqual(service.requests, [0, 4, 8])
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(model.preview)), service.payload)
        service.fail = true
        let restored = AttachmentModel(service: service, store: store, sessionID: "chat")
        XCTAssertTrue(restored.cached)
        await restored.refresh()
        XCTAssertEqual(restored.items.count, 1)
        restored.open(item)
        XCTAssertNotNil(restored.preview)
        XCTAssertEqual(service.requests.count, 3)
        XCTAssertNil(try self.store("other").attachmentURL(item))
        try store.invalidateAndClear()
        XCTAssertThrowsError(try store.attachmentURL(item))
    }
    func testCorruptDownloadNeverBecomesPreviewAndRetryWorks() async throws {
        let service = AttachmentServiceStub(); service.corrupt = true
        let cache = store(); let model = AttachmentModel(service: service, store: cache, sessionID: "chat")
        await model.refresh(); let item = try XCTUnwrap(model.items.first)
        model.open(item); await model.waitForDownload()
        XCTAssertNil(model.preview); XCTAssertNotNil(model.error)
        XCTAssertNil(try cache.attachmentURL(item))
        service.corrupt = false; model.open(item); await model.waitForDownload()
        XCTAssertNotNil(model.preview); XCTAssertNil(model.error)
    }
    func testCancellationAndInvalidatedStoreCannotPublish() async throws {
        let service = AttachmentServiceStub(); service.pause = true
        let cache = store(); let model = AttachmentModel(service: service, store: cache, sessionID: "chat")
        await model.refresh(); let item = try XCTUnwrap(model.items.first)
        model.open(item); model.cancel(); await model.waitForDownload()
        XCTAssertNil(model.preview); XCTAssertNil(model.downloading)
        XCTAssertNil(try cache.attachmentURL(item))
        service.pause = false
        try cache.invalidateAndClear()
        model.open(item); await model.waitForDownload()
        XCTAssertNil(model.preview)
        XCTAssertTrue(service.requests.isEmpty)
    }
    func testEmptyFileAndUnsafeNames() async throws {
        let service = AttachmentServiceStub(); service.payload = Data()
        let item = try ChatAttachment(service.row(name: "../../report.txt"))
        XCTAssertFalse(item.name.contains("/")); XCTAssertFalse(item.name.hasPrefix("."))
        let model = AttachmentModel(service: service, store: store(), sessionID: "chat")
        model.open(item); await model.waitForDownload()
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(model.preview)), Data())
    }
    func testInvalidChunksAndPaginationAreRejected() throws {
        XCTAssertThrowsError(try AttachmentChunk(["data": "!", "nextOffset": 1, "complete": true], offset: 0, expected: 1))
        XCTAssertThrowsError(try AttachmentChunk(["data": "YQ==", "nextOffset": 2, "complete": true], offset: 0, expected: 1))
        XCTAssertThrowsError(try AttachmentChunk(["data": "", "nextOffset": 0, "complete": false], offset: 0, expected: 1))
        let service = AttachmentServiceStub()
        XCTAssertThrowsError(try AttachmentPage(["items": [service.row(), service.row()], "after": ""], after: ""))
        XCTAssertThrowsError(try AttachmentPage(["items": [service.row()], "after": "../escape"], after: ""))
    }
    func testLocalFileTamperingTriggersRedownload() async throws {
        let service = AttachmentServiceStub(); let cache = store()
        let model = AttachmentModel(service: service, store: cache, sessionID: "chat")
        let item = try ChatAttachment(service.row())
        let url = try cache.saveAttachment(service.payload, item: item)
        try Data(repeating: 0, count: service.payload.count).write(to: url)
        model.open(item); await model.waitForDownload()
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(model.preview)), service.payload)
        XCTAssertFalse(service.requests.isEmpty)
    }
    func testCacheEvictsOldFilesWithoutTouchingDrafts() throws {
        let cache = store()
        var draft = SavedConversation(); draft.draft = "Keep this"
        try cache.save(draft, for: "chat")
        var items: [ChatAttachment] = []
        let service = AttachmentServiceStub()
        for index in 0..<11 {
            service.payload = Data("File \(index)".utf8)
            let item = try ChatAttachment(service.row(name: "file-\(index).txt"))
            _ = try cache.saveAttachment(service.payload, item: item)
            items.append(item)
        }
        XCTAssertEqual(try items.compactMap { try cache.attachmentURL($0) }.count, 10)
        XCTAssertNotNil(try cache.attachmentURL(items.last!))
        XCTAssertEqual(try cache.load("chat").draft, "Keep this")
    }

}
