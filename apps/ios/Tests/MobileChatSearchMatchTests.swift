import XCTest
@testable import FunnyWork

final class MobileChatSearchMatchTests: XCTestCase {
    func testReferencesAreValidatedAndLegacyCacheIsCompatible() throws {
        let match = try MobileChatSearchMatch(["field": "reply", "turnId": "turn-one", "excerpt": "Needle in a reply", "created_at": 1000])
        XCTAssertEqual(match.turnID, "turn-one"); XCTAssertEqual(match.date.timeIntervalSince1970, 1)
        let title = try MobileChatSearchMatch(["field": "title", "excerpt": "Needle title", "created_at": 0])
        XCTAssertNil(title.turnID)
        XCTAssertThrowsError(try MobileChatSearchMatch(["field": "reply", "excerpt": "Needle", "created_at": 1000]))
        XCTAssertThrowsError(try MobileChatSearchMatch(["field": "reply", "turnId": "../wrong", "excerpt": "Needle", "created_at": 1000]))
        XCTAssertThrowsError(try MobileChatSearchMatch(["field": "reply", "turnId": "turn", "excerpt": String(repeating: "x", count: 501), "created_at": 1000]))
        XCTAssertThrowsError(try MobileChatSearchMatch(["field": "reply", "turnId": "turn", "excerpt": "Needle", "created_at": true]))
        let old = try JSONDecoder().decode(MobileChatSummary.self, from: Data("{\"id\":\"old\",\"title\":\"Old\",\"modelID\":\"qwen\",\"revision\":0}".utf8))
        XCTAssertNil(old.searchMatch)
    }
    func testSearchMatchRoundTripsInSummaryCache() throws {
        let summary = try MobileChatSummary(["id": "chat", "title": "Chat", "modelId": "qwen", "revision": 4,
            "search_match": ["field": "user", "turnId": "turn", "excerpt": "My needle", "created_at": 2000]])
        let decoded = try JSONDecoder().decode(MobileChatSummary.self, from: JSONEncoder().encode(summary))
        XCTAssertEqual(decoded.searchMatch, summary.searchMatch)
    }
}
