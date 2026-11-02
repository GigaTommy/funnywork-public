import XCTest
@testable import FunnyWork

final class HistoryActivityTests: XCTestCase {
    func testTimelineCollapsesConsecutiveActivityWithoutCrossingMessagesOrGaps() throws {
        func item(_ sequence: Int, _ type: String, text: String = "") -> HistoryItem {
            HistoryItem(["seq": sequence, "type": type, "data": ["content": [["text": text]]]])!
        }
        let items = [item(0, "user/message", text: "hello"), item(1, "turn/start"),
            item(2, "system/message", text: "context"), item(3, "tool/call"),
            item(5, "tool/result"), item(6, "assistant/message", text: "reply"),
            item(7, "turn/end")]
        let timeline = ConversationTimeline(items: items, gaps: [5])
        XCTAssertEqual(timeline.rows.map(\.id), [0, 1, 5, 6, 7])
        XCTAssertEqual(timeline.rows[1].items.map(\.id), [1, 2, 3])
        XCTAssertEqual(timeline.rows.map(\.isActivity), [false, true, true, false, true])
        XCTAssertEqual(timeline.anchor(for: 3), 1)
        XCTAssertEqual(timeline.anchor(for: 5), 5)
        XCTAssertEqual(timeline.anchor(for: 6), 6)
        XCTAssertEqual(timeline.anchor(for: 100), 100)
        let merged = ConversationTimeline(items: items, gaps: [])
        XCTAssertEqual(merged.anchor(for: 5), 1)
        XCTAssertEqual(merged.rows.flatMap(\.items), items)
    }
    func testEmptyInterruptedReplyRemainsVisible() {
        let interrupted = HistoryItem(["seq": 0, "type": "assistant/message", "data": ["interrupted": true]])!
        let timeline = ConversationTimeline(items: [interrupted], gaps: [])
        XCTAssertFalse(timeline.rows[0].isActivity)
    }
    func testInterruptedReplyKeepsNativeTextAndSurvivesCache() throws {
        let item = try XCTUnwrap(HistoryItem(["seq": 1, "type": "assistant/message", "data": [
            "interrupted": true, "message": ["content": [["type": "text", "text": "Partial reply"]]]]]))
        XCTAssertTrue(item.interrupted)
        XCTAssertEqual(item.text, "Partial reply")
        XCTAssertNil(item.activity)
        let restored = try JSONDecoder().decode(HistoryItem.self, from: JSONEncoder().encode(item))
        XCTAssertTrue(restored.interrupted)
        XCTAssertEqual(restored.searchableText, "Partial reply")
        let user = try XCTUnwrap(HistoryItem(["seq": 2, "type": "user/message", "data": ["interrupted": true]]))
        XCTAssertFalse(user.interrupted)
        let old = Data("{\"id\":0,\"label\":\"funnywork\",\"text\":\"Old reply\",\"eventType\":\"assistant/message\"}".utf8)
        XCTAssertFalse(try JSONDecoder().decode(HistoryItem.self, from: old).interrupted)
    }
    func testToolParametersAndFailureUseNativeEnvelopeWithoutPrivateMetadata() throws {
        let call = try XCTUnwrap(HistoryItem(["seq": 1, "type": "tool/call", "data": ["name": "shell", "arguments": "{\"command\":\"pwd\"}"]]))
        XCTAssertEqual(call.activity?.kind, .toolCall)
        XCTAssertEqual(call.activity?.name, "shell")
        XCTAssertEqual(call.activityText, "{\"command\":\"pwd\"}")
        XCTAssertTrue(call.searchableText.contains("shell")); XCTAssertFalse(call.isUser)
        let result = try XCTUnwrap(HistoryItem(["seq": 2, "type": "tool/result", "data": [
            "message": ["isError": true, "content": [["type": "text", "text": "Missing file"]]],
            "error": ["reason": "Check the path"], "meta": ["private": "Do not render"]]]))
        XCTAssertEqual(result.activity?.kind, .toolError)
        XCTAssertEqual(result.activityText, "Check the path\n\nMissing file")
        XCTAssertFalse(result.searchableText.contains("Do not render"))
        XCTAssertEqual(try JSONDecoder().decode(HistoryItem.self, from: JSONEncoder().encode(result)), result)
    }
    func testTurnReasonsDoNotInventCompletion() {
        let reasons: [(String, HistoryActivity.Kind)] = [("completed", .completed), ("aborted", .stopped),
            ("interrupted", .interrupted), ("forked", .interrupted), ("blocked", .blocked), ("error", .failed),
            ("max-tokens", .limit), ("future-reason", .other)]
        for (reason, expected) in reasons {
            let item = HistoryItem(["seq": 3, "type": "turn/end", "data": ["reason": ["kind": reason, "error": ["message": "Failure details"]]]])!
            XCTAssertEqual(item.activity?.kind, expected)
            XCTAssertEqual(item.activity?.detail, "Failure details")
        }
    }
    func testInjectedContextIsNotAttributedToUserAndLegacyCacheStillLoads() throws {
        let user = HistoryItem(["seq": 0, "type": "user/message", "data": ["source": ["kind": "user"], "content": [["text": "hello"]]]])!
        XCTAssertTrue(user.isUser); XCTAssertNil(user.activity)
        let injected = HistoryItem(["seq": 1, "type": "user/message", "data": ["source": ["kind": "runtime-context"], "content": [["text": "Context"]]]])!
        XCTAssertFalse(injected.isUser); XCTAssertEqual(injected.activity?.kind, .context)
        for label in ["你", "You"] {
            let old = try JSONSerialization.data(withJSONObject: ["id": 0, "label": label, "text": "hello"])
            let cached = try JSONDecoder().decode(HistoryItem.self, from: old)
            XCTAssertTrue(cached.isUser); XCTAssertNil(cached.activity)
        }
    }
}
