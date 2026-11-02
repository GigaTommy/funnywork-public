import XCTest

final class CodeReadingUITests: XCTestCase {
    func testCodeExpandsWrapsAndReturnsToConversation() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-code-reading", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let expand = app.buttons["expand-code"].firstMatch
            XCTAssertTrue(expand.waitForExistence(timeout: 5))
            if !expand.isHittable { app.swipeDown() }
            expand.tap()
            let source = app.staticTexts["expanded-code-text"]
            XCTAssertTrue(source.waitForExistence(timeout: 5))
            XCTAssertTrue(source.label.contains("你好，funnywork"))
            XCTAssertTrue(source.label.contains("\n\n    let description"))
            let wrap = app.switches["code-wrap"]
            XCTAssertEqual(wrap.value as? String, "1")
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "expanded-code-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["copy-expanded-code"].tap()
            XCTAssertTrue(app.buttons["copy-expanded-code"].label.contains("已复制"))
            XCTAssertTrue(app.buttons["share-expanded-code"].exists)
            wrap.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
            XCTAssertTrue(app.scrollViews["expanded-code-horizontal"].waitForExistence(timeout: 3))
            XCTAssertEqual(wrap.value as? String, "0")
            app.scrollViews["expanded-code-horizontal"].swipeLeft()
            wrap.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
            XCTAssertEqual(wrap.value as? String, "1")
            app.buttons["完成"].tap()
            XCTAssertTrue(app.descendants(matching: .any)["conversation-composer"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["copy-expanded-code"].exists)
            app.terminate()
        }
    }
    func testExpandedDiffKeepsChangeLabelsAndLineNumbers() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let expand = app.buttons["expand-code"].firstMatch
        XCTAssertTrue(expand.waitForExistence(timeout: 5))
        if !expand.isHittable { app.swipeDown() }
        expand.tap()
        XCTAssertTrue(app.buttons["copy-expanded-code"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.scrollViews["message-diff"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["新增: +let retries = 3"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["删除: -let retries = 1"].exists)
        XCTAssertFalse(app.switches["code-wrap"].exists)
        app.buttons["完成"].tap()
        XCTAssertTrue(app.buttons["copy-code"].waitForExistence(timeout: 5))
    }
}
