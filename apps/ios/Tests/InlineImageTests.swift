import XCTest
import UIKit
@testable import FunnyWork

@MainActor final class InlineImageTests: XCTestCase {
    private var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func service() -> AttachmentServiceStub {
        let service = AttachmentServiceStub(); service.filename = "photo.png"; service.chunkSize = 384000
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        service.payload = UIGraphicsImageRenderer(size: CGSize(width: 1600, height: 800), format: format).pngData { context in
            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 1600, height: 800))
        }
        return service
    }
    func testVerifiedImageIsDownsampledAndReopensOffline() async throws {
        let service = service(); let file = try ChatAttachment(service.row())
        let store = ConversationStore(key: "account", root: root)
        let model = InlineImagePreview()
        await model.load(file, service: service, store: store, sessionID: "chat")
        let image = try XCTUnwrap(model.image)
        XCTAssertLessThanOrEqual(image.size.width, 720); XCTAssertEqual(image.size.width / image.size.height, 2, accuracy: 0.01)
        let calls = service.requests.count
        service.fail = true; model.cancel(); XCTAssertNil(model.image)
        await model.load(file, service: service, store: store, sessionID: "chat")
        XCTAssertNotNil(model.image); XCTAssertEqual(service.requests.count, calls)
        await model.load(file, service: service, store: ConversationStore(key: "other", root: root), sessionID: "chat")
        XCTAssertNil(model.image)
    }
    func testCorruptImageAndCancelledDownloadCannotPublishThumbnail() async throws {
        let service = service(); let file = try ChatAttachment(service.row())
        let store = ConversationStore(key: "account", root: root)
        let model = InlineImagePreview(); service.corrupt = true
        await model.load(file, service: service, store: store, sessionID: "chat")
        XCTAssertNil(model.image)
        service.corrupt = false; service.pause = true; service.requests = []
        let task = Task { await model.load(file, service: service, store: store, sessionID: "chat") }
        while service.requests.isEmpty { await Task.yield() }
        model.cancel(); task.cancel(); await task.value
        XCTAssertNil(model.image); XCTAssertFalse(model.loading)
    }
    func testLargeImagesAndNonImagesDoNotAutomaticallyRequestData() async throws {
        let service = service(); let store = ConversationStore(key: "account", root: root)
        let large = try ChatAttachment(reference: ["attachmentId": "sha256:" + service.hash, "name": "large.png", "bytes": InlineImagePreview.automaticLimit + 1])
        let model = InlineImagePreview()
        await model.load(large, service: service, store: store, sessionID: "chat")
        let document = try ChatAttachment(service.row(name: "notes.txt"))
        await model.load(document, service: service, store: store, sessionID: "chat")
        XCTAssertEqual(service.lookups, 0); XCTAssertTrue(service.requests.isEmpty)
    }
    func testCancelledWaiterDoesNotConsumePreviewSlot() async throws {
        let slots = ImagePreviewSlots()
        try await slots.acquire(); try await slots.acquire()
        let waiting = Task { try await slots.acquire() }
        for _ in 0..<10 { await Task.yield() }
        waiting.cancel()
        do { try await waiting.value; XCTFail("Cancelled work acquired a slot") } catch { XCTAssertTrue(error is CancellationError) }
        await slots.release(); await slots.release()
        try await slots.acquire(); try await slots.acquire()
        await slots.release(); await slots.release()
    }
}
