import XCTest

final class TaskUXUITests: XCTestCase {
    func testQueueWithdrawalPreservesNewTyping() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-queue-withdrawal", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 10)); composer.tap(); composer.typeText("Keep typing")
            if app.buttons["收起键盘"].exists { app.buttons["收起键盘"].tap() }
            let withdraw = app.buttons["withdraw-queue-queue-item"]
            for _ in 0..<3 where !withdraw.isHittable { app.swipeUp() }
            XCTAssertTrue(withdraw.waitForExistence(timeout: 5)); withdraw.tap()
            let expected = "Keep typing\n\n检查测试结果"
            let predicate = NSPredicate(format: "value == %@", expected)
            expectation(for: predicate, evaluatedWith: composer); waitForExpectations(timeout: 5)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "queue-withdrawal-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate()
        }
    }
    func testReviewAddsFileLineFeedbackToDraft() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            if appearance == "dark" {
                app.buttons["task-details"].tap()
                let entry = app.buttons["review-result-details"]
                XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
            } else {
                let review = app.buttons["review-result"]
                XCTAssertTrue(review.waitForExistence(timeout: 10))
                app.swipeUp()
                review.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
            }
            XCTAssertTrue(app.descendants(matching: .any)["review-comment"].firstMatch.waitForExistence(timeout: 5))
            let file = app.buttons["review-change-1-0-0"]
            for _ in 0..<4 where !file.isHittable { app.swipeUp() }
            XCTAssertTrue(file.waitForExistence(timeout: 5)); file.tap()
            let line = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "+let retries = 3")).firstMatch
            XCTAssertTrue(line.waitForExistence(timeout: 5)); line.tap()
            XCTAssertTrue(app.scrollViews["review-file-diff"].exists)
            app.buttons["review-file-done"].tap()
            let comment = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(comment.waitForExistence(timeout: 5))
            XCTAssertTrue((comment.value as? String)?.contains("Config.swift") == true)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "result-review-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["stage-review-feedback"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            XCTAssertTrue((composer.value as? String)?.contains("Config.swift") == true)
            XCTAssertTrue((composer.value as? String)?.contains("#1") == true)
            app.terminate()
        }
    }
    func testContinuousFileReviewKeepsBothLineReferencesAndNotes() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap(); app.buttons["task-details"].tap()
            let entry = app.buttons["review-result-details"]
            XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
            let file = app.buttons["review-change-1-0-0"]
            XCTAssertTrue(file.waitForExistence(timeout: 5)); for _ in 0..<3 where !file.isHittable { app.swipeUp() }; file.tap()
            let added = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "+let retries = 3")).firstMatch
            XCTAssertTrue(added.waitForExistence(timeout: 5)); added.tap()
            let comment = app.descendants(matching: .any)["review-file-comment"].firstMatch
            XCTAssertTrue(comment.waitForExistence(timeout: 5)); let once = comment.value as? String
            added.tap(); XCTAssertEqual(comment.value as? String, once)
            comment.tap(); comment.typeText(" Keep the retry check."); app.buttons["收起键盘"].tap()
            let removed = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "-let retries = 1")).firstMatch
            for _ in 0..<3 where !removed.isHittable { app.swipeDown() }; removed.tap()
            XCTAssertTrue(app.scrollViews["review-file-diff"].exists)
            XCTAssertTrue((comment.value as? String)?.contains("retry check") == true)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "continuous-file-review-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["review-file-done"].tap()
            let aggregate = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(aggregate.waitForExistence(timeout: 5))
            XCTAssertTrue((aggregate.value as? String)?.contains("#1 · Config.swift") == true)
            XCTAssertTrue((aggregate.value as? String)?.contains("-let retries = 1") == true)
            XCTAssertTrue((aggregate.value as? String)?.contains("+let retries = 3") == true)
            app.buttons["stage-review-feedback"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertTrue((composer.value as? String)?.contains("retry check") == true)
            app.terminate()
        }
    }
    func testNewTaskDraftSurvivesDismissalAndOpensInCreatedChat() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-task-creation", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["new-conversation"].tap()
        let draft = app.descendants(matching: .any)["creation-draft"].firstMatch
        XCTAssertTrue(draft.waitForExistence(timeout: 5)); draft.tap(); draft.typeText("Check release")
        app.buttons["收起键盘"].tap(); app.buttons["完成"].tap()
        app.buttons["new-conversation"].tap()
        XCTAssertTrue(draft.waitForExistence(timeout: 5)); XCTAssertEqual(draft.value as? String, "Check release")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "new-task-draft"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["create-conversation"].tap()
        let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 12)); XCTAssertEqual(composer.value as? String, "Check release")
    }
    func testLongReviewKeepsFilesAndFeedbackAccessibleBeforeFullReplies() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-long-review", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap(); app.buttons["task-details"].tap()
            let entry = app.buttons["review-result-details"]
            XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
            let file = app.buttons["review-change-1-0-0"]
            XCTAssertTrue(file.waitForExistence(timeout: 5)); XCTAssertTrue(file.isHittable)
            let comment = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(comment.isHittable)
            let fullText = app.scrollViews["review-content"].staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "完整回复末尾标记")).firstMatch
            XCTAssertFalse(fullText.exists)
            let first = XCTAttachment(screenshot: app.screenshot()); first.name = "review-first-screen-\(appearance)"; first.lifetime = .keepAlways; add(first)
            comment.tap(); comment.typeText("Please check retries")
            let stage = app.buttons["stage-review-feedback"]
            XCTAssertTrue(app.keyboards.firstMatch.exists); XCTAssertTrue(stage.isHittable)
            XCTAssertTrue(comment.isHittable); XCTAssertLessThanOrEqual(comment.frame.maxY, stage.frame.minY + 1)
            let keyboard = XCTAttachment(screenshot: app.screenshot()); keyboard.name = "review-keyboard-\(appearance)"; keyboard.lifetime = .keepAlways; add(keyboard)
            app.buttons["收起键盘"].tap()
            let full = app.descendants(matching: .any)["review-full-replies"].firstMatch
            for _ in 0..<3 where !full.isHittable { app.swipeUp() }
            full.tap(); XCTAssertTrue(fullText.waitForExistence(timeout: 5))
            stage.tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertTrue((composer.value as? String)?.contains("Please check retries") == true)
            app.terminate()
        }
    }
    func testNewTaskCanBeCreatedWithKeyboardOpen() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-task-creation", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            if appearance == "dark" { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
            app.launch(); app.buttons["new-conversation"].tap()
            let draft = app.descendants(matching: .any)["creation-draft"].firstMatch
            XCTAssertTrue(draft.waitForExistence(timeout: 5)); draft.tap(); draft.typeText("Check release")
            let target = app.descendants(matching: .any)["creation-target-summary"].firstMatch
            XCTAssertTrue(target.waitForExistence(timeout: 5)); XCTAssertTrue(target.label.contains("MacBook Pro")); XCTAssertTrue(target.label.contains("funnywork"))
            let create = app.buttons["create-conversation"]
            XCTAssertTrue(app.keyboards.firstMatch.exists); XCTAssertTrue(create.isHittable); XCTAssertTrue(create.isEnabled)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "creation-keyboard-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            create.tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 12)); XCTAssertEqual(composer.value as? String, "Check release")
            app.terminate()
        }
    }

    func testReviewSwitchesFilesWithoutLosingNotesOrReferences() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-multi-file-review", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap(); app.buttons["task-details"].tap()
            let entry = app.buttons["review-result-details"]
            XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
            let source = app.staticTexts["review-source"]
            XCTAssertTrue(source.waitForExistence(timeout: 5)); XCTAssertTrue(source.label.contains("#1"))
            app.buttons["review-change-1-0-0"].tap()
            let previous = app.buttons["review-file-previous"], next = app.buttons["review-file-next"]
            XCTAssertTrue(next.waitForExistence(timeout: 5)); XCTAssertFalse(previous.isEnabled)
            let firstLine = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "+let retries = 3")).firstMatch
            let header = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "--- a/Config.swift")).firstMatch
            XCTAssertTrue(header.exists)
            XCTAssertLessThan(header.frame.minY, app.navigationBars["Config.swift"].frame.maxY + 60, "Short changes should begin below the navigation bar")
            firstLine.tap()
            let note = app.descendants(matching: .any)["review-file-comment"].firstMatch
            note.tap(); note.typeText("Check retry count")
            next.tap()
            let secondLine = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "+let ready = true")).firstMatch
            XCTAssertTrue(secondLine.waitForExistence(timeout: 5)); XCTAssertFalse(next.isEnabled)
            secondLine.tap(); note.tap(); note.typeText("Check ready flag")
            app.buttons["收起键盘"].tap()
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "review-second-file-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            previous.tap(); XCTAssertTrue(firstLine.waitForExistence(timeout: 5))
            next.tap(); XCTAssertTrue(secondLine.waitForExistence(timeout: 5))
            app.buttons["review-file-done"].tap()
            let aggregate = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(aggregate.waitForExistence(timeout: 5))
            let value = aggregate.value as? String ?? ""
            XCTAssertTrue(value.contains("Config.swift")); XCTAssertTrue(value.contains("Notes.swift"))
            XCTAssertEqual(value.components(separatedBy: "Check retry count").count, 2)
            XCTAssertEqual(value.components(separatedBy: "Check ready flag").count, 2)
            app.buttons["stage-review-feedback"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertTrue((composer.value as? String)?.contains("Check ready flag") == true)
            app.terminate()
        }
    }

    func testExpandedTaskPersistsAcrossRestartAndReturnsToCreation() {
        let app = XCUIApplication()
        let text = (1...12).map { "Task requirement \($0)" }.joined(separator: "\n")
        for appearance in ["light", "dark"] {
            let args = ["--ui-test-directory", "--ui-test-task-creation", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launchArguments = args + ["--ui-test-reset"]
            app.launch(); app.buttons["new-conversation"].tap(); app.buttons["expand-creation-draft"].tap()
            let editor = app.textViews["expanded-task-text"]
            XCTAssertTrue(editor.waitForExistence(timeout: 5)); editor.tap(); editor.typeText(text)
            app.buttons["expanded-composer-hide-keyboard"].tap()
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "expanded-task-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate(); app.launchArguments = args; app.launch(); app.buttons["new-conversation"].tap()
            app.buttons["expand-creation-draft"].tap(); XCTAssertTrue(editor.waitForExistence(timeout: 5)); XCTAssertEqual(editor.value as? String, text)
            app.buttons["close-expanded-task"].tap()
            let create = app.buttons["create-conversation"]
            XCTAssertTrue(create.waitForExistence(timeout: 5)); create.tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 12)); XCTAssertEqual(composer.value as? String, text)
            app.terminate()
        }
    }
    func testExpandedReviewKeepsReferencesAndRestoresOriginalReview() {
        let app = XCUIApplication()
        let text = (1...10).map { "Review adjustment \($0)" }.joined(separator: "\n")
        for appearance in ["light", "dark"] {
            let args = ["--ui-test-conversation", "--ui-test-diff", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launchArguments = args + ["--ui-test-reset"]
            app.launch(); app.buttons["示例对话"].tap(); app.buttons["task-details"].tap()
            let entry = app.buttons["review-result-details"]
            XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
            let file = app.buttons["review-change-1-0-0"]
            XCTAssertTrue(file.waitForExistence(timeout: 5)); file.tap()
            let line = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "+let retries = 3")).firstMatch
            XCTAssertTrue(line.waitForExistence(timeout: 5)); line.tap(); app.buttons["review-file-done"].tap()
            let expand = app.buttons["expand-review-comment"]
            XCTAssertTrue(expand.waitForExistence(timeout: 5)); expand.tap()
            let editor = app.textViews["expanded-review-text"]
            XCTAssertTrue(editor.waitForExistence(timeout: 5)); editor.tap(); editor.typeText("\n" + text)
            let expected = editor.value as? String
            XCTAssertTrue(expected?.contains("#1 · Config.swift") == true); XCTAssertTrue(expected?.contains("Review adjustment 10") == true)
            app.buttons["expanded-composer-hide-keyboard"].tap()
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "expanded-review-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate(); app.launchArguments = args; app.launch(); app.buttons["示例对话"].tap(); app.buttons["task-details"].tap()
            let resume = app.buttons["resume-review-1"]
            XCTAssertTrue(resume.waitForExistence(timeout: 5)); resume.tap()
            XCTAssertTrue(expand.waitForExistence(timeout: 5)); expand.tap()
            XCTAssertTrue(editor.waitForExistence(timeout: 5)); XCTAssertEqual(editor.value as? String, expected)
            app.buttons["close-expanded-review"].tap()
            app.buttons["stage-review-feedback"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertTrue((composer.value as? String)?.contains("Review adjustment 10") == true)
            XCTAssertTrue((composer.value as? String)?.contains("+let retries = 3") == true)
            app.terminate()
        }
    }

    func testPendingCreationPreviewKeepsSubmittedTextAcrossRestart() {
        let app = XCUIApplication()
        let text = (1...14).map { "Submitted requirement \($0)" }.joined(separator: "\n")
        for appearance in ["light", "dark"] {
            let args = ["--ui-test-directory", "--ui-test-task-creation", "--ui-test-creation-uncertain", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launchArguments = args + ["--ui-test-reset"]
            app.launch(); app.buttons["new-conversation"].tap(); app.buttons["expand-creation-draft"].tap()
            let editor = app.textViews["expanded-task-text"]
            XCTAssertTrue(editor.waitForExistence(timeout: 5)); editor.tap(); editor.typeText(text)
            app.buttons["close-expanded-task"].tap()
            let create = app.buttons["create-conversation"]
            XCTAssertTrue(create.waitForExistence(timeout: 5)); create.tap()
            let preview = app.buttons["preview-submitted-task"]
            XCTAssertTrue(preview.waitForExistence(timeout: 5)); preview.tap()
            let body = app.staticTexts["task-content-preview-text"]
            XCTAssertTrue(body.waitForExistence(timeout: 5)); XCTAssertEqual(body.label, text)
            XCTAssertFalse(app.textViews["expanded-task-text"].exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "submitted-task-preview-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["close-task-content-preview"].tap()
            XCTAssertTrue(app.staticTexts["请先核对结果，避免重复创建。"].exists)
            app.terminate(); app.launchArguments = args; app.launch(); app.buttons["new-conversation"].tap()
            XCTAssertTrue(preview.waitForExistence(timeout: 5)); preview.tap()
            XCTAssertTrue(body.waitForExistence(timeout: 5)); XCTAssertEqual(body.label, text)
            app.terminate()
        }
    }
    func testReviewMergePreviewDoesNotChangeExistingDraft() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); composer.tap(); composer.typeText("Keep my plan")
            app.buttons["收起键盘"].tap(); app.buttons["task-details"].tap()
            let entry = app.buttons["review-result-details"]
            XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
            let comment = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(comment.waitForExistence(timeout: 5)); comment.tap(); comment.typeText("Adjust retries")
            app.buttons["收起键盘"].tap(); app.buttons["preview-review-merge"].tap()
            let body = app.staticTexts["task-content-preview-text"]
            XCTAssertTrue(body.waitForExistence(timeout: 5)); let merged = body.label
            XCTAssertTrue(merged.hasPrefix("Keep my plan\n\n")); XCTAssertTrue(merged.contains("#1")); XCTAssertTrue(merged.hasSuffix("Adjust retries"))
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "review-merge-preview-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["close-task-content-preview"].tap()
            XCTAssertEqual(comment.value as? String, "Adjust retries")
            app.buttons["完成"].tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertEqual(composer.value as? String, "Keep my plan")
            app.buttons["task-details"].tap()
            let resume = app.buttons["resume-review-1"]
            XCTAssertTrue(resume.waitForExistence(timeout: 5)); resume.tap()
            let stage = app.buttons["stage-review-feedback"]
            XCTAssertTrue(stage.waitForExistence(timeout: 5)); stage.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertEqual(composer.value as? String, merged)
            app.terminate()
        }
    }

}
