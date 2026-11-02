import XCTest

final class PushNotificationsUITests: XCTestCase {
    private func settings(_ app: XCUIApplication, extra: [String] = []) {
        app.launchArguments = ["--ui-test-directory", "--ui-test-push", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"] + extra
        app.launch(); app.revealConversationMenu(); app.buttons["账号"].tap()
        XCTAssertTrue(app.buttons["notification-settings"].waitForExistence(timeout: 5))
        app.buttons["notification-settings"].tap()
    }
    func testPreferencesAndNotificationOpenInLightAndDark() {
        for appearance in ["light", "dark"] {
            let app = XCUIApplication(); settings(app, extra: ["-appearance", appearance])
            XCTAssertTrue(app.switches["notification-approvals"].waitForExistence(timeout: 5))
            app.switches["notification-approvals"].coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已开启")).firstMatch.waitForExistence(timeout: 5))
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "notifications-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.navigationBars.buttons.element(boundBy: 0).tap()
            app.buttons["完成"].tap()
            app.buttons["push-fixture-open"].tap()
            XCTAssertTrue(app.staticTexts["selected-conversation"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["selected-conversation"].label, "修改登录页")
            app.terminate()
        }
    }
    func testDeniedAndOfflineShowActionableStatus() {
        let app = XCUIApplication(); settings(app, extra: ["--ui-test-push-denied"])
        XCTAssertTrue(app.buttons["打开系统设置"].waitForExistence(timeout: 5))
        app.switches["notification-questions"].coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["系统通知已关闭"].exists)
        app.terminate(); settings(app, extra: ["--ui-test-push-offline"])
        app.switches["notification-approvals"].coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["连接已断开，请检查网络后重试。"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已开启")).firstMatch.exists); XCTAssertTrue(app.buttons["重试"].isEnabled)
    }
}
