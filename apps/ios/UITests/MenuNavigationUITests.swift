import XCTest

final class MenuNavigationUITests: XCTestCase {
    func testAllDirectoryMenusReturnToConversationsInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-creation-locations", "--ui-test-push", "--ui-test-storage", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            for menu in ["设备", "搜索消息"] {
                app.revealConversationMenu()
                if menu == "搜索消息" { app.beginConversationSearch() }
                app.buttons[menu].tap()
                XCTAssertTrue(app.buttons["完成"].waitForExistence(timeout: 5))
                if menu == "设备" {
                    let shot = XCTAttachment(screenshot: app.screenshot())
                    shot.name = "device-status-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
                }
                app.buttons["完成"].tap()
                if menu == "搜索消息" { app.buttons["sidebar-search-cancel"].tap() }
                XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
            }
            app.revealConversationMenu(); app.buttons["已归档"].tap()
            app.revealConversationMenu(); app.buttons["返回对话"].tap()
            app.buttons["new-conversation"].tap()
            XCTAssertTrue(app.buttons["完成"].waitForExistence(timeout: 5))
            app.buttons["完成"].tap()
            app.revealConversationMenu(); app.buttons["账号"].tap()
            XCTAssertTrue(app.buttons["appearance-setting"].waitForExistence(timeout: 5))
            app.buttons["notification-settings"].tap()
            XCTAssertTrue(app.switches["notification-approvals"].waitForExistence(timeout: 5))
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.buttons["appearance-setting"].waitForExistence(timeout: 5))
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "menu-settings-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["完成"].tap()
            XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.textFields["login-username"].exists)
            app.terminate()
        }
    }
}
