import XCTest

final class ActivityGroupUITests: XCTestCase {
    func testSearchOpensCollapsedActivityGroup() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-activity", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let group = app.buttons["activity-group-1"]
        XCTAssertTrue(group.waitForExistence(timeout: 10))
        XCTAssertEqual(group.value as? String, "已收起")
        app.buttons["search-conversation"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("notes.txt")
        let result = app.buttons["search-result-2"]
        XCTAssertTrue(result.waitForExistence(timeout: 5)); result.tap()
        app.buttons["open-message-context"].tap()
        XCTAssertTrue(app.otherElements["located-message"].waitForExistence(timeout: 5) || app.staticTexts["located-message"].exists)
        XCTAssertEqual(group.value as? String, "已展开")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "工具执行失败")).firstMatch.exists)
    }
    func testManyUpdatesUseOneCollapsedRowInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-activity", "--ui-test-many-activities", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let earlier = app.buttons["加载更早记录"]
            if earlier.waitForExistence(timeout: 5) { earlier.tap() }
            let group = app.buttons["activity-group-1"]
            XCTAssertTrue(group.waitForExistence(timeout: 10))
            XCTAssertEqual(group.value as? String, "已收起")
            XCTAssertTrue(group.label.contains("60"))
            XCTAssertLessThanOrEqual(group.frame.height, 60)
            XCTAssertFalse(app.staticTexts["上下文更新"].exists)
            let compact = XCTAttachment(screenshot: app.screenshot()); compact.name = "activity-group-collapsed-\(appearance)"; compact.lifetime = .keepAlways; add(compact)
            group.tap()
            XCTAssertEqual(group.value as? String, "已展开")
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "上下文更新")).firstMatch.waitForExistence(timeout: 5))
            group.tap()
            XCTAssertEqual(group.value as? String, "已收起")
            XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "上下文更新")).firstMatch.exists)
            app.terminate()
        }
    }
}
