import XCTest

final class ConversationOverviewUITests: XCTestCase {
    func testPreviewAndTaskStatusInLightAndDark() {
        for appearance in ["light", "dark"] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-directory", "--ui-test-overview", "--ui-test-reset", "-appearance", appearance, "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
            app.launch()
            XCTAssertTrue(app.staticTexts["conversation-preview-a"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["conversation-status-a"].label, "需要批准")
            XCTAssertEqual(app.staticTexts["conversation-status-c"].label, "进行中")
            if !app.staticTexts["conversation-status-d"].isHittable { app.swipeUp() }
            XCTAssertEqual(app.staticTexts["conversation-status-d"].label, "需要回答")
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "conversation-overview-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate()
        }
    }
    func testOfflineAndExpiredStateKeepPreviewWithoutLiveTaskLabels() {
        let app = XCUIApplication()
        let arguments = ["--ui-test-directory", "--ui-test-overview", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchArguments = arguments + ["--ui-test-reset"]; app.launch()
        XCTAssertTrue(app.staticTexts["conversation-preview-a"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["conversation-status-a"].label, "需要批准")
        app.terminate(); app.launchArguments = arguments + ["--ui-test-overview-offline"]; app.launch()
        XCTAssertTrue(app.staticTexts["conversation-preview-a"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["conversation-status-a"].label, "未连接")
        XCTAssertEqual(app.staticTexts["conversation-preview-a"].label, "登录表单已调整，等待批准运行检查。")
        app.terminate(); app.launchArguments = arguments + ["--ui-test-overview-stale"]; app.launch()
        XCTAssertTrue(app.staticTexts["conversation-preview-a"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["conversation-status-a"].label, "在线")
        XCTAssertEqual(app.staticTexts["conversation-status-c"].label, "在线")
    }
}
