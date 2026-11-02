import XCTest
@testable import FunnyWork

@MainActor private final class DelayedSearchService: ConversationService {
    let base = StubConversationService()
    var waiting: CheckedContinuation<EventPage, Error>?
    var delay = true
    func snapshot() async throws -> RemoteSnapshot { try await base.snapshot() }
    func receipts() async throws -> [CommandReceipt] { [] }
    func send(_ command: RemoteCommand) async throws -> CommandReceipt { try await base.send(command) }
    func events(offset: Int, limit: Int) async throws -> EventPage {
        if delay { delay = false; return try await withCheckedThrowingContinuation { waiting = $0 } }
        return try await base.events(offset: offset, limit: limit)
    }
}
@MainActor final class ConversationSearchTests: XCTestCase {
    func testSearchFindsAttachmentOnlyMessagesAndDoesNotDuplicateTextMatches() async throws {
        let service = AttachmentSearchService()
        let model = ConversationSearch(service: service)
        await model.search("report", debounce: false)
        XCTAssertEqual(model.results.map(\.id), [0, 1])
        XCTAssertTrue(model.results[0].text.isEmpty)
        await model.search(".PNG", debounce: false)
        XCTAssertEqual(model.results.map(\.id), [1])
        await model.search("missing", debounce: false)
        XCTAssertTrue(model.results.isEmpty); XCTAssertFalse(model.hasMore)
    }
    func testSearchScansOlderMessagesAndOffersRemainingResults() async {
        let service = StubConversationService(); service.eventCount = 230
        let model = ConversationSearch(service: service)
        await model.search("Message 229", debounce: false)
        XCTAssertEqual(model.results.map(\.id), [229]); XCTAssertFalse(model.hasMore)
        XCTAssertEqual(service.offsets, [0, 100, 200])
        await model.search("Message", debounce: false)
        XCTAssertEqual(model.results.count, 100); XCTAssertTrue(model.hasMore)
        await model.more(); await model.more()
        XCTAssertEqual(model.results.count, 230); XCTAssertFalse(model.hasMore)
        await model.search("   ", debounce: false)
        XCTAssertTrue(model.results.isEmpty); XCTAssertFalse(model.hasMore)
    }
    func testFailedSearchCanResumeWithoutDuplicates() async {
        let service = StubConversationService(); let model = ConversationSearch(service: service)
        await model.search("Message", debounce: false)
        service.failEvents = true; await model.more()
        XCTAssertEqual(model.results.count, 100); XCTAssertNotNil(model.error)
        service.failEvents = false; await model.more()
        XCTAssertEqual(model.results.count, 130); XCTAssertNil(model.error)
    }
    func testLateResultCannotReplaceNewQueryOrClosedSearch() async throws {
        let service = DelayedSearchService(); let model = ConversationSearch(service: service)
        let old = Task { await model.search("Message 1", debounce: false) }
        while service.waiting == nil { await Task.yield() }
        await model.search("Message 129", debounce: false)
        service.waiting?.resume(returning: try await service.base.events(offset: 0, limit: 100))
        await old.value
        XCTAssertEqual(model.results.map(\.id), [129])
        service.waiting = nil; service.delay = true
        let closed = Task { await model.search("Message", debounce: false) }
        while service.waiting == nil { await Task.yield() }
        model.cancel()
        service.waiting?.resume(returning: try await service.base.events(offset: 0, limit: 100))
        await closed.value
        XCTAssertTrue(model.results.isEmpty); XCTAssertFalse(model.loading)
    }
}

@MainActor private struct AttachmentSearchService: ConversationService {
    func snapshot() async throws -> RemoteSnapshot { RemoteSnapshot() }
    func receipts() async throws -> [CommandReceipt] { [] }
    func send(_ command: RemoteCommand) async throws -> CommandReceipt { throw ClientError.invalidResponse }
    func events(offset: Int, limit: Int) async throws -> EventPage {
        let all: [[String: Any]] = (0..<2).map { index in
            ["seq": index, "type": "user/message", "data": ["content": [
                ["text": index == 0 ? "" : "Report with image"],
                ["attachment": ["attachmentId": "sha256:" + String(repeating: "a", count: 64),
                                "bytes": 12, "name": index == 0 ? "report.pdf" : "report.png"]]
            ]]]
        }
        let rows = Array(all.dropFirst(offset).prefix(limit))
        return try EventPage(["events": rows, "nextSeq": offset + rows.count, "durableNextSeq": all.count], offset: offset)
    }
}
