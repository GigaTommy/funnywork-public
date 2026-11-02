import XCTest

final class DeviceDirectoryUITests: XCTestCase {
    func testDisableAndRestoreRequireConfirmation() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-creation-locations", "--ui-test-device-management", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.revealConversationMenu(); app.buttons["远程"].tap()
            let action = app.buttons["device-access-device"]
            XCTAssertTrue(action.waitForExistence(timeout: 5))
            action.tap()
            XCTAssertTrue(app.alerts["停用设备？"].waitForExistence(timeout: 3))
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "disable-confirm-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.alerts.buttons["取消"].tap()
            XCTAssertTrue(app.buttons["device-create-device"].exists)
            action.tap(); app.alerts.buttons["停用"].tap()
            XCTAssertTrue(app.staticTexts["已停用"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["device-create-device"].exists)
            action.tap()
            XCTAssertTrue(app.alerts["恢复设备？"].waitForExistence(timeout: 3))
            app.alerts.buttons["恢复"].tap()
            XCTAssertTrue(app.staticTexts["离线"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.staticTexts["已停用"].exists)
            XCTAssertFalse(app.buttons["device-create-device"].exists)
            app.terminate()
        }
    }
    func testOfflineDeviceCannotStartConversation() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-creation-locations", "--ui-test-creation-offline", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.revealConversationMenu(); app.buttons["远程"].tap()
        XCTAssertTrue(app.staticTexts["Mac mini"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["离线"].exists)
        XCTAssertFalse(app.buttons["device-create-second"].exists)
        XCTAssertTrue(app.buttons["device-create-device"].isEnabled)
        app.buttons["完成"].tap()
        XCTAssertTrue(app.buttons["new-conversation"].exists)
    }
    func testDevicesShowWorkspacesAndCreateOnChosenDevice() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-creation-locations", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.revealConversationMenu(); app.buttons["远程"].tap()
            XCTAssertTrue(app.buttons["device-create-second"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["Notes"].exists)
            XCTAssertTrue(app.staticTexts["macOS"].exists)
            XCTAssertTrue(app.staticTexts["最近连接"].exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "devices-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["device-create-second"].tap()
            XCTAssertTrue(app.buttons["creation-device"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["Mac mini"].exists)
            XCTAssertFalse(app.buttons["create-conversation"].isEnabled)
            app.buttons["creation-workspace"].tap(); app.buttons["Notes"].tap()
            app.buttons["create-conversation"].tap()
            XCTAssertTrue(app.staticTexts["未命名对话"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
}
