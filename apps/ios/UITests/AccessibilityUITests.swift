import XCTest

final class AccessibilityUITests: XCTestCase {
    private let largeText = ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL", "-AppleLanguages", "(zh-Hans)"]
    private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
    func testLargestTextLoginAndKeyboardComposer() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-login", "--ui-test-reset", "-appearance", appearance] + largeText
            app.launch()
            XCTAssertTrue(app.staticTexts["login-brand"].waitForExistence(timeout: 5))
            capture(app, "large-text-login-\(appearance)")
            app.terminate()
            app.launchArguments = ["--ui-test-conversation", "--ui-test-reset", "-appearance", appearance] + largeText
            app.launch(); app.buttons["示例对话"].tap()
            let composer = app.textFields["conversation-composer"]
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            composer.tap(); composer.typeText("First line\nSecond line\nThird line\nFourth line\nFifth line")
            capture(app, "large-text-composer-keyboard-\(appearance)")
            XCTAssertLessThan(composer.frame.height, 160, "Large text input must leave room for the conversation")
            XCTAssertEqual(composer.value as? String, "First line\nSecond line\nThird line\nFourth line\nFifth line")
            XCTAssertTrue(app.buttons["收起键盘"].isHittable)
            XCTAssertTrue(app.buttons["send-message"].isHittable)
            app.buttons["收起键盘"].tap()
            capture(app, "large-text-conversation-\(appearance)")
            XCTAssertTrue(app.buttons["send-message"].isEnabled)
            app.buttons["send-message"].tap()
            XCTAssertEqual(composer.value as? String, "说点什么…")
            app.terminate()
        }
    }
}
