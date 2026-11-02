import XCTest
final class MobileChatHomeUITests: XCTestCase {
    func testCompactToolbarKeepsTransferInMenuAndNewChatPreservesDraft() {
        let app = XCUIApplication()
        for (language, appearance, size) in [("zh-Hans", "light", "UICTContentSizeCategoryL"), ("en", "light", "UICTContentSizeCategoryL"), ("en", "dark", "UICTContentSizeCategoryAccessibilityXXXL")] {
            app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-management", "--ui-test-mobile-transfer", "--ui-test-long-chat-titles", "--ui-test-reset", "-AppleLanguages", "(\(language))", "-appearance", appearance, "-UIPreferredContentSizeCategoryName", size]
            app.launch(); XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 10))
            app.revealConversationMenu()
            let row = app.buttons["mobile-chat-managed-0"]
            XCTAssertTrue(row.waitForExistence(timeout: 10)); row.tap()
            let more = app.buttons["mobile-chat-more"], new = app.buttons["new-mobile-chat-top"]
            XCTAssertTrue(more.waitForExistence(timeout: 10)); XCTAssertTrue(new.isHittable)
            XCTAssertLessThan(more.frame.width, 65); XCTAssertLessThan(new.frame.width, 65)
            XCTAssertFalse(app.buttons["transfer-result-to-desktop"].exists)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "compact-chat-toolbar-\(language)-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            more.tap(); XCTAssertTrue(app.buttons["transfer-result-to-desktop"].waitForExistence(timeout: 5))
            app.buttons["transfer-result-to-desktop"].tap()
            let done = app.buttons[language == "en" ? "Done" : "完成"]
            XCTAssertTrue(done.waitForExistence(timeout: 5)); done.tap()
            let composer = app.textFields["mobile-chat-message"]
            composer.tap(); composer.typeText("Preserved draft")
            new.tap(); XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["creation-workspace"].exists)
            app.revealConversationMenu(); row.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 5)); XCTAssertEqual(composer.value as? String, "Preserved draft")
            app.terminate()
        }
    }
    func testSettingsExposeKeyboardPreferenceAndRecoveryHelp() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 10))
        app.revealConversationMenu(); app.buttons["账号"].tap()
        XCTAssertTrue(app.switches["keep-chat-keyboard"].waitForExistence(timeout: 5))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "chat-settings"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["使用帮助"].tap()
        XCTAssertTrue(app.staticTexts["操作结果未确认时，先核对原操作的结果，避免重复提交。"].waitForExistence(timeout: 5))
        app.terminate()
    }
    func testKeyboardPreferenceKeepsComposerOpenAfterSending() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-keep-chat-keyboard", "YES", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); let home = app.textFields["home-chat-message"]
        XCTAssertTrue(home.waitForExistence(timeout: 10)); home.tap(); home.typeText("Keep keyboard")
        app.buttons["home-chat-send"].tap()
        XCTAssertTrue(app.textFields["mobile-chat-message"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.terminate()
    }
    func testHomeSuggestionRequiresAnExplicitChoiceBeforeReplacingDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); let home = app.textFields["home-chat-message"]
        XCTAssertTrue(home.waitForExistence(timeout: 10)); home.tap(); home.typeText("Keep this draft")
        app.buttons["收起键盘"].tap(); app.buttons["一起想个点子"].tap()
        XCTAssertTrue(app.buttons["追加到草稿"].waitForExistence(timeout: 5))
        // iOS 26 presents this as a popover and dismisses it by tapping outside.
        app.otherElements["PopoverDismissRegion"].tap()
        XCTAssertTrue(app.buttons["追加到草稿"].waitForNonExistence(timeout: 5))
        XCTAssertEqual(home.value as? String, "Keep this draft")
        app.buttons["一起想个点子"].tap(); app.buttons["追加到草稿"].tap()
        XCTAssertTrue((home.value as? String)?.hasPrefix("Keep this draft\n") == true)
    }
    func testRunningComposerKeepsNextDraftAndSupportsExpandedEditing() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-wait", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); let home = app.textFields["home-chat-message"]
        XCTAssertTrue(home.waitForExistence(timeout: 10)); home.tap(); home.typeText("Original message")
        app.buttons["home-chat-send"].tap()
        let composer = app.textFields["mobile-chat-message"]
        XCTAssertTrue(composer.waitForExistence(timeout: 10)); composer.tap(); composer.typeText("Next draft")
        XCTAssertTrue(app.staticTexts["这条草稿会保留，回复结束后可发送。"].exists)
        app.buttons["mobile-chat-message-expand"].tap()
        XCTAssertTrue(app.textViews["expanded-composer-text"].waitForExistence(timeout: 5))
        app.buttons["close-expanded-composer"].tap()
        app.buttons["mobile-chat-stop"].tap()
        XCTAssertTrue(app.buttons["mobile-chat-stop"].waitForNonExistence(timeout: 5))
        XCTAssertEqual(composer.value as? String, "Next draft")
    }
    func testPhoneMessageActionsKeepUsageInsideMoreMenu() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); let home = app.textFields["home-chat-message"]
            XCTAssertTrue(home.waitForExistence(timeout: 10)); home.tap(); home.typeText("Phone actions")
            app.buttons["home-chat-send"].tap()
            XCTAssertTrue(app.buttons["select-message-text"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.staticTexts["本轮用量"].exists)
            app.buttons["copy-message"].tap()
            XCTAssertTrue(app.buttons["copy-message"].label.contains("已复制"))
            app.buttons["select-message-text"].tap()
            XCTAssertTrue(app.textViews["selectable-message-text"].waitForExistence(timeout: 5))
            app.buttons["完成"].tap()
            app.buttons["message-more"].tap(); app.buttons["本轮用量"].tap()
            XCTAssertTrue(app.staticTexts["usage-actual-model"].waitForExistence(timeout: 5))
            app.buttons["usage-details-close"].tap()
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "phone-message-actions-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.terminate()
        }
    }
    func testLongPhoneChatPreservesOldReadingPositionDuringStreamingAndRelaunch() {
        let app = XCUIApplication()
        let arguments = ["--ui-test-mobile-chat", "--ui-test-mobile-history", "--ui-test-mobile-slow-reply", "-AppleLanguages", "(zh-Hans)"]
        app.launchArguments = arguments + ["--ui-test-reset"]
        app.launch(); let composer = app.textFields["mobile-chat-message"]
        XCTAssertTrue(composer.waitForExistence(timeout: 10))
        composer.tap(); composer.typeText("Next reply"); app.buttons["mobile-chat-send"].tap()
        let transcript = app.scrollViews["mobile-chat-transcript"]
        transcript.swipeDown(); transcript.swipeDown()
        XCTAssertTrue(app.buttons["mobile-scroll-to-latest"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["mobile-chat-stop"].waitForNonExistence(timeout: 15))
        XCTAssertTrue(app.buttons["mobile-scroll-to-latest"].exists)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "phone-old-reading-after-stream"; shot.lifetime = .keepAlways; add(shot)
        app.terminate(); app.launchArguments = arguments; app.launch()
        XCTAssertTrue(app.buttons["mobile-scroll-to-latest"].waitForExistence(timeout: 10))
        app.buttons["mobile-scroll-to-latest"].tap()
        XCTAssertTrue(app.buttons["mobile-scroll-to-latest"].waitForNonExistence(timeout: 5))
    }
    func testLongModelNameStaysInSelectionMenuInsteadOfChatCanvas() {
        let app=XCUIApplication()
        app.launchArguments=["--ui-test-mobile-chat","--ui-test-long-model","--ui-test-reset","-AppleLanguages","(zh-Hans)"]
        app.launch()
        let input=app.textFields["home-chat-message"]
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        let picker=app.buttons["home-chat-model"]
        XCTAssertTrue(picker.isHittable)
        XCTAssertTrue((picker.value as? String)?.contains("长上下文") == true)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "长上下文")).firstMatch.exists)
        picker.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "长上下文")).firstMatch.waitForExistence(timeout: 5))
        app.buttons["DeepSeek"].tap()
        XCTAssertTrue((picker.value as? String)?.contains("DeepSeek") == true)
        let shot=XCTAttachment(screenshot:app.screenshot());shot.name="compact-long-model";shot.lifetime = .keepAlways;add(shot)
    }
    func testSourceSwitcherRemainsReachableInDarkAppearanceWithLargeText() {
        let app=XCUIApplication()
        app.launchArguments=["--ui-test-mobile-chat","--ui-test-reset","-AppleLanguages","(zh-Hans)","-appearance","dark","-UIPreferredContentSizeCategoryName","UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch();XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout:10))
        app.revealConversationMenu()
        XCTAssertEqual(app.buttons.matching(identifier: "scope-mobile").count, 1)
        XCTAssertEqual(app.buttons.matching(identifier: "scope-desktop").count, 1)
        XCTAssertTrue(app.buttons["scope-mobile"].isHittable);XCTAssertTrue(app.buttons["scope-desktop"].isHittable)
        app.buttons["scope-desktop"].tap()
        XCTAssertTrue(app.buttons["scope-mobile"].waitForExistence(timeout:5))
        XCTAssertTrue(app.buttons["scope-mobile"].isHittable)
        let shot=XCTAttachment(screenshot:app.screenshot());shot.name="source-switch-dark-large-text";shot.lifetime = .keepAlways;add(shot)
        app.buttons["scope-mobile"].tap()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout:5))
    }
    func testSourceSwitchRetainsBothDraftsWithoutExtraReturnHeader() {
        let app=XCUIApplication()
        app.launchArguments=["--ui-test-mobile-chat","--ui-test-pending-context","--ui-test-reset","-AppleLanguages","(zh-Hans)"]
        app.launch()
        let home=app.textFields["home-chat-message"]
        XCTAssertTrue(home.waitForExistence(timeout:10));home.tap();home.typeText("Mobile draft")
        app.revealConversationMenu();app.buttons["scope-desktop"].tap()
        XCTAssertTrue(app.buttons["scope-mobile"].waitForExistence(timeout:5))
        XCTAssertFalse(app.buttons["返回聊天"].exists)
        app.buttons.matching(NSPredicate(format:"label BEGINSWITH %@","整理会议纪要")).firstMatch.tap()
        let desktop=app.descendants(matching:.any)["conversation-composer"]
        XCTAssertTrue(desktop.waitForExistence(timeout:10));desktop.tap();desktop.typeText("Desktop draft")
        app.revealConversationMenu();app.buttons["scope-mobile"].tap()
        XCTAssertTrue(home.waitForExistence(timeout:5));XCTAssertEqual(home.value as? String,"Mobile draft")
        app.revealConversationMenu();app.buttons["scope-desktop"].tap()
        app.buttons.matching(NSPredicate(format:"label BEGINSWITH %@","整理会议纪要")).firstMatch.tap()
        XCTAssertTrue(desktop.waitForExistence(timeout:5));XCTAssertEqual(desktop.value as? String,"Desktop draft")
        let shot=XCTAttachment(screenshot:app.screenshot());shot.name="unified-source-task";shot.lifetime = .keepAlways;add(shot)
        app.revealConversationMenu()
        XCTAssertTrue(app.buttons["sidebar-search-toggle"].isHittable)
        XCTAssertGreaterThanOrEqual(app.buttons["sidebar-search-toggle"].frame.minX,0)
        let menu=XCTAttachment(screenshot:app.screenshot());menu.name="unified-source-sidebar";menu.lifetime = .keepAlways;add(menu)
    }
    func testPhoneReplyFinishesAcrossDesktopSourceSwitch() {
        let app=XCUIApplication()
        app.launchArguments=["--ui-test-mobile-chat","--ui-test-mobile-slow-reply","--ui-test-reset","-AppleLanguages","(zh-Hans)"]
        app.launch();let home=app.textFields["home-chat-message"]
        XCTAssertTrue(home.waitForExistence(timeout:10));home.tap();home.typeText("Reply while switching")
        app.buttons["home-chat-send"].tap()
        XCTAssertTrue(app.buttons["mobile-chat-stop"].waitForExistence(timeout:5))
        app.revealConversationMenu();app.buttons["scope-desktop"].tap()
        XCTAssertTrue(app.buttons["scope-mobile"].waitForExistence(timeout:5))
        app.buttons["scope-mobile"].tap()
        XCTAssertTrue(app.staticTexts["可以直接在手机上聊天。电脑任务仍在电脑上运行。"].waitForExistence(timeout:15))
        XCTAssertTrue(app.buttons["mobile-chat-stop"].waitForNonExistence(timeout:5))
        XCTAssertFalse(app.buttons["mobile-chat-reconcile"].exists)
    }
    func testRunningChatCanBrowseHomeAndReturnWithoutLosingDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-wait", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        let input = app.textFields["home-chat-message"]
        XCTAssertTrue(input.waitForExistence(timeout: 10)); input.tap(); input.typeText("Running task")
        app.buttons["home-chat-send"].tap()
        XCTAssertTrue(app.buttons["mobile-chat-stop"].waitForExistence(timeout: 10))
        app.buttons["new-mobile-chat-top"].tap()
        XCTAssertTrue(app.buttons["open-running-chat"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["mobile-chat-stop"].exists)
        let home = app.textFields["home-chat-message"]
        home.tap(); home.typeText("Next draft")
        app.buttons["open-running-chat"].tap()
        XCTAssertTrue(app.buttons["mobile-chat-stop"].waitForExistence(timeout: 5))
        app.buttons["new-mobile-chat-top"].tap()
        XCTAssertEqual(home.value as? String, "Next draft")
    }
    func testNetworkProblemUsesTopStatusAndKeepsComposerVisible() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-network", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.otherElements["home-chat-error"].exists)
        XCTAssertTrue(app.buttons["home-connection-status"].waitForExistence(timeout: 6))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "home-connection-status"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["home-connection-status"].tap()
        XCTAssertTrue(app.staticTexts["暂时未连接"].waitForExistence(timeout: 5))
        app.buttons["重试"].tap()
        XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 5))
    }
    func testHomeCanSendChatWithoutSelectingComputerOrWorkspace() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            let input = app.textFields["home-chat-message"]
            XCTAssertTrue(input.waitForExistence(timeout: 10)); XCTAssertTrue(app.staticTexts["今天想聊点什么？"].exists)
            XCTAssertFalse(app.buttons["home-chat-send"].isEnabled)
            input.tap(); input.typeText("Hello from phone")
            XCTAssertTrue(app.buttons["home-chat-send"].isEnabled)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "chat-home-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["home-chat-send"].tap()
            XCTAssertTrue(app.staticTexts["可以直接在手机上聊天。电脑任务仍在电脑上运行。"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["mobile-chat-model"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.staticTexts["缓存命中 30.0%"].exists)
            app.buttons["mobile-chat-usage-menu"].tap()
            XCTAssertTrue(app.staticTexts["usage-actual-model"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["企业千问 · qwen"].exists)
            XCTAssertTrue(app.staticTexts["输入总量（含缓存）"].exists)
            app.swipeUp()
            XCTAssertTrue(app.staticTexts["30.0%"].waitForExistence(timeout: 5))
            app.swipeUp()
            if !app.staticTexts["平均输出速度"].exists { app.swipeUp() }
            XCTAssertTrue(app.staticTexts["平均输出速度"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["12.5 tok/s"].exists)
            let details = XCTAttachment(screenshot: app.screenshot()); details.name = "chat-usage-\(appearance)"; details.lifetime = .keepAlways; add(details)
            app.buttons["usage-details-close"].tap()
            XCTAssertFalse(app.staticTexts["累计用量：15 tokens"].exists)
            let footer = XCTAttachment(screenshot: app.screenshot()); footer.name = "chat-footer-\(appearance)"; footer.lifetime = .keepAlways; add(footer)
            app.buttons["mobile-chat-model"].tap()
            app.buttons["DeepSeek"].tap()
            XCTAssertTrue((app.buttons["mobile-chat-model"].value as? String)?.contains("DeepSeek") == true)
            app.buttons["mobile-chat-usage-menu"].tap()
            XCTAssertTrue(app.staticTexts["企业千问 · qwen"].waitForExistence(timeout: 5))
            app.buttons["usage-details-close"].tap()
            XCTAssertFalse(app.buttons["creation-workspace"].exists)
            app.buttons["new-mobile-chat-top"].tap()
            XCTAssertTrue(app.textFields["home-chat-message"].waitForExistence(timeout: 5))
            app.terminate()
        }
    }
    func testWaitingAndComputerMonitoringRemainSeparate() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-mobile-wait", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); let input = app.textFields["home-chat-message"]; XCTAssertTrue(input.waitForExistence(timeout: 10))
        input.tap(); input.typeText("Queued chat"); app.buttons["home-chat-send"].tap()
        XCTAssertTrue(app.staticTexts["正在等待模型容量…"].waitForExistence(timeout: 5))
        app.buttons["mobile-chat-stop"].tap(); XCTAssertTrue(app.textFields["mobile-chat-message"].waitForExistence(timeout: 5))
        app.revealConversationMenu(); app.buttons["scope-desktop"].tap()
        XCTAssertTrue(app.buttons["scope-mobile"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["返回聊天"].exists)
        app.buttons["scope-mobile"].tap(); XCTAssertTrue(app.textFields["mobile-chat-message"].waitForExistence(timeout: 5))
    }
    func testLargeTextKeepsKeyboardAndMetricsOperable() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
        app.launchArguments = ["--ui-test-mobile-chat", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance, "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch(); let home = app.textFields["home-chat-message"]
        XCTAssertTrue(home.waitForExistence(timeout: 10)); home.tap(); home.typeText("Large text chat")
        XCTAssertTrue(app.buttons["home-chat-send"].isHittable); app.buttons["home-chat-send"].tap()
        let composer = app.textFields["mobile-chat-message"]
        XCTAssertTrue(composer.waitForExistence(timeout: 10)); composer.tap(); composer.typeText("Next message")
        XCTAssertTrue(app.buttons["mobile-chat-send"].isHittable)
        XCTAssertTrue(app.buttons["mobile-chat-usage-menu"].isHittable)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "chat-metrics-large-text-keyboard-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["mobile-chat-usage-menu"].tap()
        XCTAssertTrue(app.buttons["usage-details-close"].waitForExistence(timeout: 5)); app.buttons["usage-details-close"].tap()
        XCTAssertTrue(composer.exists)
        app.terminate()
        }
    }

}
