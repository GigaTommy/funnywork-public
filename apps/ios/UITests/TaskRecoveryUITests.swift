import XCTest

final class TaskRecoveryUITests: XCTestCase {
    func testUncreatedTaskKeepsChosenLocationAfterRestart() {
        let app = XCUIApplication()
        let args = ["--ui-test-directory", "--ui-test-creation-locations", "-AppleLanguages", "(zh-Hans)"]
        app.launchArguments = args + ["--ui-test-reset", "-appearance", "light"]
        app.launch(); app.buttons["new-conversation"].tap()
        app.buttons["creation-device"].tap(); app.buttons["Mac mini"].tap()
        app.buttons["creation-workspace"].tap(); app.buttons["Notes"].tap()
        let draft = app.descendants(matching: .any)["creation-draft"].firstMatch
        draft.tap(); draft.typeText("Task for Notes"); app.buttons["收起键盘"].tap()
        app.terminate()
        app.launchArguments = args + ["-appearance", "dark"]
        app.launch(); app.buttons["new-conversation"].tap()
        XCTAssertTrue(draft.waitForExistence(timeout: 5)); XCTAssertEqual(draft.value as? String, "Task for Notes")
        XCTAssertTrue(app.staticTexts["Mac mini"].exists); XCTAssertTrue(app.staticTexts["Notes"].exists)
        XCTAssertTrue(app.buttons["create-conversation"].isEnabled)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "uncreated-target-restored-dark"; shot.lifetime = .keepAlways; add(shot)
        app.terminate()
        app.launchArguments = args + ["--ui-test-creation-offline", "-appearance", "light"]
        app.launch(); app.buttons["new-conversation"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["creation-disabled-reason"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["create-conversation"].isEnabled)
        XCTAssertEqual(draft.value as? String, "Task for Notes")
    }
    func testSavedOldReviewOpensAfterNextResult() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-review-recovery", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            XCTAssertTrue(app.staticTexts["New result"].waitForExistence(timeout: 10))
            app.buttons["task-details"].tap()
            let resume = app.buttons["resume-review-2"]
            XCTAssertTrue(resume.waitForExistence(timeout: 5)); resume.tap()
            let comment = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(comment.waitForExistence(timeout: 5)); XCTAssertEqual(comment.value as? String, "Unsubmitted old review")
            XCTAssertTrue(app.staticTexts["Old result"].exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "old-review-restored-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["stage-review-feedback"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertTrue((composer.value as? String)?.contains("Unsubmitted old review") == true)
            app.terminate()
        }
    }
    func testQueueEditShowsSubmissionThenKeepsRejectedCopy() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-queue-edit", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let edit = app.buttons["编辑"]
            XCTAssertTrue(edit.waitForExistence(timeout: 10))
            app.swipeUp(); edit.tap()
            let text = app.textViews["queue-edit-text"]
            XCTAssertTrue(text.waitForExistence(timeout: 5)); text.tap(); text.typeText(" Edited copy")
            app.buttons["submit-queue-edit"].tap()
            let status = app.staticTexts["queue-edit-status"]
            XCTAssertTrue(status.waitForExistence(timeout: 5)); XCTAssertTrue(status.label.contains("修改已提交"))
            XCTAssertFalse(text.isEnabled)
            let waiting = XCTAttachment(screenshot: app.screenshot()); waiting.name = "queue-edit-submitted-\(appearance)"; waiting.lifetime = .keepAlways; add(waiting)
            app.buttons["完成"].tap(); app.buttons["模拟拒绝"].tap()
            XCTAssertTrue(app.staticTexts["修改未生效，编辑副本已保留"].waitForExistence(timeout: 10))
            edit.tap(); XCTAssertTrue(text.waitForExistence(timeout: 5))
            XCTAssertTrue((text.value as? String)?.contains("Edited copy") == true)
            let rejected = XCTAttachment(screenshot: app.screenshot()); rejected.name = "queue-edit-rejected-\(appearance)"; rejected.lifetime = .keepAlways; add(rejected)
            app.terminate()
        }
    }
}
