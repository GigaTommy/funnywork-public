import XCTest
@testable import FunnyWork

@MainActor private final class VoiceStub: VoiceInputDriver {
    var result: ((UUID, String?, Bool, Error?) -> Void)?
    var tokens: [UUID] = []
    var denied = false
    var stopped = 0
    func start(token: UUID) async throws {
        if denied { throw ClientError(message: "Denied") }
        tokens.append(token)
    }
    func finish() { }
    func stop() { stopped += 1 }
}
@MainActor final class VoiceInputTests: XCTestCase {
    func testFinishKeepsFinalTextAndLateResultsCannotReplaceEditedText() async {
        let driver = VoiceStub(); let model = VoiceInput(driver: driver)
        await model.start(); let token = driver.tokens[0]
        XCTAssertTrue(model.recording)
        driver.result?(token, "partial", false, nil)
        model.finish(); XCTAssertTrue(model.busy); XCTAssertFalse(model.recording)
        driver.result?(token, "final", true, nil)
        XCTAssertFalse(model.busy); XCTAssertEqual(model.text, "final")
        model.text = "edited"
        driver.result?(token, "late", false, nil)
        XCTAssertEqual(model.text, "edited")
    }
    func testPermissionFailureAndCancellationReleaseRecordingState() async {
        let driver = VoiceStub(); let model = VoiceInput(driver: driver)
        driver.denied = true; await model.start()
        XCTAssertFalse(model.recording); XCTAssertFalse(model.busy); XCTAssertNotNil(model.error)
        driver.denied = false; await model.start(); let old = driver.tokens[0]
        model.stop(); await model.start()
        driver.result?(old, "old", true, nil)
        XCTAssertTrue(model.recording); XCTAssertEqual(model.text, "")
        model.stop(); XCTAssertFalse(model.recording)
    }
}
