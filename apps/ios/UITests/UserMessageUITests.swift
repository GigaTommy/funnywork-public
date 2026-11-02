import XCTest

final class UserMessageUITests: XCTestCase {
    func testShortUserMessageSelectionAndBranchKeepOriginalDraftInBothAppearances() {
        let app = XCUIApplication(), original = "继续完善手机端的远程操作。"
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-fork", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            let source = app.staticTexts["修改登录页"]
            XCTAssertTrue(source.waitForExistence(timeout: 10)); source.tap()
            let text = app.staticTexts["user-message-text"], composer = app.textFields["conversation-composer"]
            XCTAssertTrue(text.waitForExistence(timeout: 10)); XCTAssertEqual(text.label, original)
            XCTAssertFalse(app.buttons["expand-user-message"].exists)
            composer.tap(); composer.typeText("Keep source draft"); app.buttons["收起键盘"].tap()
            text.press(forDuration: 1)
            let select = app.collectionViews.buttons["选择文本"].firstMatch
            XCTAssertTrue(select.waitForExistence(timeout: 5)); select.tap()
            let selection = app.textViews["selectable-message-text"]
            XCTAssertTrue(selection.waitForExistence(timeout: 5)); XCTAssertEqual(selection.value as? String, original)
            app.buttons["copy-entire-message"].tap(); XCTAssertEqual(app.buttons["copy-entire-message"].label, "已复制")
            app.buttons["完成"].tap()
            XCTAssertTrue(text.waitForExistence(timeout: 5)); text.press(forDuration: 1)
            let branch = app.collectionViews.buttons["从这里继续"].firstMatch
            XCTAssertTrue(branch.waitForExistence(timeout: 5)); branch.tap()
            XCTAssertTrue(app.buttons["create-fork"].waitForExistence(timeout: 5)); app.buttons["create-fork"].tap()
            XCTAssertTrue(app.navigationBars["分支对话"].waitForExistence(timeout: 10))
            XCTAssertEqual(text.label, original)
            XCTAssertFalse(app.staticTexts["已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。"].exists)
            XCTAssertEqual(composer.value as? String, "说点什么…")
            if !source.isHittable { app.navigationBars["分支对话"].buttons.element(boundBy: 0).tap() }
            XCTAssertTrue(source.waitForExistence(timeout: 5)); source.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertEqual(composer.value as? String, "Keep source draft")
            app.terminate()
        }
    }
    func testLongMessageExpandsCollapsesAndSelectionKeepsFullTextInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-long-user-message", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let expand = app.buttons["expand-user-message"], text = app.staticTexts["user-message-text"]
            XCTAssertTrue(expand.waitForExistence(timeout: 10))
            let compactHeight = text.frame.height
            let compact = XCTAttachment(screenshot: app.screenshot()); compact.name = "user-message-collapsed-\(appearance)"; compact.lifetime = .keepAlways; add(compact)
            expand.tap()
            let collapse = app.buttons["collapse-user-message"]
            XCTAssertTrue(collapse.waitForExistence(timeout: 5))
            XCTAssertGreaterThan(text.frame.height, compactHeight * 1.5)
            for _ in 0..<5 {
                if collapse.isHittable && collapse.frame.maxY < app.textFields["conversation-composer"].frame.minY { break }
                app.swipeUp()
            }
            XCTAssertTrue(collapse.isHittable)
            let full = XCTAttachment(screenshot: app.screenshot()); full.name = "user-message-expanded-\(appearance)"; full.lifetime = .keepAlways; add(full)
            collapse.tap(); XCTAssertTrue(expand.waitForExistence(timeout: 5))
            for _ in 0..<5 { if text.isHittable { break }; app.swipeDown() }
            text.press(forDuration: 1)
            let select = app.collectionViews.buttons["选择文本"].firstMatch
            XCTAssertTrue(select.waitForExistence(timeout: 5))
            select.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            let selection = app.textViews["selectable-message-text"]
            XCTAssertTrue(selection.waitForExistence(timeout: 5))
            XCTAssertTrue((selection.value as? String)?.hasSuffix("完整消息末尾") == true)
            app.buttons["copy-entire-message"].tap()
            XCTAssertEqual(app.buttons["copy-entire-message"].label, "已复制")
            app.buttons["完成"].tap()
            XCTAssertTrue(expand.waitForExistence(timeout: 5))
            app.terminate()
        }
    }
}
