import XCTest

final class MobileChatHistoryUITests: XCTestCase {
    private func open(_ appearance: String = "light", managed: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
        if managed { app.launchArguments.append("--ui-test-mobile-management") }
        app.launch(); XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 10))
        return app
    }
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, up: Bool = true) {
        for _ in 0..<12 {
            if element.exists && element.isHittable { return }
            if up { app.swipeUp() } else { app.swipeDown() }
        }
        XCTAssertTrue(element.isHittable)
    }
    func testPaginationAndBodySearchFindEarlierChat() {
        continueAfterFailure = false
        let app = open(); app.revealConversationMenu()
        let more = app.buttons["mobile-history-more"]
        reveal(more, in: app); more.tap(); reveal(more, in: app); more.tap()
        let last = app.buttons["mobile-chat-managed-54"]
        reveal(last, in: app); XCTAssertTrue(last.exists)
        app.beginConversationSearch()
        let search = app.textFields["mobile-history-search"]
        let visibleSearch = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in search.exists && search.isHittable }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [visibleSearch], timeout: 5), .completed)
        search.tap(); search.typeText("needle")
        XCTAssertTrue(last.waitForExistence(timeout: 5))
        let typing = XCTAttachment(screenshot: app.screenshot()); typing.name = "phone-history-typing-results"; typing.lifetime = .keepAlways; add(typing)
        let submitSearch = app.keyboards.buttons.matching(NSPredicate(format: "identifier IN %@", ["Search", "搜索"])).firstMatch
        XCTAssertTrue(submitSearch.exists); submitSearch.tap()
        last.swipeDown()
        XCTAssertTrue(last.waitForExistence(timeout: 5))
        let menu = XCTAttachment(screenshot: app.screenshot()); menu.name = "phone-history-search-menu"; menu.lifetime = .keepAlways; add(menu)
        last.tap()
        XCTAssertTrue(app.staticTexts["消息里的搜索词 needle"].waitForExistence(timeout: 5))
        let hit = app.descendants(matching: .any)["mobile-search-hit"]
        XCTAssertTrue(hit.waitForExistence(timeout: 5)); XCTAssertTrue(app.staticTexts["消息里的搜索词 needle"].isHittable)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "phone-history-body-search"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["mobile-chat-more"].tap()
        app.buttons["mobile-return-search"].tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5)); XCTAssertEqual(search.value as? String, "needle")
        XCTAssertTrue(last.isHittable)
    }
    func testRenameArchiveAndRestorePreserveSelectedChatDraft() {
        for appearance in ["light", "dark"] {
            let app = open(appearance); app.revealConversationMenu()
            let row = app.buttons["mobile-chat-managed-0"]
            XCTAssertTrue(row.waitForExistence(timeout: 5)); row.press(forDuration: 1)
            app.buttons["重命名"].tap()
            let title = app.textFields["mobile-history-title"]
            XCTAssertTrue(title.waitForExistence(timeout: 5)); title.tap()
            title.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (title.value as? String)?.count ?? 10) + "New name")
            app.buttons["保存"].tap(); XCTAssertTrue(title.waitForNonExistence(timeout: 5))
            XCTAssertTrue(row.label.contains("New name")); row.press(forDuration: 1); app.buttons["归档"].tap()
            XCTAssertTrue(row.waitForNonExistence(timeout: 5))
            app.buttons["mobile-history-archive-scope"].tap()
            XCTAssertTrue(row.waitForExistence(timeout: 5)); row.tap()
            let composer = app.textFields["mobile-chat-message"]
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); composer.tap(); composer.typeText("Keep archived draft")
            XCTAssertFalse(app.buttons["mobile-chat-send"].isEnabled)
            app.revealConversationMenu(); row.press(forDuration: 1); app.buttons["恢复对话"].tap()
            XCTAssertTrue(row.waitForNonExistence(timeout: 5)); app.dismissConversationMenu()
            XCTAssertEqual(composer.value as? String, "Keep archived draft")
            XCTAssertTrue(app.buttons["mobile-chat-send"].isEnabled)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "phone-history-restored-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate()
        }
    }
    func testOlderServerKeepsTitleSearchAndHidesUnsupportedManagement() {
        let app = open(managed: false)
        let composer = app.textFields["home-chat-message"]; composer.tap(); composer.typeText("Legacy chat")
        app.buttons["home-chat-send"].tap()
        XCTAssertTrue(app.staticTexts["可以直接在手机上聊天。电脑任务仍在电脑上运行。"].waitForExistence(timeout: 10))
        app.beginConversationSearch()
        XCTAssertTrue(app.staticTexts["只搜索最近已加载的聊天标题。"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["mobile-history-archive-scope"].exists)
        let search = app.textFields["mobile-history-search"]; search.tap(); search.typeText("手机")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "mobile-chat-")).firstMatch.waitForExistence(timeout: 5))
    }
}
