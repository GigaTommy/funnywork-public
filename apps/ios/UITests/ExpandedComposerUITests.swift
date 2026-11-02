import XCTest

final class ExpandedComposerUITests: XCTestCase {
    func testClosingEditorReturnsToManualSend() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-upload", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let composer = app.textFields["conversation-composer"]
        XCTAssertTrue(composer.waitForExistence(timeout: 5)); composer.tap(); composer.typeText("Draft")
        app.buttons["expand-composer"].tap()
        let editor = app.textViews["expanded-composer-text"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5)); editor.tap(); editor.typeText(" edited")
        app.buttons["close-expanded-composer"].tap()
        XCTAssertEqual(composer.value as? String, "Draft edited")
        let send = app.buttons["send-message"]
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: send)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
        send.tap(); XCTAssertEqual(composer.value as? String, "说点什么…")
        app.terminate()
    }

    func testEditingPreservesDraftAcrossCloseAndOfflineRestartInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance, "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
            app.launch(); app.buttons["示例对话"].tap()
            let composer = app.textFields["conversation-composer"]
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); composer.tap()
            composer.typeText("First line\nSecond line\nThird line")
            app.buttons["expand-composer"].tap()
            let editor = app.textViews["expanded-composer-text"]
            XCTAssertTrue(editor.waitForExistence(timeout: 5))
            XCTAssertEqual(editor.value as? String, "First line\nSecond line\nThird line")
            editor.tap(); editor.typeText("\nFourth line")
            app.buttons["expanded-composer-hide-keyboard"].tap()
            XCTAssertGreaterThan(editor.frame.height, app.frame.height * 0.5)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "expanded-composer-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["close-expanded-composer"].tap()
            XCTAssertEqual(composer.value as? String, "First line\nSecond line\nThird line\nFourth line")
            app.terminate()
            app.launchArguments = ["--ui-test-conversation", "--ui-test-offline", "-AppleLanguages", "(zh-Hans)"]
            app.launch(); app.buttons["示例对话"].tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            XCTAssertEqual(composer.value as? String, "First line\nSecond line\nThird line\nFourth line")
            XCTAssertFalse(app.buttons["send-message"].isEnabled)
            app.buttons["expand-composer"].tap()
            XCTAssertTrue(editor.waitForExistence(timeout: 5))
            XCTAssertEqual(editor.value as? String, "First line\nSecond line\nThird line\nFourth line")
            app.terminate()
        }
    }
}
