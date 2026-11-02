import XCTest
import UIKit
@testable import FunnyWork

@MainActor final class MessageImageGalleryTests: XCTestCase {
    private var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func imageService() -> AttachmentServiceStub {
        let service = AttachmentServiceStub(); service.filename = "photo.png"; service.chunkSize = 384000
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        service.payload = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 1500), format: format).pngData { context in
            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 3000, height: 1500))
        }
        return service
    }
    func testGalleryContainsOnlyMessageImagesInOrderWithoutDuplicates() throws {
        let service = imageService(); let photo = try ChatAttachment(service.row())
        let second = try ChatAttachment(reference: ["attachmentId": "sha256:" + String(repeating: "b", count: 64), "name": "other.jpg", "bytes": 100])
        let document = try ChatAttachment(reference: ["attachmentId": "sha256:" + String(repeating: "c", count: 64), "name": "notes.pdf", "bytes": 100])
        let selected = MessageImageSelection(selected: second, attachments: [photo, document, second, photo])
        XCTAssertEqual(selected.images.map(\.hash), [photo.hash, second.hash]); XCTAssertEqual(selected.id, second.hash)
    }
    func testVerifiedImageIsBoundedAndCanReopenOfflineWithOriginalShareFile() async throws {
        let service = imageService(); let file = try ChatAttachment(service.row())
        let store = ConversationStore(key: "account", root: root), model = GalleryImageModel(service: service, store: ConversationStore(key: "account", root: root), sessionID: "chat")
        await model.load(file)
        let image = try XCTUnwrap(model.image), url = try XCTUnwrap(model.url)
        XCTAssertEqual(image.size.width, 2560); XCTAssertEqual(image.size.width / image.size.height, 2, accuracy: 0.01)
        XCTAssertEqual(try Data(contentsOf: url), service.payload)
        let calls = service.requests.count; service.fail = true; await model.load(file)
        XCTAssertNotNil(model.image); XCTAssertEqual(service.requests.count, calls)
        let other = GalleryImageModel(service: service, store: ConversationStore(key: "other", root: root), sessionID: "chat")
        await other.load(file); XCTAssertNil(other.url); XCTAssertNil(other.image); XCTAssertNotNil(other.error)
        try store.clearDownloadedFiles(); await model.load(file)
        XCTAssertNil(model.image); XCTAssertNil(model.url)
    }
    func testLeavingOrChangingImagesRejectsLateLookupAndCancelledDecode() async throws {
        let service = imageService(); service.delayLookup = true
        let file = try ChatAttachment(service.row()), store = ConversationStore(key: "account", root: root)
        let model = GalleryImageModel(service: service, store: store, sessionID: "chat")
        let loading = Task { await model.load(file) }
        while service.lookupContinuation == nil { await Task.yield() }
        model.clear(); service.lookupContinuation?.resume(returning: file); await loading.value
        XCTAssertNil(model.image); XCTAssertNil(model.url); XCTAssertFalse(model.loading); XCTAssertNil(model.error)
        service.delayLookup = false; service.delayChunk = true
        let download = Task { await model.load(file) }
        while service.chunkContinuation == nil { await Task.yield() }
        download.cancel()
        let chunk = try AttachmentChunk(["data": service.payload.base64EncodedString(), "nextOffset": service.payload.count, "complete": true], offset: 0, expected: service.payload.count)
        service.chunkContinuation?.resume(returning: chunk); await download.value
        XCTAssertNil(model.image); XCTAssertNil(model.url); XCTAssertNil(try store.attachmentURL(file))
    }
    func testNonImageBytesRemainShareableButCannotDisplayAndCorruptionIsRejected() async throws {
        let service = AttachmentServiceStub(); service.filename = "photo.png"
        let file = try ChatAttachment(service.row()), store = ConversationStore(key: "account", root: root)
        let model = GalleryImageModel(service: service, store: store, sessionID: "chat")
        await model.load(file); XCTAssertNil(model.image); XCTAssertNotNil(model.url); XCTAssertNotNil(model.error)
        try store.clearDownloadedFiles(); service.corrupt = true
        await model.load(file); XCTAssertNil(model.image); XCTAssertNil(model.url); XCTAssertNotNil(model.error)
    }
    func testZoomFitsPortraitLandscapeAndResetsAfterResize() {
        let image = UIImage(systemName: "photo")!
        let view = MessageImageScrollView(image: image, name: "photo", move: { _ in })
        view.frame = CGRect(x: 0, y: 0, width: 390, height: 600); view.layoutIfNeeded()
        XCTAssertEqual(view.zoomScale, view.minimumZoomScale, accuracy: 0.001)
        view.setZoomScale(view.minimumZoomScale * 3, animated: false)
        XCTAssertEqual(view.accessibilityValue, "300%")
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 380); view.layoutIfNeeded()
        XCTAssertEqual(view.accessibilityValue, "100%")
        XCTAssertEqual(view.maximumZoomScale, view.minimumZoomScale * 6, accuracy: 0.001)
    }
}
