import XCTest

final class DeviceLinkUITests: XCTestCase {
    func testScanShowsDeviceBeforeConnectingInBothAppearances() {
        for appearance in ["light", "dark"] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-device-link", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-appearance", appearance]
            app.launch()
            app.buttons["scan-device-code"].tap()
            XCTAssertTrue(app.staticTexts["connection-device"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["connection-device"].label, "Tommy 的 Mac")
            XCTAssertFalse(app.staticTexts["linked-device"].exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Device connection \(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["confirm-device-connection"].tap()
            XCTAssertTrue(app.staticTexts["linked-device"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["linked-device"].label, "mac")
            app.terminate()
        }
    }
    func testExpiredCodeKeepsConfirmationUnavailable() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-device-link", "--ui-test-link-expired"]
        app.launch(); app.buttons["scan-device-code"].tap()
        XCTAssertTrue(app.staticTexts["connection-error"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["confirm-device-connection"].exists)
    }
}
