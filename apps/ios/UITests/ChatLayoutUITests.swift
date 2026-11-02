import XCTest

final class ChatLayoutUITests: XCTestCase {
    private func launch(appearance: String = "light", largeText: Bool = false, language: String = "zh-Hans") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-management", "--ui-test-long-chat-titles", "--ui-test-reset",
                               "-AppleLanguages", "(\(language))", "-appearance", appearance,
                               "-UIPreferredContentSizeCategoryName", largeText ? "UICTContentSizeCategoryAccessibilityXXXL" : "UICTContentSizeCategoryL"]
        app.launch()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 10))
        return app
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
    private func revealSuggestion(_ suggestion: XCUIElement, above input: XCUIElement, in app: XCUIApplication) {
        // ScrollView's accessibility frame includes safe-area overlays. Start the gesture
        // inside the visible welcome region rather than on the fixed composer.
        for _ in 0..<4 where suggestion.frame.maxY > input.frame.minY - 12 {
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let start = origin.withOffset(CGVector(dx: input.frame.midX, dy: input.frame.minY - 24))
            let end = origin.withOffset(CGVector(dx: input.frame.midX, dy: app.navigationBars.firstMatch.frame.maxY + 24))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
    }
    func testWelcomeAndSidebarDensityInBothAppearances() {
        for appearance in ["light", "dark"] {
            let app = launch(appearance: appearance)
            let heading = app.staticTexts["home-chat-heading"]
            XCTAssertTrue(heading.isHittable)
            XCTAssertLessThan(heading.frame.midY, app.frame.height * 0.45)
            let suggestion = app.buttons["解释一个概念"]
            XCTAssertTrue(suggestion.isHittable)
            XCTAssertGreaterThanOrEqual(suggestion.frame.height, 44)
            XCTAssertLessThanOrEqual(suggestion.frame.height, 52)
            XCTAssertLessThan(app.buttons["一起想个点子"].frame.maxY, app.textFields["home-chat-message"].frame.minY)
            capture(app, "compact-home-\(appearance)")
            app.revealConversationMenu()
            let first = app.buttons["mobile-chat-managed-0"]
            let second = app.buttons["mobile-chat-managed-1"]
            XCTAssertTrue(first.waitForExistence(timeout: 5)); XCTAssertTrue(second.isHittable)
            XCTAssertGreaterThanOrEqual(first.frame.height, 44 - 0.5)
            XCTAssertLessThanOrEqual(first.frame.height, 52)
            XCTAssertLessThanOrEqual(second.frame.minY - first.frame.maxY, 8)
            XCTAssertTrue(app.buttons["mobile-chat-managed-7"].isHittable)
            XCTAssertTrue(app.buttons["mobile-sidebar-account"].isHittable)
            capture(app, "compact-sidebar-\(appearance)")
            first.tap()
            XCTAssertTrue(app.textFields["mobile-chat-message"].waitForExistence(timeout: 5))
            app.terminate()
        }
    }
    func testAccessibilityTextCanScrollSuggestionsAndUseSidebar() {
        let app = launch(largeText: true)
        let suggestion = app.buttons["一起想个点子"]
        let input = app.textFields["home-chat-message"]
        revealSuggestion(suggestion, above: input, in: app)
        XCTAssertTrue(suggestion.isHittable)
        XCTAssertLessThan(suggestion.frame.maxY, input.frame.minY)
        XCTAssertTrue(app.buttons["home-chat-send"].isHittable)
        capture(app, "compact-home-accessibility")
        suggestion.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["收起键盘"].isHittable)
        app.buttons["收起键盘"].tap()
        app.revealConversationMenu()
        XCTAssertTrue(app.buttons["scope-mobile"].isHittable)
        XCTAssertTrue(app.buttons["scope-desktop"].isHittable)
        XCTAssertGreaterThanOrEqual(app.buttons["scope-desktop"].frame.minY, app.buttons["scope-mobile"].frame.maxY)
        XCTAssertLessThanOrEqual(app.buttons["sidebar-search-toggle"].frame.maxX, app.frame.maxX)
        XCTAssertGreaterThanOrEqual(app.buttons["sidebar-search-toggle"].frame.width, 44 - 0.5)
        XCTAssertTrue(app.buttons["mobile-sidebar-account"].isHittable)
        XCTAssertTrue(app.buttons["mobile-chat-managed-0"].isHittable)
        capture(app, "compact-sidebar-accessibility")
    }
    func testLandscapeEnglishWelcomeRemainsScrollableAboveComposer() {
        let app = launch(language: "en")
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let rotated = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [rotated], timeout: 5), .completed)
        let input = app.textFields["home-chat-message"]
        XCTAssertTrue(input.isHittable)
        let suggestion = app.buttons["Brainstorm an idea"]
        revealSuggestion(suggestion, above: input, in: app)
        XCTAssertTrue(suggestion.isHittable)
        XCTAssertLessThanOrEqual(suggestion.frame.maxY, input.frame.minY)
        capture(app, "compact-home-landscape-en")
        suggestion.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    }
}
