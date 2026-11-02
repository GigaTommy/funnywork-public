import XCTest

final class ModelManagementUITests: XCTestCase {
    func testModelAndPresetSelectionInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-model-choices", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.revealConversationMenu(); app.buttons["new-conversation"].tap()
            app.buttons["更多选项"].tap()
            let model = app.buttons["creation-model"]
            XCTAssertTrue(model.waitForExistence(timeout: 10))
            model.tap(); app.buttons["企业千问"].tap()
            app.buttons["creation-preset"].tap(); app.buttons["企业助手"].tap()
            app.buttons["更多选项"].tap()
            let summary = app.staticTexts["creation-options-summary"]
            XCTAssertTrue(summary.waitForExistence(timeout: 5)); XCTAssertTrue(summary.label.contains("企业千问")); XCTAssertTrue(summary.label.contains("企业助手"))
            XCTAssertTrue(app.buttons["create-conversation"].isEnabled)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "model-choices-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["create-conversation"].tap()
            XCTAssertTrue(app.staticTexts["未命名对话"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
    func testCapacityWaitingShowsModelAndCanStop() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-model-waiting", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            app.staticTexts["示例对话"].tap()
            XCTAssertTrue(app.descendants(matching: .any)["execution-status"].firstMatch.waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["正在等待模型容量…"].exists)
            XCTAssertFalse(app.descendants(matching: .any)["execution-model"].firstMatch.exists)
            app.buttons["task-details"].tap()
            XCTAssertTrue(app.descendants(matching: .any)["execution-model"].firstMatch.exists)
            XCTAssertTrue(app.buttons["desktop-task-usage-menu"].exists)
            XCTAssertFalse(app.staticTexts["平均输出 12.5 tok/s"].exists)
            app.buttons["desktop-task-usage-menu"].tap()
            XCTAssertTrue(app.staticTexts["usage-actual-model"].waitForExistence(timeout: 5))
            app.buttons["usage-details-close"].tap()
            app.buttons["task-details-close"].tap()
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "capacity-waiting-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["停止"].tap()
            let stopped = NSPredicate(format: "exists == false")
            expectation(for: stopped, evaluatedWith: app.buttons["stop-generation"])
            waitForExpectations(timeout: 10)
            XCTAssertFalse(app.descendants(matching: .any)["execution-status"].firstMatch.exists)
            app.terminate()
        }
    }
    func testPerformanceRemainsAvailableWithoutTokenProjection() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-model-waiting", "--ui-test-performance-only", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.staticTexts["示例对话"].tap(); app.buttons["task-details"].tap()
        XCTAssertTrue(app.buttons["desktop-task-usage-menu"].waitForExistence(timeout: 10))
        app.buttons["desktop-task-usage-menu"].tap()
        XCTAssertTrue(app.buttons["usage-details-close"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["待更新"].exists)
        for _ in 0..<3 where !app.staticTexts["12.5 tok/s"].exists { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["12.5 tok/s"].exists)
    }

}
