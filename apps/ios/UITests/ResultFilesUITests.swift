import XCTest

final class ResultFilesUITests: XCTestCase {
    func testFixedImagePreviewAndVersionFeedback() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-result-file", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let card = app.buttons["result-file-fixture-result:1"]
            XCTAssertTrue(card.waitForExistence(timeout: 10)); card.tap()
            XCTAssertTrue(app.buttons["下载原文件"].waitForExistence(timeout: 10))
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "fixed-result-preview-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["提出修改"].tap()
            let comment = app.descendants(matching: .any)["result-feedback-comment"].firstMatch
            XCTAssertTrue(comment.waitForExistence(timeout: 5)); comment.tap(); comment.typeText("Make the title larger")
            app.buttons["加入对话草稿"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            XCTAssertTrue((composer.value as? String)?.contains("resultId=fixture-result") == true)
            XCTAssertTrue((composer.value as? String)?.contains("version=1") == true)
            XCTAssertTrue((composer.value as? String)?.contains("Make the title larger") == true)
            app.terminate()
        }
    }
    func testPDFAndSavedWorkbookPreviews() {
        let app = XCUIApplication()
        for mode in ["pdf", "workbook"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-result-file", "--ui-test-result-" + mode, "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
            app.launch(); app.buttons["示例对话"].tap()
            let card = app.buttons["result-file-fixture-result:1"]; XCTAssertTrue(card.waitForExistence(timeout: 10)); card.tap()
            if mode == "pdf" { XCTAssertTrue(app.buttons["分享 PDF 预览"].waitForExistence(timeout: 10)) }
            else { XCTAssertTrue(app.staticTexts["25.0%"].waitForExistence(timeout: 10)); XCTAssertTrue(app.staticTexts["结果未保存"].exists); XCTAssertTrue(app.staticTexts["月报标题"].exists) }
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "fixed-result-\(mode)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate()
        }
    }

    func testLatePreviewOpensWithoutClosingTheResult() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-result-file", "--ui-test-result-pdf", "--ui-test-result-late-preview", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let card = app.buttons["result-file-fixture-result:1"]; XCTAssertTrue(card.waitForExistence(timeout: 10)); card.tap()
        XCTAssertTrue(app.buttons["下载原文件"].waitForExistence(timeout: 10)); XCTAssertFalse(app.buttons["分享 PDF 预览"].exists)
        XCTAssertTrue(app.buttons["分享 PDF 预览"].waitForExistence(timeout: 15))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "late-result-preview"; shot.lifetime = .keepAlways; add(shot)
    }
    func testPausedPDFAndWorkbookHaveAContinueEntry() {
        let app = XCUIApplication()
        for mode in ["pdf", "workbook"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-result-file", "--ui-test-result-" + mode, "--ui-test-result-pause-preview", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
            app.launch(); app.buttons["示例对话"].tap()
            let card = app.buttons["result-file-fixture-result:1"]; XCTAssertTrue(card.waitForExistence(timeout: 10)); card.tap()
            let pause = app.buttons["暂停"]; XCTAssertTrue(pause.waitForExistence(timeout: 10)); pause.tap()
            let resume = app.buttons["resume-result-download"]; XCTAssertTrue(resume.waitForExistence(timeout: 5))
            let paused = XCTAttachment(screenshot: app.screenshot()); paused.name = "paused-result-\(mode)"; paused.lifetime = .keepAlways; add(paused)
            resume.tap()
            if mode == "pdf" { XCTAssertTrue(app.buttons["分享 PDF 预览"].waitForExistence(timeout: 10)) }
            else { XCTAssertTrue(app.staticTexts["25.0%"].waitForExistence(timeout: 10)) }
            XCTAssertFalse(resume.exists); app.terminate()
        }
    }

}
