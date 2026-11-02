import XCTest

final class ForkConversationUITests: XCTestCase {
    private let reply = "已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。"
    func testFileBranchInheritsSelectedMessageAndOpensSystemPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-fork", "--ui-test-message-file", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 10)); app.staticTexts["修改登录页"].tap()
        let file = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-attachment-0-")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 10)); file.press(forDuration: 1)
        XCTAssertTrue(app.buttons["从这里继续"].waitForExistence(timeout: 5)); app.buttons["从这里继续"].tap()
        app.buttons["create-fork"].tap()
        XCTAssertTrue(app.navigationBars["分支对话"].waitForExistence(timeout: 10))
        XCTAssertTrue(file.waitForExistence(timeout: 10)); XCTAssertTrue(file.label.contains("说明.txt"))
        XCTAssertFalse(app.staticTexts[reply].exists)
        file.tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 10))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "fork-inherited-file"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["QLOverlayDoneButtonAccessibilityIdentifier"].tap()
        XCTAssertTrue(app.buttons["分享"].waitForExistence(timeout: 5))
        app.terminate()
    }
    func testImageBranchInheritsSelectedMessageAndOpensOriginalPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-fork", "--ui-test-message-image", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 10)); app.staticTexts["修改登录页"].tap()
        let thumbnail = app.images.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-image-")).firstMatch
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 10)); thumbnail.press(forDuration: 1)
        XCTAssertTrue(app.buttons["从这里继续"].waitForExistence(timeout: 5)); app.buttons["从这里继续"].tap()
        app.buttons["create-fork"].tap()
        XCTAssertTrue(app.navigationBars["分支对话"].waitForExistence(timeout: 10))
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts[reply].exists, "Messages after the selected image must remain outside the branch")
        thumbnail.tap()
        XCTAssertTrue(app.images["gallery-image"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.images["gallery-image"].value as? String, "100%")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "fork-inherited-image"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["完成"].tap()
        XCTAssertTrue(app.navigationBars["分支对话"].waitForExistence(timeout: 5))
        app.terminate()
    }
    func testMessageBranchNavigatesAndKeepsSourceDraftInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-fork", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let composer = app.textFields["conversation-composer"]
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); composer.tap(); composer.typeText("Keep original draft")
            app.buttons["收起键盘"].tap()
            app.staticTexts[reply].press(forDuration: 1)
            XCTAssertTrue(app.buttons["从这里继续"].waitForExistence(timeout: 3)); app.buttons["从这里继续"].tap()
            XCTAssertTrue(app.buttons["create-fork"].waitForExistence(timeout: 5))
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "fork-confirm-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["create-fork"].tap()
            XCTAssertTrue(app.navigationBars["分支对话"].waitForExistence(timeout: 10))
            XCTAssertEqual(composer.value as? String, "说点什么…")
            app.navigationBars.buttons.element(boundBy: 0).tap()
            if app.buttons["示例对话"].exists { app.buttons["示例对话"].tap() }
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            XCTAssertEqual(composer.value as? String, "Keep original draft")
            app.terminate()
        }
    }
    func testBranchUsesConversationListNavigation() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-fork", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 10)); app.staticTexts["修改登录页"].tap()
        XCTAssertTrue(app.staticTexts[reply].waitForExistence(timeout: 5)); app.staticTexts[reply].press(forDuration: 1)
        app.buttons["从这里继续"].tap(); app.buttons["create-fork"].tap()
        XCTAssertTrue(app.navigationBars["分支对话"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.textFields["conversation-composer"].exists)
        app.terminate()
    }
    func testUnknownResultSurvivesRestartWithoutAutomaticResubmission() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-fork", "--ui-test-fork-lost", "--ui-test-fork-uncertain", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.staticTexts[reply].waitForExistence(timeout: 5)); app.staticTexts[reply].press(forDuration: 1)
        app.buttons["从这里继续"].tap(); app.buttons["create-fork"].tap()
        XCTAssertTrue(app.staticTexts["fork-status"].waitForExistence(timeout: 5)); app.terminate()
        app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["pending-fork"].waitForExistence(timeout: 5)); app.buttons["pending-fork"].tap()
        XCTAssertTrue(app.staticTexts["fork-status"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["retry-fork"].exists); XCTAssertFalse(app.navigationBars["分支对话"].exists)
        app.terminate()
    }
}
