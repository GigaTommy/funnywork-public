import XCTest

/// Uses only the newly created Android acceptance chat, normal iOS navigation and real services.
final class LiveMobileHistoryUITests: XCTestCase {
    struct Configuration: Decodable {
        let username: String
        let password: String
        let origin: String
        let chat: String
        let marker: String
        let report: String
    }
    func testAndroidHistoryChangesAreVisibleAndIOSCanRenameArchiveRestore() throws {
        guard let path = ProcessInfo.processInfo.environment["FUNNYWORK_LIVE_MOBILE_HISTORY"] else { throw XCTSkip("Requires authorized isolated live configuration") }
        let config = try JSONDecoder().decode(Configuration.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        guard ["https://funnywork.funnypixelsapp.com", "https://funnywork.funnypixelsapp.com"].contains(config.origin),
              UUID(uuidString: config.chat) != nil, config.marker.hasPrefix("RC3-HISTORY-"),
              config.report.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-history-20261023/"),
              !config.report.split(separator: "/").contains("..") else { return XCTFail("Only this run's new phone chat is allowed") }
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.funnyworker.ios.liveui")
        app.launchArguments = ["--live-ui", "-AppleLanguages", "(zh-Hans)"]
        app.launchEnvironment["FUNNYWORK_LIVE_UI_ORIGIN"] = config.origin
        app.launch()
        defer { app.terminate() }
        if app.buttons["login-submit"].waitForExistence(timeout: 8) {
            app.textFields["login-username"].tap(); app.textFields["login-username"].typeText(config.username)
            app.secureTextFields["login-password"].tap(); app.secureTextFields["login-password"].typeText(config.password)
            app.buttons["login-submit"].tap()
        }
        XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 60))
        app.revealConversationMenu()
        app.buttons["scope-mobile"].tap()
        app.revealConversationMenu()
        let search = app.textFields["mobile-history-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 30)); search.tap(); search.typeText(config.marker)
        if app.buttons["mobile-history-hide-keyboard"].exists { app.buttons["mobile-history-hide-keyboard"].tap() }
        let row = app.buttons["mobile-chat-" + config.chat]
        XCTAssertTrue(row.waitForExistence(timeout: 60)); row.press(forDuration: 1)
        let rename = app.buttons["重命名"].firstMatch
        waitEnabled(rename); rename.tap()
        let title = app.textFields["mobile-history-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20)); title.tap()
        let changed = "iOS history " + config.marker
        replace(title, with: changed, app: app)
        XCTAssertEqual(title.value as? String, changed)
        let save = app.buttons["保存"].firstMatch; waitEnabled(save); save.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 45))
        expectation(for: NSPredicate(format: "label CONTAINS %@", changed), evaluatedWith: row)
        waitForExpectations(timeout: 45)
        if app.buttons["mobile-history-clear"].exists { waitEnabled(app.buttons["mobile-history-clear"]); app.buttons["mobile-history-clear"].tap() }
        waitEnabled(row); row.press(forDuration: 1); let archive = app.buttons["归档"].firstMatch; waitEnabled(archive); archive.tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: row); waitForExpectations(timeout: 45)
        let scope = app.buttons["mobile-history-archive-scope"]; waitEnabled(scope); scope.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 45)); row.press(forDuration: 1)
        let restore = app.buttons["恢复对话"].firstMatch; waitEnabled(restore); restore.tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: row); waitForExpectations(timeout: 45)
        waitEnabled(scope); scope.tap(); XCTAssertTrue(row.waitForExistence(timeout: 45))
        XCTAssertTrue(row.label.contains(changed))
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "cross-client-phone-history"; attachment.lifetime = .keepAlways; add(attachment)
        try JSONSerialization.data(withJSONObject: ["passed": true, "origin": config.origin, "chat": config.chat, "title": changed, "archiveRestored": true, "modelCalls": 0]).write(to: URL(fileURLWithPath: config.report), options: .atomic)
    }
    private func replace(_ field: XCUIElement, with text: String, app: XCUIApplication) {
        field.tap()
        if let old = field.value as? String, !old.isEmpty {
            guard old.utf16.count <= 160 else { return XCTFail("Only bounded acceptance titles are allowed") }
            field.press(forDuration: 1.2)
            let select = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["全选", "Select All"])).firstMatch
            if select.waitForExistence(timeout: 2) { select.tap(); field.typeText(XCUIKeyboardKey.delete.rawValue) }
            else {
                field.tap()
                for _ in 0...old.utf16.count { field.typeKey(XCUIKeyboardKey.rightArrow.rawValue, modifierFlags: []) }
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.utf16.count + 1))
            }
            XCTAssertTrue((field.value as? String) == "" || (field.value as? String) == field.placeholderValue, "All original title text must be cleared")
        }
        field.typeText(text)
    }
    private func waitEnabled(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 45))
        expectation(for: NSPredicate(format: "exists == true AND enabled == true AND hittable == true"), evaluatedWith: element)
        waitForExpectations(timeout: 45)
    }
}
