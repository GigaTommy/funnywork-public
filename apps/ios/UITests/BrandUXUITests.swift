import XCTest

final class BrandUXUITests: XCTestCase {
    func testOfflineStatusHasAccessibleValueAndRecoveryRemainsReachable() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-network", "--ui-test-reset", "-AppleLanguages", "(en)", "-appearance", "dark", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let status = app.buttons["home-connection-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        XCTAssertEqual(status.value as? String, "Temporarily disconnected")
        XCTAssertTrue(status.isHittable); status.tap()
        XCTAssertTrue(app.buttons["Retry"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Retry"].isHittable)
        XCTAssertTrue(app.buttons["Done"].isHittable)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "offline-large-text-dark"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 5))
    }
    func testHomeKeyboardCanBeDismissedWithoutLosingDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(en)"]
        app.launch()
        let field = app.textFields["home-chat-message"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap(); field.typeText("Preserved keyboard draft")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.buttons["Dismiss keyboard"].tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "Preserved keyboard draft")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "home-brand-draft"; shot.lifetime = .keepAlways; add(shot)
    }
    func testHomeScrollDismissesKeyboardAndPreservesDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(en)"]
        app.launch()
        let field = app.textFields["home-chat-message"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap(); field.typeText("Swipe preserved draft")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.scrollViews["home-chat-content"].swipeDown()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "Swipe preserved draft")
    }

}
