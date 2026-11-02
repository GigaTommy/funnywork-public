import XCTest
@testable import FunnyWork

@MainActor private final class ReadingStub: ReadingDriver {
    var finished: ((UUID) -> Void)?
    var interrupted: (() -> Void)?
    var tokens: [UUID] = []
    var stops = 0
    var acceptsPause = true
    var fails = false
    func start(_ text: String, token: UUID) throws {
        if fails { throw URLError(.unknown) }
        tokens.append(token)
    }
    func pause() -> Bool { acceptsPause }
    func resume() -> Bool { true }
    func stop() { stops += 1 }
}
@MainActor final class MessageReadingTests: XCTestCase {
    func testNewMessageIgnoresOldCompletionAndSupportsPause() {
        let driver = ReadingStub(); let model = MessageReading(driver: driver)
        model.read("One", messageID: 1); let first = driver.tokens[0]
        model.read("Two", messageID: 2)
        driver.finished?(first)
        XCTAssertEqual(model.messageID, 2)
        model.togglePause(); XCTAssertTrue(model.paused)
        model.togglePause(); XCTAssertFalse(model.paused)
        driver.finished?(driver.tokens[1]); XCTAssertNil(model.messageID)
        XCTAssertGreaterThanOrEqual(driver.stops, 3)
    }
    func testInterruptionStopAndFailedStartReleaseState() {
        let driver = ReadingStub(); let model = MessageReading(driver: driver)
        model.read("One", messageID: 1)
        driver.acceptsPause = false; model.togglePause(); XCTAssertFalse(model.paused)
        driver.interrupted?(); XCTAssertNil(model.messageID)
        driver.fails = true; model.read("Two", messageID: 2)
        XCTAssertNil(model.messageID); XCTAssertNotNil(model.error)
        model.read("  ", messageID: 3); XCTAssertNil(model.messageID)
    }
}
