import XCTest

extension XCUIApplication {
    func revealConversationMenu() {
        let button = buttons["open-sidebar"]
        if !buttons["sidebar-search-toggle"].isHittable && !buttons["sidebar-search-cancel"].isHittable && button.exists && button.isHittable { button.tap() }
    }
    func beginConversationSearch() {
        revealConversationMenu()
        let search = buttons["sidebar-search-toggle"]
        if search.waitForExistence(timeout: 5) { search.tap() }
    }
    func dismissConversationMenu() {
        // The exposed strip is the dismissal target; the drawer has no redundant close icon.
        coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.4)).tap()
    }
}

final class SidebarUITests: XCTestCase {
    func testPinnedSearchRemainsUsableAtAccessibilityTextSize() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-management", "--ui-test-reset", "-AppleLanguages", "(en)", "-appearance", "dark",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 10))
        app.beginConversationSearch()
        let search = app.textFields["mobile-history-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5)); XCTAssertTrue(search.isHittable)
        let cancel = app.buttons["sidebar-search-cancel"]
        XCTAssertTrue(cancel.isHittable)
        XCTAssertLessThanOrEqual(cancel.frame.height, 56)
        let status = app.staticTexts["remote-connection-status"]
        XCTAssertTrue(status.exists)
        XCTAssertGreaterThanOrEqual(status.frame.minY, app.buttons["scope-desktop"].frame.minY - 0.5)
        XCTAssertLessThanOrEqual(status.frame.maxY, app.buttons["scope-desktop"].frame.maxY + 0.5)
        search.tap(); search.typeText("needle")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "pinned-search-accessibility-en-dark"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["sidebar-search-cancel"].tap()
        XCTAssertTrue(search.waitForNonExistence(timeout: 5))
        app.dismissConversationMenu()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 5))
    }
    func testSingleSearchEntryCancelAndDraftInBothLanguagesAndAppearances() {
        for language in ["zh-Hans", "en"] {
            for appearance in ["light", "dark"] {
                let app = XCUIApplication()
                app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-management", "--ui-test-reset", "-AppleLanguages", "(\(language))", "-appearance", appearance]
                app.launch()
                let composer = app.textFields["home-chat-message"]
                XCTAssertTrue(composer.waitForExistence(timeout: 10))
                composer.tap(); composer.typeText("Keep my draft")
                app.revealConversationMenu()
                XCTAssertEqual(app.buttons.matching(identifier: "sidebar-search-toggle").count, 1)
                XCTAssertFalse(app.buttons["open-sidebar"].isHittable)
                XCTAssertFalse(app.buttons["new-mobile-chat-top"].isHittable)
                XCTAssertFalse(app.buttons["close-sidebar"].exists)
                XCTAssertFalse(app.textFields["mobile-history-search"].exists)
                XCTAssertTrue(app.buttons["new-mobile-chat"].isHittable)
                app.beginConversationSearch()
                let field = app.textFields["mobile-history-search"]
                XCTAssertTrue(field.waitForExistence(timeout: 5))
                XCTAssertTrue(app.buttons["sidebar-search-cancel"].isHittable)
                XCTAssertFalse(app.buttons["sidebar-search-toggle"].exists)
                XCTAssertFalse(app.buttons["mobile-history-refresh"].exists)
                XCTAssertFalse(app.buttons["mobile-history-hide-keyboard"].exists)
                field.tap(); field.typeText("No matching chat")
                let shot = XCTAttachment(screenshot: app.screenshot())
                shot.name = "sidebar-search-\(language)-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
                app.buttons["sidebar-search-cancel"].tap()
                XCTAssertTrue(field.waitForNonExistence(timeout: 5))
                XCTAssertTrue(app.buttons["new-mobile-chat"].isHittable)
                app.dismissConversationMenu()
                XCTAssertTrue(composer.waitForExistence(timeout: 5))
                XCTAssertEqual(composer.value as? String, "Keep my draft")
                app.terminate()
            }
        }
    }
    func testDrawerButtonsGesturesAndSameConversationKeepDraft() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-sidebar", "--ui-test-pending-context", "--ui-test-creation-locations", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["new-conversation"].exists)
            XCTAssertFalse(app.buttons["账号"].exists)
            XCTAssertFalse(app.buttons["对话选项"].exists)
            app.buttons["open-sidebar"].tap()
            XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["账号"].exists)
            XCTAssertFalse(app.buttons["close-sidebar"].exists)
            XCTAssertEqual(app.buttons.matching(identifier: "open-sidebar").count, 1)
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "sidebar-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.dismissConversationMenu()
            XCTAssertTrue(app.buttons["sidebar-search-toggle"].waitForNonExistence(timeout: 5))
            XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.45))
            start.press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.45)))
            XCTAssertTrue(app.buttons["sidebar-search-toggle"].waitForExistence(timeout: 5))
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.3)).press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.3)))
            XCTAssertTrue(app.buttons["sidebar-search-toggle"].waitForNonExistence(timeout: 5))
            XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
            app.buttons["open-sidebar"].tap()
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "整理会议纪要")).firstMatch.tap()
            let composer = app.descendants(matching: .any)["conversation-composer"]
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            composer.tap(); composer.typeText("Keep this draft")
            app.buttons["open-sidebar"].tap()
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "整理会议纪要")).firstMatch.tap()
            XCTAssertTrue(app.buttons["sidebar-search-toggle"].waitForNonExistence(timeout: 5))
            XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
            XCTAssertEqual(composer.value as? String, "Keep this draft")
            app.terminate()
        }
    }
}
