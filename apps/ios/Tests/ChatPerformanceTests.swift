import XCTest
@testable import FunnyWork
final class ChatPerformanceTests: XCTestCase {
    func testWeightedSpeedUsesOnlyPairedTokensAndDurations() throws {
        let row: [String: Any] = ["queue_ms": 100, "dispatch_ms": 50, "request_ms": 500, "total_ms": 650, "first_output_ms": 100, "decode_ms": 400, "decode_tokens": 5]
        let reading = try XCTUnwrap(ChatPerformanceReading(row)); var summary = ChatPerformanceSummary(reading)
        summary.add(try XCTUnwrap(ChatPerformanceReading(row.merging(["request_ms": 1700, "total_ms": 1850, "decode_ms": 1600, "decode_tokens": 35]) { _, b in b })))
        XCTAssertEqual(summary.speed, 20); XCTAssertEqual(summary.firstOutput, 100); XCTAssertEqual(summary.decodeCalls, 2)
        XCTAssertEqual(summary.queue, 200); XCTAssertEqual(summary.dispatch, 100); XCTAssertEqual(summary.request, 2200)
        let noOutput: [String: Any] = ["queue_ms": 0, "dispatch_ms": 0, "request_ms": 1000, "total_ms": 1000]
        summary.add(try XCTUnwrap(ChatPerformanceReading(noOutput))); XCTAssertEqual(summary.speed, 20)
        XCTAssertNil(ChatPerformanceSummary(try XCTUnwrap(ChatPerformanceReading(noOutput))).speed)
        XCTAssertNil(ChatPerformanceReading(row.merging(["decode_ms": 0]) { _, b in b }))
        XCTAssertNil(ChatPerformanceReading(row.merging(["request_ms": true]) { _, b in b }))
        XCTAssertNil(ChatPerformanceReading(row.merging(["total_ms": -1]) { _, b in b }))
    }
    func testHarnessStatsAreReadDirectlyAndMissingMetricsAreNotZero() throws {
        let row: [String: Any] = ["steps": 2, "llmMs": 2000, "toolMs": 300, "ttftMs": 400, "ttftSteps": 2, "decodeMs": 1600, "decodeTokens": 32]
        let summary = try XCTUnwrap(ChatPerformanceSummary(remote: row))
        XCTAssertEqual(summary.speed, 20); XCTAssertEqual(summary.firstOutput, 200); XCTAssertEqual(summary.tool, 300)
        XCTAssertNil(summary.decodeCalls); XCTAssertNil(summary.queue); XCTAssertNil(summary.total); XCTAssertTrue(summary.desktop)
        XCTAssertNil(ChatPerformanceSummary(remote: [:]))
        XCTAssertNil(ChatPerformanceSummary().speed); XCTAssertNil(ChatPerformanceSummary().firstOutput)
        XCTAssertNil(ChatPerformanceSummary(remote: row.merging(["ttftSteps": 3]) { _, b in b }))
    }
}
