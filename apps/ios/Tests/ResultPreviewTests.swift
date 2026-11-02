import XCTest
import CryptoKit
@testable import FunnyWork

@MainActor final class ResultPreviewServiceStub: AttachmentService, ResultService {
    var data: [String: Data] = [:]
    var names: [String: String] = [:]
    var versionsValue: [ResultRecord] = []
    var incomplete: Set<String> = []
    var requested: [(String, Int)] = []
    var pauseHash: String?
    var delayed = false
    var versionContinuation: CheckedContinuation<[ResultRecord], Error>?
    var delayVersions = false
    func add(_ name: String, _ text: String) -> ResultFile {
        let bytes = Data(text.utf8), hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        data[hash] = bytes; names[hash] = name
        return ResultFile(type: "file", attachment: .init(attachmentId: "sha256:" + hash, name: name, bytes: bytes.count))
    }
    func page(after: String) async throws -> AttachmentPage {
        let rows: [[String: Any]] = data.map { hash, bytes in ["hash": hash, "complete": !incomplete.contains(hash), "metadata": ["attachment": ["attachmentId": "sha256:" + hash, "name": names[hash]!, "bytes": bytes.count]]] }
        return try AttachmentPage(["items": rows.sorted { ($0["hash"] as! String) < ($1["hash"] as! String) }, "after": ""], after: after)
    }
    func chunk(_ attachment: ChatAttachment, offset: Int) async throws -> AttachmentChunk {
        requested.append((attachment.hash, offset))
        if attachment.hash == pauseHash, offset == 4, !delayed { delayed = true; try await Task.sleep(for: .seconds(60)) }
        let bytes = data[attachment.hash]!, end = min(bytes.count, offset + 4)
        return try AttachmentChunk(["data": bytes.subdata(in: offset..<end).base64EncodedString(), "nextOffset": end, "complete": end == bytes.count], offset: offset, expected: bytes.count)
    }
    func versions(_ id: String) async throws -> [ResultRecord] {
        if delayVersions { return try await withCheckedThrowingContinuation { versionContinuation = $0 } }
        return versionsValue
    }
    func directory() async throws -> [ResultRecord] { versionsValue }
    func version(_ original: ResultFile, variants: [ResultVariant] = [], state: String = "ready", number: Int = 1) -> ResultVersion {
        ResultVersion(schema: 1, id: "result", version: number, previousHash: number == 1 ? nil : String(original.attachment.attachmentId.dropFirst(7)), original: original, variants: variants, previewState: state, warnings: [])
    }
    func record(_ value: ResultVersion) -> ResultRecord {
        var transfers = ["original": ResultTransfer(nextOffset: value.original.attachment.bytes, complete: true)]
        for variant in value.variants { transfers[variant.key] = ResultTransfer(nextOffset: variant.content.attachment.bytes, complete: !incomplete.contains(variant.content.file!.hash)) }
        return ResultRecord(manifest: value, eventSeq: value.version, createdAt: 1, transfers: transfers)
    }
}
@MainActor final class ResultPreviewTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func model(_ service: ResultPreviewServiceStub, _ version: ResultVersion) -> ResultPreviewModel {
        let loader = AttachmentModel(service: service, store: ConversationStore(key: "owner", root: root), sessionID: "session")
        return ResultPreviewModel(result: version, download: loader, service: service)
    }
    func testLatePreviewOpensAndExplicitOriginalSelectionIsPreserved() async throws {
        let service = ResultPreviewServiceStub(), original = service.add("report.docx", "original"), pdf = service.add("preview.pdf", "%PDF-1.7 preview")
        let first = service.version(original, state: "pending"), ready = service.version(original, variants: [ResultVariant(role: "pdf", key: "pdf", content: pdf)])
        let preview = model(service, first); await preview.openDefault(); XCTAssertEqual(preview.selectedKey, "original")
        service.versionsValue = [service.record(ready)]; await preview.refresh()
        XCTAssertEqual(preview.selectedKey, "pdf"); XCTAssertEqual(preview.download.preview?.pathExtension, "pdf")
        await preview.openOriginal(); await preview.refresh()
        XCTAssertEqual(preview.selectedKey, "original"); XCTAssertEqual(preview.download.preview?.pathExtension, "docx")
    }
    func testPreviewDeclarationBeforeUploadRetriesWhenTransferFinishes() async throws {
        let service = ResultPreviewServiceStub(), original = service.add("report.docx", "original"), pdf = service.add("preview.pdf", "%PDF-1.7 preview")
        let ready = service.version(original, variants: [ResultVariant(role: "pdf", key: "pdf", content: pdf)])
        service.incomplete.insert(pdf.file!.hash)
        let preview = model(service, ready); await preview.openDefault(); XCTAssertNil(preview.download.preview); XCTAssertNotNil(preview.download.error)
        service.incomplete.removeAll(); service.versionsValue = [service.record(ready)]; await preview.refresh()
        XCTAssertEqual(preview.selectedKey, "pdf"); XCTAssertNotNil(preview.download.preview); XCTAssertNil(preview.download.error)
    }
    func testPauseResumesSamePreviewAtCommittedCursorAndRefreshDoesNotSwitchIt() async throws {
        for (role, name) in [("pdf", "preview.pdf"), ("workbook", "workbook.json")] {
            let service = ResultPreviewServiceStub(), original = service.add("report.docx", "original"), file = service.add(name, "preview bytes for resume")
            let ready = service.version(original, variants: [ResultVariant(role: role, key: role, content: file)])
            service.pauseHash = file.file!.hash; service.versionsValue = [service.record(ready)]
            let preview = model(service, ready), task = Task { await preview.openDefault() }
            for _ in 0..<100 where service.requested.count < 2 { try await Task.sleep(for: .milliseconds(10)) }
            XCTAssertEqual(service.requested.count, 2); preview.download.pauseDownload(); await task.value
            XCTAssertNotNil(preview.download.paused); await preview.refresh(); XCTAssertNil(preview.download.preview)
            await preview.download.resumeDownload()
            XCTAssertEqual(preview.selectedKey, role); XCTAssertNil(preview.download.paused); XCTAssertNil(preview.download.error)
            XCTAssertEqual(service.requested.map { $0.1 }.prefix(3), [0, 4, 4])
            XCTAssertEqual(try Data(contentsOf: preview.download.preview!), service.data[file.file!.hash])
            XCTAssertFalse(service.requested.contains { $0.0 == original.file!.hash })
        }
    }
    func testDelayedRefreshCannotRevertASelectedVersion() async throws {
        let service = ResultPreviewServiceStub(), original = service.add("report.png", "original"), first = service.version(original), second = service.version(original, number: 2)
        let preview = model(service, first); await preview.openDefault(); service.delayVersions = true
        let refresh = Task { await preview.refresh() }
        for _ in 0..<100 where service.versionContinuation == nil { try await Task.sleep(for: .milliseconds(10)) }
        await preview.selectVersion(second)
        service.versionContinuation?.resume(returning: [service.record(first)]); await refresh.value
        XCTAssertEqual(preview.result.version, 2)
    }
}
