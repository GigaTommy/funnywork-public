import XCTest
@testable import FunnyWork

@MainActor final class ConversationReadTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    private func row(_ sequence: Int) -> Conversation { Conversation(["id": "chat", "next_seq": sequence])! }
    func testReadBoundaryPersistsAndNewContentBecomesUnread() throws {
        let store = ConversationStore(key: "a", root: root)
        XCTAssertFalse(store.hasUnread(row(0))); XCTAssertTrue(store.hasUnread(row(5)))
        try store.markRead(through: 3, for: "chat")
        XCTAssertTrue(store.hasUnread(row(5)))
        try store.markRead(through: 5, for: "chat")
        XCTAssertFalse(store.hasUnread(row(5))); XCTAssertTrue(store.hasUnread(row(6)))
        let restored = ConversationStore(key: "a", root: root)
        XCTAssertFalse(restored.hasUnread(row(5)))
        XCTAssertTrue(ConversationStore(key: "b", root: root).hasUnread(row(5)))
        try store.invalidateAndClear()
        XCTAssertThrowsError(try store.markRead(through: 6, for: "chat"))
        XCTAssertTrue(ConversationStore(key: "a", root: root).hasUnread(row(5)))
    }
    func testOldPageCannotLowerReadBoundary() throws {
        let old = ConversationStore(key: "a", root: root)
        let new = ConversationStore(key: "a", root: root)
        try new.markRead(through: 20, for: "chat")
        try old.markRead(through: 5, for: "chat")
        XCTAssertEqual(old.readMarkers["chat"]?.sequence, 20)
        XCTAssertFalse(ConversationStore(key: "a", root: root).hasUnread(row(20)))
    }
    func testMissingSequenceAndOldCacheDoNotInventUnreadStatus() throws {
        let store = ConversationStore(key: "a", root: root)
        let legacy = Conversation(["id": "chat"])!
        XCTAssertFalse(store.hasUnread(legacy))
        try store.saveDirectory([row(5)])
        XCTAssertEqual(try store.loadDirectory()?.rows.first?.nextSequence, 5)
        XCTAssertNil(try JSONDecoder().decode(Conversation.self, from: Data("{\"id\":\"chat\",\"title\":\"Chat\",\"device\":\"Mac\",\"online\":false,\"deviceID\":\"mac\"}".utf8)).nextSequence)
    }
}
