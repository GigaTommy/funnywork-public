import XCTest
@testable import FunnyWork

final class RemoteResultReviewTests: XCTestCase {
    func items(_ rows: [[String: Any]]) throws -> [HistoryItem] {
        try EventPage(["events": rows, "nextSeq": rows.count, "durableNextSeq": rows.count], offset: 0).events
    }
    func testReviewUsesCompletedTurnWhileNextTurnRunsAndReferencesExactDiffLines() throws {
        let patch = "```diff\ndiff --git a/A.swift b/A.swift\n--- a/A.swift\n+++ b/A.swift\n@@ -2 +2 @@\n-old\n+new\ndiff --git a/B.swift b/B.swift\n--- a/B.swift\n+++ b/B.swift\n@@ -5 +5 @@\n-before\n+after\n```"
        let rows = try items([
            ["seq": 0, "type": "turn/start", "data": [:]],
            ["seq": 1, "type": "assistant/message", "data": ["content": [["type": "text", "text": patch]]]],
            ["seq": 2, "type": "turn/end", "data": ["reason": ["kind": "completed"]]],
            ["seq": 3, "type": "turn/start", "data": [:]],
            ["seq": 4, "type": "assistant/message", "data": ["content": [["type": "text", "text": "Next turn"]]]]
        ])
        let review = try XCTUnwrap(RemoteResultReview(items: rows))
        XCTAssertEqual(review.id, 2); XCTAssertEqual(review.status?.kind, .completed)
        XCTAssertEqual(review.messages.map(\.id), [1]); XCTAssertEqual(review.changes.map(\.path), ["A.swift", "B.swift"])
        let change = review.changes[1]
        XCTAssertEqual(change.added, 1); XCTAssertEqual(change.removed, 1)
        let line = try XCTUnwrap(MessageDiffLine.parse(change.text).first { $0.newLine == 5 })
        XCTAssertTrue(change.reference(line).contains("#1 · B.swift")); XCTAssertTrue(change.reference(line).contains("5"))
        XCTAssertTrue(review.feedback("Please fix").contains("#1"))
    }
    func testReviewDoesNotAttachOldRepliesAcrossAnUnloadedGap() throws {
        let old = try XCTUnwrap(HistoryItem(["seq": 1, "type": "assistant/message", "data": ["content": [["text": "Old reply"]]]]))
        let new = try XCTUnwrap(HistoryItem(["seq": 100, "type": "assistant/message", "data": ["content": [["text": "New reply"]]]]))
        let end = try XCTUnwrap(HistoryItem(["seq": 101, "type": "turn/end", "data": ["reason": ["kind": "completed"]]]))
        let result = try XCTUnwrap(RemoteResultReview(items: [old, new, end]))
        XCTAssertEqual(result.messages.map(\.id), [100]); XCTAssertEqual(result.id, 101)
    }
    func testFailedTurnWithoutReplyStillHasReviewableOutcome() throws {
        let rows = try items([
            ["seq": 0, "type": "turn/start", "data": [:]],
            ["seq": 1, "type": "turn/end", "data": ["reason": ["kind": "error", "error": ["message": "File unavailable"]]]]
        ])
        let result = try XCTUnwrap(RemoteResultReview(items: rows))
        XCTAssertEqual(result.status?.kind, .failed); XCTAssertEqual(result.status?.detail, "File unavailable")
        XCTAssertTrue(result.messages.isEmpty); XCTAssertTrue(result.feedback("Try again").contains("#1"))
    }
    func testPlainUnifiedMultiFilePatchKeepsFileReferences() throws {
        let rows = try items([["seq": 0, "type": "assistant/message", "data": ["content": [["text": "```diff\n--- a/One\n+++ b/One\n@@ -1 +1 @@\n-old\n+new\n--- a/Two\n+++ b/Two\n@@ -3 +3 @@\n-before\n+after\n```"]]]]])
        let result = try XCTUnwrap(RemoteResultReview(items: rows))
        XCTAssertEqual(result.changes.map(\.path), ["One", "Two"])
        XCTAssertEqual(result.changes.map(\.added), [1, 1])
    }
    func testReviewDoesNotInventCompletionForLegacyRepliesOrIncludeToolOutput() throws {
        let rows = try items([
            ["seq": 0, "type": "tool/result", "data": ["content": [["type": "text", "text": "Secret diagnostic"]]]],
            ["seq": 1, "type": "assistant/message", "data": ["content": [["type": "text", "text": "Reply"]]]]
        ])
        let review = try XCTUnwrap(RemoteResultReview(items: rows))
        XCTAssertNil(review.status); XCTAssertEqual(review.messages.map(\.id), [1]); XCTAssertTrue(review.changes.isEmpty)
    }
    func testExcerptUsesOnlyLatestReplyProseAndBoundsLongText() throws {
        let text = String(repeating: "说明", count: 500)
        let rows = try items([
            ["seq": 0, "type": "assistant/message", "data": ["content": [["text": "Earlier outcome"]]]],
            ["seq": 1, "type": "assistant/message", "data": ["content": [["text": "# Heading\n\n```swift\nlet x = 1\n```\n\n" + text]]]]
        ])
        let result = try XCTUnwrap(RemoteResultReview(items: rows))
        XCTAssertEqual(result.replyExcerpt, String(text.prefix(600)))
        XCTAssertEqual(result.messages.last?.text, "# Heading\n\n```swift\nlet x = 1\n```\n\n" + text)
    }
    func testCodeOnlyLatestReplyDoesNotReuseEarlierOutcomeAsExcerpt() throws {
        let rows = try items([
            ["seq": 0, "type": "assistant/message", "data": ["content": [["text": "Earlier outcome"]]]],
            ["seq": 1, "type": "assistant/message", "data": ["content": [["text": "```diff\n--- a/A\n+++ b/A\n@@ -1 +1 @@\n-old\n+new\n```"]]]]
        ])
        let result = try XCTUnwrap(RemoteResultReview(items: rows))
        XCTAssertNil(result.replyExcerpt); XCTAssertEqual(result.changes.count, 1)
    }

    func testReviewSourceDoesNotLabelTerminalEventAsReply() throws {
        let empty = try XCTUnwrap(RemoteResultReview(items: items([
            ["seq": 0, "type": "turn/start", "data": [:]],
            ["seq": 1, "type": "turn/end", "data": ["reason": ["kind": "error"]]]
        ])))
        XCTAssertEqual(empty.sourceDescription, String(localized: "本轮没有已同步回复"))
        let reply = try XCTUnwrap(RemoteResultReview(items: items([
            ["seq": 0, "type": "assistant/message", "data": ["content": [["text": "Reply"]]]],
            ["seq": 1, "type": "turn/end", "data": ["reason": ["kind": "completed"]]]
        ])))
        XCTAssertTrue(reply.sourceDescription.contains("#0")); XCTAssertFalse(reply.sourceDescription.contains("#1"))
    }

}
