import XCTest
@testable import FunnyWork

@MainActor final class UploadStub: UploadService {
    var offsets: [Int] = []
    var next: [String: Int] = [:]
    var verified = Set<String>()
    var loseFirstAck = false
    var delayed = false
    var continuation: CheckedContinuation<UploadStatus, Error>?
    var discarded: [String] = []
    var loseDiscardAck = false
    func prepare(_ file: OutgoingAttachment) async throws -> UploadStatus {
        try UploadStatus(["nextOffset": next[file.hash] ?? 0, "complete": verified.contains(file.hash)], file: file)
    }
    func chunk(_ file: OutgoingAttachment, offset: Int, data: Data) async throws -> UploadStatus {
        offsets.append(offset); next[file.hash] = offset + data.count
        if next[file.hash] == file.bytes { verified.insert(file.hash) }
        if loseFirstAck { loseFirstAck = false; throw URLError(.timedOut) }
        if delayed { return try await withCheckedThrowingContinuation { continuation = $0 } }
        return try await prepare(file)
    }
    func discard(_ file: OutgoingAttachment) async throws {
        discarded.append(file.hash); next[file.hash] = nil; verified.remove(file.hash)
        if loseDiscardAck { throw URLError(.timedOut) }
    }
}

@MainActor final class UploadTests: XCTestCase {
    func testDraftPreviewUsesVerifiedBytesAndDoesNotDeleteDraft() throws {
        let data = Data("Draft preview".utf8)
        let file = try OutgoingAttachment(data: data, name: "notes.txt", mediaType: "text/plain")
        let store = ConversationStore(key: "account", root: root)
        try store.saveUpload(data, file: file, sessionID: "chat")
        let preview = try store.prepareUploadPreview(file, sessionID: "chat")
        XCTAssertEqual(preview.lastPathComponent, "notes.txt")
        XCTAssertEqual(try Data(contentsOf: preview), data)
        #if !targetEnvironment(simulator)
        // The Simulator does not expose iOS data-protection attributes.
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: preview.path)[.protectionKey] as? String, FileProtectionType.complete.rawValue)
        #endif
        XCTAssertEqual(try preview.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        XCTAssertThrowsError(try ConversationStore(key: "other", root: root).prepareUploadPreview(file, sessionID: "chat"))
        XCTAssertThrowsError(try store.prepareUploadPreview(file, sessionID: "another-chat"))
        store.clearUploadPreview(); XCTAssertFalse(FileManager.default.fileExists(atPath: preview.path))
        XCTAssertEqual(try store.loadUpload(file, sessionID: "chat"), data)
        let second = try store.prepareUploadPreview(file, sessionID: "chat")
        _ = ConversationStore(key: "account", root: root)
        XCTAssertFalse(FileManager.default.fileExists(atPath: second.path))
        _ = try store.prepareUploadPreview(file, sessionID: "chat")
        try store.invalidateAndClear(); XCTAssertFalse(FileManager.default.fileExists(atPath: second.path))
        XCTAssertThrowsError(try store.prepareUploadPreview(file, sessionID: "chat"))
    }
    func testDraftPreviewRejectsCorruptedOriginal() throws {
        let data = Data("Preview".utf8)
        let file = try OutgoingAttachment(data: data, name: "notes.txt", mediaType: "text/plain")
        let store = ConversationStore(key: "account", root: root)
        try store.saveUpload(data, file: file, sessionID: "chat")
        let files = try FileManager.default.contentsOfDirectory(at: root.appendingPathComponent("account"), includingPropertiesForKeys: nil)
        let original = try XCTUnwrap(files.first { $0.lastPathComponent.hasPrefix("upload-") })
        try Data("Changed".utf8).write(to: original)
        XCTAssertThrowsError(try store.prepareUploadPreview(file, sessionID: "chat"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("account/DraftPreview").path))
    }
    private var root: URL!
    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func file(_ count: Int = 10) throws -> URL {
        let url = root.appendingPathComponent("notes.txt")
        try Data(repeating: 65, count: count).write(to: url); return url
    }
    private func wait(_ model: ConversationModel) async {
        while model.uploading { await Task.yield() }
    }
    func testLostUploadAcknowledgementResumesAndAttachmentOnlyPromptIsDurable() async throws {
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let uploads = UploadStub(); uploads.loseFirstAck = true
        let store = ConversationStore(key: "account", root: root)
        let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await model.refresh(); model.addFiles([try file(400001)]); await wait(model)
        XCTAssertFalse(model.validPrompt); XCTAssertNotNil(model.uploadError)
        let attached = try XCTUnwrap(model.files.first)
        XCTAssertEqual(uploads.offsets, [0])
        let restored = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await restored.refresh(); restored.retryUploads(); await wait(restored)
        XCTAssertEqual(uploads.offsets, [0, 384000]); XCTAssertTrue(restored.validPrompt)
        await restored.prompt()
        let sent = try XCTUnwrap(service.sent.first)
        XCTAssertEqual(sent.payload["attachments"], .array([attached.payload]))
        XCTAssertTrue(restored.files.isEmpty)
        XCTAssertTrue(try store.load("chat").files.isEmpty)
        XCTAssertThrowsError(try store.loadUpload(attached, sessionID: "chat"))
        let rejected = CommandReceipt(["id": sent.id, "status": "rejected"])!
        restored.restoreDraft(rejected)
        XCTAssertEqual(restored.files.map(\.hash), [attached.hash])
        restored.retryUploads(); await wait(restored)
        XCTAssertTrue(restored.validPrompt)
        XCTAssertEqual(uploads.offsets, [0, 384000])
    }
    func testCancelledUploadKeepsDraftAndIgnoresLateSuccess() async throws {
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let uploads = UploadStub(); uploads.delayed = true
        let store = ConversationStore(key: "account", root: root)
        let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await model.refresh(); model.updateDraft("Keep this")
        model.addFiles([try file()])
        while uploads.continuation == nil { await Task.yield() }
        let attached = try XCTUnwrap(model.files.first)
        model.deactivate()
        uploads.continuation?.resume(returning: try UploadStatus(["nextOffset": attached.bytes, "complete": true], file: attached))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertFalse(model.uploading); XCTAssertFalse(model.files[0].uploaded)
        XCTAssertEqual(try store.load("chat").draft, "Keep this")
        XCTAssertFalse(try store.load("chat").files[0].uploaded)
        XCTAssertTrue(service.sent.isEmpty)
    }
    func testEmptyFileCanUploadAndRemovalClearsLocalDraftOnlyAfterServerConfirmation() async throws {
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let uploads = UploadStub(); let store = ConversationStore(key: "account", root: root)
        let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await model.refresh(); model.addFiles([try file(0)]); await wait(model)
        let attached = try XCTUnwrap(model.files.first)
        XCTAssertTrue(attached.uploaded); XCTAssertTrue(model.validPrompt)
        service.remote.attachmentUploads = false; await model.refresh()
        XCTAssertFalse(model.validPrompt)
        model.removeFile(attached); await wait(model)
        XCTAssertTrue(model.files.isEmpty); XCTAssertEqual(uploads.discarded, [attached.hash])
        XCTAssertThrowsError(try store.loadUpload(attached, sessionID: "chat"))
    }
    func testLostRemovalConfirmationRetainsBytesAndRequiresReupload() async throws {
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let uploads = UploadStub(); let store = ConversationStore(key: "account", root: root)
        let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await model.refresh(); model.addFiles([try file()]); await wait(model)
        let attached = try XCTUnwrap(model.files.first)
        uploads.loseDiscardAck = true
        model.removeFile(attached); await wait(model)
        XCTAssertFalse(model.validPrompt); XCTAssertNotNil(model.uploadError)
        XCTAssertEqual(try store.loadUpload(attached, sessionID: "chat").count, attached.bytes)
        model.retryUploads(); await wait(model)
        XCTAssertTrue(model.validPrompt); XCTAssertEqual(uploads.offsets, [0, 0])
    }
    func testLimitsAndUploadResponsesRejectInvalidState() async throws {
        let data = Data([65]); let attached = try OutgoingAttachment(data: data, name: "notes.txt", mediaType: "text/plain")
        XCTAssertThrowsError(try UploadStatus(["nextOffset": 1, "complete": false], file: attached))
        XCTAssertThrowsError(try UploadStatus(["nextOffset": 0, "complete": true], file: attached))
        XCTAssertThrowsError(try UploadStatus(["nextOffset": 1, "complete": true, "metadata": [:]], file: attached, metadata: true))
        let store = ConversationStore(key: "account", root: root)
        try store.saveUpload(data, file: attached, sessionID: "chat")
        XCTAssertThrowsError(try ConversationStore(key: "other", root: root).loadUpload(attached, sessionID: "chat"))
        let service = StubConversationService(); service.remote.attachmentUploads = true
        let uploads = UploadStub(); let model = ConversationModel(sessionID: "chat", service: service, store: store, uploadService: uploads)
        await model.refresh(); model.addFiles(Array(repeating: try file(), count: 5))
        XCTAssertTrue(model.files.isEmpty); XCTAssertNotNil(model.uploadError)
        try store.invalidateAndClear()
        XCTAssertThrowsError(try store.saveUpload(data, file: attached, sessionID: "chat"))
    }
}
