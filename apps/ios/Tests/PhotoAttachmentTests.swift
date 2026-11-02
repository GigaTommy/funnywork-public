import XCTest
import UIKit
@testable import FunnyWork

@MainActor final class PhotoAttachmentTests: XCTestCase {
    private var root: URL!
    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func photoURL() throws -> URL {
        let data = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        let url = root.appendingPathComponent("photo.bin")
        try data.write(to: url)
        return url
    }
    func testImageTypeComesFromBytesAndSurvivesTemporaryFileRemoval() async throws {
        let url = try photoURL()
        let photo = try await PhotoAttachment.read(url)
        try FileManager.default.removeItem(at: url)
        XCTAssertEqual(photo.file.mediaType, "image/png")
        XCTAssertEqual(photo.file.name, "photo.png")
        let store = ConversationStore(key: "account", root: root)
        try store.saveUpload(photo.data, file: photo.file, sessionID: "chat")
        XCTAssertEqual(try store.loadUpload(photo.file, sessionID: "chat"), photo.data)
    }
    func testUnreadablePhotoDoesNotChangeExistingTextOrFiles() async throws {
        let url = root.appendingPathComponent("broken.jpg")
        try Data("Not an image".utf8).write(to: url)
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let model = ConversationModel(sessionID: "chat", service: service,
            store: ConversationStore(key: "account", root: root), uploadService: UploadStub())
        await model.refresh(); model.updateDraft("Keep this")
        model.addAttachments([{ let photo = try await PhotoAttachment.read(url); return (photo.file, photo.data) }])
        while model.uploading { await Task.yield() }
        XCTAssertNotNil(model.uploadError); XCTAssertEqual(model.draft, "Keep this")
        XCTAssertTrue(model.files.isEmpty); XCTAssertTrue(service.sent.isEmpty)
    }
    func testCancelledPhotoLoadCannotAppendAfterPageDeactivation() async throws {
        let photo = try await PhotoAttachment.read(photoURL())
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let store = ConversationStore(key: "account", root: root)
        let uploads = UploadStub()
        let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await model.refresh(); model.updateDraft("Keep this")
        var continuation: CheckedContinuation<(OutgoingAttachment, Data), Never>?
        model.addAttachments([{ await withCheckedContinuation { continuation = $0 } }])
        while continuation == nil { await Task.yield() }
        XCTAssertTrue(model.importingAttachments)
        model.deactivate()
        continuation?.resume(returning: (photo.file, photo.data))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(model.files.isEmpty); XCTAssertTrue(try store.load("chat").files.isEmpty)
        XCTAssertEqual(model.draft, "Keep this"); XCTAssertTrue(uploads.offsets.isEmpty)
        XCTAssertFalse(model.importingAttachments)
        XCTAssertThrowsError(try store.loadUpload(photo.file, sessionID: "chat"))
    }
    func testPartialPhotoImportFailureKeepsImportedDraftAndCanResume() async throws {
        let photo = try await PhotoAttachment.read(photoURL())
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let store = ConversationStore(key: "account", root: root)
        let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: UploadStub())
        await model.refresh()
        model.addAttachments([{ (photo.file, photo.data) }, { throw URLError(.networkConnectionLost) }])
        while model.uploading { await Task.yield() }
        XCTAssertEqual(model.files.count, 1); XCTAssertFalse(model.files[0].uploaded)
        XCTAssertFalse(model.importingAttachments); XCTAssertNotNil(model.uploadError)
        XCTAssertEqual(try store.loadUpload(photo.file, sessionID: "chat"), photo.data)
        model.retryUploads()
        while model.uploading { await Task.yield() }
        XCTAssertTrue(model.validPrompt)
        model.addAttachments(Array(repeating: { (photo.file, photo.data) }, count: 4))
        XCTAssertFalse(model.uploading); XCTAssertNotNil(model.uploadError)
        XCTAssertEqual(model.files.count, 1)
    }
}
