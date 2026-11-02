import XCTest
import CryptoKit
@testable import FunnyWork

@MainActor final class SaveMessageImageTests: XCTestCase {
    private var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func fixture() async throws -> (ConversationStore, ChatAttachment, Data) {
        let service = AttachmentServiceStub(); service.filename = "photo.png"
        let store = ConversationStore(key: "account", root: root)
        let file = try ChatAttachment(service.row())
        let loader = AttachmentModel(service: service, store: store, sessionID: "chat")
        await loader.openReference(file); await loader.waitForDownload()
        return (store, file, service.payload)
    }
    func testSavesOriginalBytesOnlyAfterExplicitRequestAndDenialCanRetry() async throws {
        let (store, file, data) = try await fixture(), driver = ImageSavingStub()
        let model = SaveMessageImage(store: store, driver: driver)
        XCTAssertEqual(driver.authorizations, 0)
        driver.allowed = false; await model.save(file)
        XCTAssertTrue(model.needsSettings); XCTAssertNotNil(model.error); XCTAssertTrue(driver.writes.isEmpty)
        driver.allowed = true; await model.save(file)
        XCTAssertTrue(model.saved); XCTAssertFalse(model.needsSettings); XCTAssertNil(model.error)
        XCTAssertEqual(driver.writes.first?.0, data); XCTAssertEqual(driver.writes.first?.1, file.name)
    }
    func testLeavingDuringAuthorizationNeverWritesAndDuplicateTapsAreIgnored() async throws {
        let (store, file, _) = try await fixture(), driver = ImageSavingStub()
        driver.delayAuthorization = true
        let model = SaveMessageImage(store: store, driver: driver)
        let request = Task { await model.save(file) }
        while driver.authorization == nil { await Task.yield() }
        await model.save(file); XCTAssertEqual(driver.authorizations, 1)
        model.clear(); driver.authorization?.resume(returning: true); await request.value
        XCTAssertTrue(driver.writes.isEmpty); XCTAssertFalse(model.saved); XCTAssertFalse(model.busy)
    }
    func testCacheClearDuringPermissionAndAccountIsolationPreventWriting() async throws {
        let (store, file, _) = try await fixture(), driver = ImageSavingStub()
        let other = SaveMessageImage(store: ConversationStore(key: "other", root: root), driver: driver)
        await other.save(file); XCTAssertEqual(driver.authorizations, 0); XCTAssertNotNil(other.error)
        driver.delayAuthorization = true
        let model = SaveMessageImage(store: store, driver: driver), request = Task { await model.save(file) }
        while driver.authorization == nil { await Task.yield() }
        try store.clearDownloadedFiles(); driver.authorization?.resume(returning: true); await request.value
        XCTAssertTrue(driver.writes.isEmpty); XCTAssertFalse(model.saved)
    }
    func testSubmittedWriteRemainsLockedAndLateCompletionCannotConfirmAnotherImage() async throws {
        let (store, file, _) = try await fixture(), driver = ImageSavingStub()
        driver.delayWrite = true
        let model = SaveMessageImage(store: store, driver: driver), request = Task { await model.save(file) }
        while driver.write == nil { await Task.yield() }
        model.clear(); await model.save(file)
        XCTAssertTrue(model.busy); XCTAssertEqual(driver.writes.count, 1)
        driver.write?.resume(); await request.value
        XCTAssertFalse(model.saved); XCTAssertFalse(model.busy)
    }
    func testLogoutOrTaskCancellationDuringPermissionNeverWrites() async throws {
        for cancel in [false, true] {
            let (store, file, _) = try await fixture(), driver = ImageSavingStub()
            driver.delayAuthorization = true
            let model = SaveMessageImage(store: store, driver: driver), request = Task { await model.save(file) }
            while driver.authorization == nil { await Task.yield() }
            if cancel { request.cancel() } else { store.invalidate() }
            driver.authorization?.resume(returning: true); await request.value
            XCTAssertTrue(driver.writes.isEmpty); XCTAssertFalse(model.saved); XCTAssertFalse(model.busy)
        }
    }
    func testChangedOriginalDuringPermissionCannotBeSaved() async throws {
        let (store, file, _) = try await fixture(), driver = ImageSavingStub()
        let url = try XCTUnwrap(store.attachmentURL(file))
        driver.delayAuthorization = true
        let model = SaveMessageImage(store: store, driver: driver), request = Task { await model.save(file) }
        while driver.authorization == nil { await Task.yield() }
        try Data("changed".utf8).write(to: url)
        driver.authorization?.resume(returning: true); await request.value
        XCTAssertTrue(driver.writes.isEmpty); XCTAssertNotNil(model.error)
    }
    func testWriteFailureIsRetryableWithoutAutomaticReplay() async throws {
        let (store, file, _) = try await fixture(), driver = ImageSavingStub()
        driver.fail = true
        let model = SaveMessageImage(store: store, driver: driver)
        await model.save(file); XCTAssertNotNil(model.error); XCTAssertFalse(model.saved)
        XCTAssertEqual(driver.writes.count, 1)
        driver.fail = false; await model.save(file)
        XCTAssertTrue(model.saved); XCTAssertEqual(driver.writes.count, 2)
    }
}
@MainActor private final class ImageSavingStub: ImageSavingDriver {
    var allowed = true, fail = false, delayAuthorization = false, delayWrite = false
    var authorizations = 0
    var writes: [(Data, String)] = []
    var authorization: CheckedContinuation<Bool, Never>?
    var write: CheckedContinuation<Void, Never>?
    func authorize() async -> Bool {
        authorizations += 1
        if delayAuthorization { return await withCheckedContinuation { authorization = $0 } }
        return allowed
    }
    func save(_ data: Data, filename: String) async throws {
        writes.append((data, filename))
        if delayWrite { await withCheckedContinuation { write = $0 } }
        if fail { throw URLError(.cannotWriteToFile) }
    }
}
