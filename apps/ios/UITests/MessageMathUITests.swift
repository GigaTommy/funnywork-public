import XCTest

final class MessageMathUITests: XCTestCase {
    func testFormulasRenderAndInvalidInputRemainsReadableInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-math", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            XCTAssertTrue(app.images.matching(identifier: "rendered-formula").firstMatch.waitForExistence(timeout: 5))
            XCTAssertEqual(app.images.matching(identifier: "rendered-formula").count, 2)
            XCTAssertTrue(app.staticTexts["formula-fallback"].exists)
            XCTAssertTrue(app.staticTexts["formula-fallback"].label.contains("unsupported"))
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "金额 $5 和 $10")).firstMatch.exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "message-math-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["select-message-text"].tap()
            let selection = app.textViews["selectable-message-text"]
            XCTAssertTrue(selection.waitForExistence(timeout: 5))
            XCTAssertTrue((selection.value as? String)?.contains(#"\frac{a}{b}"#) == true)
            app.buttons["完成"].tap()
            app.terminate()
        }
    }
}
