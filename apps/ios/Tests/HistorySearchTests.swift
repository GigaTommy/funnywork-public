import XCTest
@testable import FunnyWork

@MainActor final class HistorySearchStub: HistorySearchService {
    var requests: [(String, HistorySearchCursor, Bool, String?)] = []
    var pages: [HistorySearchPage] = []
    var failure = false
    var delayed: CheckedContinuation<HistorySearchPage, Error>?
    func page(query: String, cursor: HistorySearchCursor, archived: Bool, deviceID: String?) async throws -> HistorySearchPage {
        requests.append((query, cursor, archived, deviceID))
        if query == "old" { return try await withCheckedThrowingContinuation { delayed = $0 } }
        if failure { throw URLError(.notConnectedToInternet) }
        return pages.removeFirst()
    }
    func message(_ result: HistorySearchResult) async throws -> HistoryItem { throw ClientError.invalidResponse }
}
@MainActor final class HistorySearchTests: XCTestCase {
    func result(_ id: String, _ sequence: Int) -> HistorySearchResult {
        .init(conversation: Conversation(["id": id, "title": id])!, sequence: sequence, excerpt: "match")
    }
    func testSparsePagesContinueAcrossSessionsAndForwardFilters() async {
        let service = HistorySearchStub()
        service.pages = [.init(items: [], cursor: .init(session: "a", sequence: 63), hasMore: true),
                         .init(items: [result("a", 69), result("b", 0)], cursor: .init(session: "b", sequence: 5), hasMore: false)]
        let model = HistorySearchModel(service: service, archived: true, deviceID: "mac")
        await model.search(" match ", debounce: false)
        XCTAssertEqual(model.results.map(\.id), ["a:69", "b:0"])
        XCTAssertEqual(service.requests[1].1, .init(session: "a", sequence: 63))
        XCTAssertTrue(service.requests.allSatisfy { $0.0 == "match" && $0.2 && $0.3 == "mac" })
        XCTAssertFalse(model.hasMore); XCTAssertNil(model.error)
    }
    func testSparseBatchOffersContinuationAndFailureRetriesSamePosition() async {
        let service = HistorySearchStub()
        service.pages = (0..<20).map { .init(items: [], cursor: .init(session: "a", sequence: $0), hasMore: true) }
        let model = HistorySearchModel(service: service)
        await model.search("match", debounce: false)
        XCTAssertTrue(model.hasMore); XCTAssertEqual(service.requests.count, 20)
        service.failure = true; await model.more()
        XCTAssertNotNil(model.error); XCTAssertTrue(model.hasMore)
        service.failure = false
        service.pages = [.init(items: [result("a", 22)], cursor: .init(session: "a", sequence: 22), hasMore: false)]
        await model.more()
        XCTAssertEqual(service.requests.suffix(2).map { $0.1 }, [.init(session: "a", sequence: 19), .init(session: "a", sequence: 19)])
        XCTAssertEqual(model.results.map(\.id), ["a:22"]); XCTAssertNil(model.error)
    }
    func testOldQueryCannotReplaceNewResults() async {
        let service = HistorySearchStub(); let model = HistorySearchModel(service: service)
        let old = Task { await model.search("old", debounce: false) }
        while service.delayed == nil { await Task.yield() }
        service.pages = [.init(items: [result("b", 1)], cursor: .init(session: "b", sequence: 1), hasMore: false)]
        await model.search("new", debounce: false)
        service.delayed?.resume(returning: .init(items: [result("a", 0)], cursor: .init(session: "a", sequence: 0), hasMore: false))
        await old.value
        XCTAssertEqual(model.results.map(\.id), ["b:1"]); XCTAssertFalse(model.loading)
    }
    func testInvalidCursorAndDuplicateRowsNeverGetAppended() async {
        for page in [HistorySearchPage(items: [], cursor: .init(), hasMore: true),
                     .init(items: [result("a", 0), result("a", 0)], cursor: .init(session: "a", sequence: 0), hasMore: false)] {
            let service = HistorySearchStub(); service.pages = [page]
            let model = HistorySearchModel(service: service)
            await model.search("match", debounce: false)
            XCTAssertNotNil(model.error); XCTAssertTrue(model.results.isEmpty)
        }
    }
    func testEmptyAndOversizedQueriesDoNotSendAndURLKeepsLiteralCharacters() async throws {
        let service = HistorySearchStub(); let model = HistorySearchModel(service: service)
        await model.search("  ", debounce: false); await model.search(String(repeating: "a", count: 161), debounce: false)
        XCTAssertTrue(service.requests.isEmpty); XCTAssertNotNil(model.error)
        let query = "中文 + 100% & # ? /"
        let path = HTTPHistorySearchService.path(query: query, cursor: .init(), archived: false, deviceID: nil)
        let components = try XCTUnwrap(URLComponents(string: "https://example.com/" + path))
        XCTAssertEqual(components.queryItems?.first?.value, query)
        XCTAssertTrue(path.contains("%2B")); XCTAssertNil(components.fragment)
    }
}
