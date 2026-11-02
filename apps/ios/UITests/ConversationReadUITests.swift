import XCTest

final class ConversationReadUITests: XCTestCase {
    func testRestoringOldPositionDoesNotMarkNewMessagesRead() {
        let app = XCUIApplication()
        let arguments = ["--ui-test-directory", "--ui-test-unread", "--ui-test-long-conversation", "-AppleLanguages", "(zh-Hans)"]
        app.launchArguments = arguments + ["--ui-test-reset"]
        app.launch(); app.staticTexts["修改登录页"].tap()
        XCTAssertTrue(app.staticTexts["消息 74：这是一段用于检查长对话阅读位置的内容。"].waitForExistence(timeout: 5))
        app.swipeDown(); app.swipeDown()
        XCTAssertTrue(app.buttons["scroll-to-latest"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.terminate(); app.launchArguments = arguments + ["--ui-test-unread-update"]
        app.launch(); XCTAssertTrue(app.images["unread-a"].waitForExistence(timeout: 5))
        app.staticTexts["修改登录页"].tap()
        XCTAssertTrue(app.buttons["scroll-to-latest"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.images["unread-a"].waitForExistence(timeout: 5))
        app.staticTexts["修改登录页"].tap()
        app.buttons["scroll-to-latest"].tap()
        XCTAssertTrue(app.staticTexts["消息 75：这是一段用于检查长对话阅读位置的内容。"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.images["unread-a"].waitForNonExistence(timeout: 5))
    }
    func testReadingClearsOnlyThisConversationAndSurvivesRelaunch() {
        let app = XCUIApplication()
        let arguments = ["--ui-test-directory", "--ui-test-unread", "-AppleLanguages", "(zh-Hans)"]
        app.launchArguments = arguments + ["--ui-test-reset", "-appearance", "light"]
        app.launch()
        XCTAssertTrue(app.images["unread-a"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.images["unread-b"].exists)
        let before = XCTAttachment(screenshot: app.screenshot()); before.name = "unread-list-light"; before.lifetime = .keepAlways; add(before)
        app.staticTexts["修改登录页"].tap()
        XCTAssertTrue(app.buttons["pending-shortcut"].waitForExistence(timeout: 5))
        if app.buttons["scroll-to-latest"].exists { app.buttons["scroll-to-latest"].tap() }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.images["unread-a"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.images["unread-b"].exists)
        app.terminate(); app.launchArguments = arguments + ["-appearance", "dark"]
        app.launch(); XCTAssertTrue(app.images["unread-b"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.images["unread-a"].exists)
        let after = XCTAttachment(screenshot: app.screenshot()); after.name = "read-list-dark"; after.lifetime = .keepAlways; add(after)
        app.terminate(); app.launchArguments = arguments + ["--ui-test-unread-update"]
        app.launch(); XCTAssertTrue(app.images["unread-a"].waitForExistence(timeout: 5))
    }
}
