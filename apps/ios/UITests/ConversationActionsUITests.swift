import XCTest

final class ConversationActionsUITests: XCTestCase {
    func testOnlyConcreteActionsAppearInsideTheirConversation() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            for questions in [false, true] {
                app.launchArguments = ["--ui-test-directory", "--ui-test-pending-context", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
                if questions { app.launchArguments.append("--ui-test-questions") }
                app.launch()
                let conversation = app.staticTexts["修改登录页"]
                XCTAssertTrue(conversation.waitForExistence(timeout: 10))
                XCTAssertFalse(app.buttons["pending-inbox"].exists)
                XCTAssertFalse(app.buttons["待处理"].exists)
                conversation.tap()
                let action = app.buttons["pending-shortcut"]
                XCTAssertTrue(action.waitForExistence(timeout: 10))
                XCTAssertEqual(action.label, questions ? "需要你的回答" : "需要你的批准")
                XCTAssertFalse(app.buttons["待处理"].exists)
                action.tap()
                if !questions {
                    XCTAssertTrue(app.buttons["本次允许"].waitForExistence(timeout: 5))
                    app.buttons["本次允许"].tap()
                    XCTAssertTrue(app.staticTexts["事项已更新"].waitForExistence(timeout: 5))
                }
                app.terminate()
            }
        }
    }
}
