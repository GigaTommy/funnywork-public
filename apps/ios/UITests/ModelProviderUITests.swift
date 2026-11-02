import XCTest

final class ModelProviderUITests: XCTestCase {
    func testCompactModelButtonOpensScrollableCurrentModelAndThinkingChoices() {
        for appearance in ["light", "dark"] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-thinking", "--ui-test-reset", "-appearance", appearance,
                                   "-AppleLanguages", "(zh-Hans)", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityM"]
            app.launch()
            let button = app.buttons["home-chat-model"]
            XCTAssertTrue(button.waitForExistence(timeout: 10)); XCTAssertLessThanOrEqual(button.frame.width, 48)
            XCTAssertFalse(app.staticTexts["DeepSeek Flash 正式环境长上下文工具调用与思考模型"].exists)
            button.tap()
            XCTAssertTrue(app.staticTexts["home-chat-model-current"].waitForExistence(timeout: 5))
            let max = app.buttons["home-chat-model-effort-max"]
            for _ in 0..<4 where !max.isHittable { app.swipeUp() }
            XCTAssertTrue(max.isHittable); max.tap(); XCTAssertTrue(max.isSelected)
            let proof = XCTAttachment(screenshot: app.screenshot()); proof.name = "compact-model-thinking-" + appearance; proof.lifetime = .keepAlways; add(proof)
            app.buttons["完成"].tap(); button.tap()
            for _ in 0..<4 where !app.buttons["home-chat-model-effort-max"].isHittable { app.swipeUp() }
            XCTAssertTrue(app.buttons["home-chat-model-effort-max"].isSelected)
            app.terminate()
        }
    }
    func testModelMenuGroupsManufacturersWithoutRepeatingNames() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "--ui-test-provider-groups", "-AppleLanguages", "(en)"]
        app.launch()
        let menu = app.buttons["home-chat-model"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
        XCTAssertTrue(app.staticTexts["OpenAI"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts.matching(identifier: "OpenAI").count, 1)
        XCTAssertTrue(app.staticTexts["DeepSeek"].exists)
        let model = app.buttons["GPT-6 Luna"]
        XCTAssertTrue(model.waitForExistence(timeout: 5)); model.tap()
        XCTAssertEqual(menu.value as? String, "GPT-6 Luna")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "manufacturer-model-selection"; shot.lifetime = .keepAlways; add(shot)
    }
}
