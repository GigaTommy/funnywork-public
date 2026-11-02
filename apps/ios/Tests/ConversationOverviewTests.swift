import XCTest
@testable import FunnyWork

@MainActor final class ConversationOverviewTests: XCTestCase {
    private func row(_ state: String = "approval", remaining: Int = 30000) -> Conversation {
        Conversation(["id": "chat", "title": "Chat", "online": true, "preview_text": "Latest visible message", "task_state": state, "task_state_remaining_ms": remaining])!
    }
    func testLiveStatusExpiresAndIsSuppressedWhenOfflineOrInBackground() {
        let value = row()
        XCTAssertEqual(value.status(at: Date(), showingCache: false), String(localized: "需要批准"))
        XCTAssertEqual(value.status(at: Date().addingTimeInterval(31), showingCache: false), String(localized: "在线"))
        XCTAssertEqual(value.status(at: Date(), showingCache: true), String(localized: "未连接"))
        XCTAssertEqual(value.status(at: Date(), showingCache: false, active: false), String(localized: "未连接"))
        XCTAssertEqual(row("idle").status(at: Date(), showingCache: false), String(localized: "在线"))
    }
    func testStatusEmphasisExpiresAndNeverMarksCachedTasksAsLive() {
        let now = Date()
        XCTAssertEqual(row("approval").statusStyle(at: now, showingCache: false), .waiting)
        XCTAssertEqual(row("question").statusStyle(at: now, showingCache: false), .waiting)
        XCTAssertEqual(row("waiting").statusStyle(at: now, showingCache: false), .waiting)
        XCTAssertEqual(row("running").statusStyle(at: now, showingCache: false), .running)
        XCTAssertEqual(row().statusStyle(at: now.addingTimeInterval(31), showingCache: false), .online)
        XCTAssertEqual(row().statusStyle(at: now, showingCache: true), .unlinked)
        XCTAssertEqual(row().statusStyle(at: now, showingCache: false, active: false), .unlinked)
    }
    func testDirectoryCachePreservesExcerptButNeverRestoresLiveTaskState() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "a", root: root)
        try store.saveDirectory([row()])
        let restored = try XCTUnwrap(ConversationStore(key: "a", root: root).loadDirectory()?.rows.first)
        XCTAssertEqual(restored.preview, "Latest visible message")
        XCTAssertNil(restored.taskState); XCTAssertNil(restored.taskStateExpiresAt)
        XCTAssertNil(try ConversationStore(key: "b", root: root).loadDirectory())
    }
    func testLegacyDirectoryAndUnknownStateRemainCompatible() throws {
        let legacy = try JSONDecoder().decode(Conversation.self, from: Data("{\"id\":\"chat\",\"title\":\"Chat\",\"device\":\"Mac\",\"online\":false,\"deviceID\":\"mac\"}".utf8))
        XCTAssertNil(legacy.preview); XCTAssertNil(legacy.taskState)
        XCTAssertNil(row("future-state").taskState)
        XCTAssertNil(row(remaining: 30001).taskState)
        XCTAssertNil(row(remaining: 0).taskState)
        XCTAssertNil(Conversation(["id": "chat", "preview_text": String(repeating: "x", count: 3201)])?.preview)
    }
    func testAttachmentOnlyExcerptDoesNotInventMessageText() {
        let file = Conversation(["id": "chat", "preview_text": "", "preview_attachment": true])!
        XCTAssertEqual(file.preview, String(localized: "附件"))
        XCTAssertNil(Conversation(["id": "chat", "preview_text": "  "])?.preview)
    }
}
