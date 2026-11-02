import XCTest

final class LoginUITests: XCTestCase {
    private func replace(_ field: XCUIElement, with text: String) {
        field.tap()
        guard let value = field.value as? String else { return XCTFail("Missing field value") }
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count) + text)
    }
    func testLoginFailureKeepsPasswordAndSuccessOpensConversationsInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-login", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            let submit = app.buttons["login-submit"]
            XCTAssertTrue(submit.waitForExistence(timeout: 5)); XCTAssertFalse(submit.isEnabled)
            XCTAssertTrue(app.staticTexts["login-brand"].exists)
            XCTAssertFalse(app.textFields["login-server"].exists)
            XCTAssertFalse(app.buttons["服务地址"].exists)
            XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "funnypixels.cn")).firstMatch.exists)
            XCTAssertTrue(app.staticTexts["login-connection-status"].exists)
            XCTAssertEqual(app.textFields["login-username"].placeholderValue, "输入账号")
            XCTAssertEqual(app.secureTextFields["login-password"].placeholderValue, "输入密码")
            XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "在手机上继续")).firstMatch.exists)
            let initial = XCTAttachment(screenshot: app.screenshot()); initial.name = "login-\(appearance)"; initial.lifetime = .keepAlways; add(initial)
            app.textFields["login-username"].tap(); app.textFields["login-username"].typeText("  invalid  ")
            app.secureTextFields["login-password"].tap(); app.secureTextFields["login-password"].typeText("preview-only")
            app.buttons["显示密码"].tap()
            XCTAssertEqual(app.textFields["login-password"].value as? String, "preview-only")
            app.buttons["隐藏密码"].tap(); XCTAssertTrue(app.secureTextFields["login-password"].exists)
            submit.tap()
            XCTAssertTrue(app.staticTexts["login-error"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["登录失败，请检查账号和密码。"].exists)
            let failure = XCTAttachment(screenshot: app.screenshot()); failure.name = "login-error-\(appearance)"; failure.lifetime = .keepAlways; add(failure)
            app.buttons["显示密码"].tap()
            XCTAssertEqual(app.textFields["login-password"].value as? String, "preview-only")
            replace(app.textFields["login-username"], with: "slow")
            submit.tap(); XCTAssertFalse(submit.isEnabled)
            XCTAssertTrue(app.buttons["账号"].waitForExistence(timeout: 8))
            XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 5))
            app.revealConversationMenu(); app.buttons["账号"].tap(); app.buttons["退出登录"].tap()
            app.alerts.buttons["退出登录"].tap()
            XCTAssertTrue(submit.waitForExistence(timeout: 5)); XCTAssertFalse(submit.isEnabled)
            XCTAssertTrue(app.secureTextFields["login-password"].exists)
            app.terminate()
        }
    }
    func testInvalidServiceAddressAndNetworkFailureCanBeCorrected() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-login", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        app.textFields["login-username"].tap(); app.textFields["login-username"].typeText("offline")
        app.secureTextFields["login-password"].tap(); app.secureTextFields["login-password"].typeText("preview-only")
        app.buttons["login-submit"].tap()
        XCTAssertTrue(app.staticTexts["login-error"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["连接已断开，请检查网络后重试。"].exists)
        app.buttons["login-help"].tap()
        XCTAssertFalse(app.textFields["login-server"].exists)
        app.buttons["自建服务配置"].tap()
        let server = app.textFields["login-server"]
        replace(server, with: "http://example.com")
        app.buttons["login-help-done"].tap()
        app.buttons["login-submit"].tap()
        XCTAssertTrue(app.staticTexts["请检查服务地址，使用 https:// 开头的地址。"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["login-username"].exists)
        app.buttons["login-help"].tap()
        app.buttons["自建服务配置"].tap()
        replace(server, with: "https://example.com")
        app.buttons["login-help-done"].tap()
        replace(app.textFields["login-username"], with: "Mina")
        app.buttons["login-submit"].tap()
        XCTAssertTrue(app.buttons["账号"].waitForExistence(timeout: 5))
    }
}
