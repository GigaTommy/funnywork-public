import XCTest

final class ImageSavingUITests: XCTestCase {
    func testSaveImageFeedbackAndPermissionFailureInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            for failure in ["", "--ui-test-image-save-denied", "--ui-test-image-save-failed"] {
                app.launchArguments = ["--ui-test-conversation", "--ui-test-message-image", "--ui-test-gallery", "--ui-test-image-save", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance] + (failure.isEmpty ? [] : [failure])
                app.launch(); app.buttons["示例对话"].tap()
                let thumbnail = app.images.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-image-")).firstMatch
                XCTAssertTrue(thumbnail.waitForExistence(timeout: 10)); thumbnail.tap()
                let save = app.buttons["gallery-save"]
                XCTAssertTrue(save.waitForExistence(timeout: 10)); XCTAssertTrue(app.buttons["gallery-share"].exists)
                if failure.isEmpty { app.images["gallery-image"].doubleTap() }
                save.tap()
                if failure.isEmpty {
                    XCTAssertTrue(app.otherElements["gallery-saved"].waitForExistence(timeout: 3) || app.staticTexts["gallery-saved"].exists)
                    XCTAssertNotEqual(app.images["gallery-image"].value as? String, "100%")
                    let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "saved-image-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
                    app.buttons["gallery-next"].tap()
                    XCTAssertFalse(app.otherElements["gallery-saved"].exists); XCTAssertFalse(app.staticTexts["gallery-saved"].exists)
                } else {
                    XCTAssertTrue(app.alerts["无法保存图片"].waitForExistence(timeout: 3))
                    XCTAssertEqual(app.alerts.buttons["打开设置"].exists, failure.hasSuffix("denied"))
                    app.alerts.buttons["完成"].tap()
                    XCTAssertTrue(save.isEnabled)
                }
                app.terminate()
            }
        }
    }
}
