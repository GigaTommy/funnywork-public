import XCTest
final class RemoteWorkspaceUITests: XCTestCase {
    func testFilesEditorAndGitInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-workspace", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            XCTAssertTrue(app.buttons["README.md"].waitForExistence(timeout: 8))
            app.buttons["添加"].tap()
            XCTAssertTrue(app.alerts["添加工作区"].waitForExistence(timeout: 3))
            app.alerts.buttons["取消"].tap()
            app.buttons["README.md"].tap()
            XCTAssertTrue(app.textViews.firstMatch.waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["保存"].exists)
            let editor = XCTAttachment(screenshot: app.screenshot()); editor.name = "workspace-editor-\(appearance)"; editor.lifetime = .keepAlways; add(editor)
            app.buttons["保存"].tap()
            app.buttons["workspace-editor-close"].tap()
            app.segmentedControls.buttons["Git"].tap()
            XCTAssertTrue(app.buttons["新工作树"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["feature"].waitForExistence(timeout: 5))
            let git = XCTAttachment(screenshot: app.screenshot()); git.name = "workspace-git-\(appearance)"; git.lifetime = .keepAlways; add(git)
            app.terminate()
        }
    }
}
