import XCTest
import UIKit
import CryptoKit
import UniformTypeIdentifiers
@testable import FunnyWork

@MainActor final class CopyMessageImageTests: XCTestCase {
    private var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func fixture(data: Data? = nil) throws -> (ConversationStore, ChatAttachment, Data) {
        let bytes = data ?? UIGraphicsImageRenderer(size: CGSize(width: 12, height: 8)).pngData { context in
            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 12, height: 8))
        }
        let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let file = try ChatAttachment(["hash": hash, "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + hash, "name": "photo.png", "bytes": bytes.count]]])
        let store = ConversationStore(key: UUID().uuidString, root: root)
        _ = try store.saveAttachment(bytes, item: file)
        return (store, file, bytes)
    }
    func testExplicitCopyWritesOriginalImageToNativePasteboard() async throws {
        let (store, file, data) = try fixture()
        let name = UIPasteboard.Name(UUID().uuidString), board = try XCTUnwrap(UIPasteboard(name: name, create: true))
        defer { UIPasteboard.remove(withName: name) }
        let model = CopyMessageImage(store: store, driver: SystemImageClipboardDriver(pasteboard: board))
        XCTAssertTrue(board.items.isEmpty)
        await model.copy(file)
        XCTAssertTrue(model.copied); XCTAssertFalse(model.busy); XCTAssertNil(model.error)
        XCTAssertEqual(board.data(forPasteboardType: UTType.png.identifier), data)
        XCTAssertEqual(board.items.count, 1)
    }
    func testDisguisedImageAndChangedBytesNeverReplaceClipboard() async throws {
        let (store, file, _) = try fixture(data: Data("not an image".utf8)), driver = ClipboardStub()
        let invalid = CopyMessageImage(store: store, driver: driver)
        await invalid.copy(file); XCTAssertNotNil(invalid.error); XCTAssertTrue(driver.writes.isEmpty)
        let (validStore, validFile, _) = try fixture()
        let changed = CopyMessageImage(store: validStore, driver: driver, read: { _ in Data("changed".utf8) })
        await changed.copy(validFile); XCTAssertNotNil(changed.error); XCTAssertTrue(driver.writes.isEmpty)
    }
    func testAnotherAccountCannotCopyCachedImage() async throws {
        let (_, file, _) = try fixture(), driver = ClipboardStub()
        let model = CopyMessageImage(store: ConversationStore(key: "other", root: root), driver: driver)
        await model.copy(file)
        XCTAssertFalse(model.copied); XCTAssertNotNil(model.error); XCTAssertTrue(driver.writes.isEmpty)
    }
    func testLeavingCacheClearLogoutAndCancellationPreventLateClipboardWrites() async throws {
        for action in 0..<4 {
            let (store, file, data) = try fixture(), driver = ClipboardStub(), reader = ClipboardReadStub()
            let model = CopyMessageImage(store: store, driver: driver, read: { _ in await reader.read() })
            let request = Task { await model.copy(file) }
            while reader.pending == nil { await Task.yield() }
            switch action {
            case 0: model.clear()
            case 1: try store.clearDownloadedFiles()
            case 2: store.invalidate()
            default: request.cancel()
            }
            reader.pending?.resume(returning: data); await request.value
            XCTAssertTrue(driver.writes.isEmpty); XCTAssertFalse(model.copied); XCTAssertFalse(model.busy)
        }
    }
    func testDuplicateTapIsIgnoredAndFailureRequiresExplicitRetry() async throws {
        let (store, file, data) = try fixture(), driver = ClipboardStub(), reader = ClipboardReadStub()
        let model = CopyMessageImage(store: store, driver: driver, read: { _ in await reader.read() })
        let request = Task { await model.copy(file) }
        while reader.pending == nil { await Task.yield() }
        await model.copy(file); XCTAssertEqual(reader.calls, 1)
        reader.pending?.resume(returning: Data("changed".utf8)); await request.value
        XCTAssertNotNil(model.error); XCTAssertTrue(driver.writes.isEmpty)
        reader.pending = nil
        let retry = Task { await model.copy(file) }
        while reader.pending == nil { await Task.yield() }
        reader.pending?.resume(returning: data); await retry.value
        XCTAssertEqual(reader.calls, 2); XCTAssertEqual(driver.writes.count, 1); XCTAssertTrue(model.copied)
    }
}
@MainActor private final class ClipboardStub: ImageClipboardDriver {
    var writes: [(Data, String)] = []
    func write(_ data: Data, type: String) { writes.append((data, type)) }
}
@MainActor private final class ClipboardReadStub {
    var calls = 0
    var pending: CheckedContinuation<Data, Never>?
    func read() async -> Data { calls += 1; return await withCheckedContinuation { pending = $0 } }
}
