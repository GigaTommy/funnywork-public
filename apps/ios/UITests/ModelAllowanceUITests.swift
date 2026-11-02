import XCTest
final class ModelAllowanceUITests: XCTestCase {
    func testBalanceAndClaimInLightAndDarkAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-allowance", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            XCTAssertTrue(app.buttons["allowance-claim"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["allowance-remaining"].label.contains("¥1.000000"))
            app.buttons["allowance-claim"].tap()
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "allowance-remaining", "¥2.000000")).firstMatch.waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["allowance-claim"].exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "allowance-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["allowance-refresh"].tap()
            XCTAssertTrue(app.staticTexts["allowance-remaining"].label.contains("¥2.000000"))
            app.terminate()
        }
    }
    func testOfflineWalletKeepsSettingsAndRefreshAvailable() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-allowance", "--allowance-offline", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        XCTAssertTrue(app.staticTexts["连接已断开，请检查网络后重试。"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["allowance-refresh"].isEnabled)
        XCTAssertFalse(app.buttons["allowance-claim"].exists)
        XCTAssertTrue(app.buttons["退出登录"].exists)
    }
    func testEnglishAllowanceCopy() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-allowance", "-AppleLanguages", "(en)", "-appearance", "light"]
        app.launch()
        XCTAssertTrue(app.buttons["allowance-claim"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Model allowance"].exists)
        XCTAssertEqual(app.buttons["allowance-claim"].label, "Claim credit")
        XCTAssertTrue(app.staticTexts["allowance-remaining"].label.contains("Available balance"))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "allowance-english"; shot.lifetime = .keepAlways; add(shot)
    }
}
