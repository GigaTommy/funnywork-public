import XCTest

final class ConversationUITests: XCTestCase {
    func testStoppingLiveReplyKeepsPartialTextAndInterruptionOffline() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-live-reply", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let preview = app.descendants(matching: .any)["live-assistant-reply"].firstMatch
            XCTAssertTrue(preview.waitForExistence(timeout: 5))
            app.buttons["stop-generation"].tap()
            XCTAssertTrue(app.staticTexts["回复已中断"].waitForExistence(timeout: 5))
            XCTAssertFalse(preview.exists)
            XCTAssertEqual(app.staticTexts.matching(identifier: "先检查项目结构。").count, 1)
            XCTAssertFalse(app.staticTexts["先检查项目结构。然后核对测试结果。"].exists)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "interrupted-reply-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            app.buttons["search-conversation"].tap()
            let search = app.searchFields.firstMatch
            XCTAssertTrue(search.waitForExistence(timeout: 5))
            search.tap(); search.typeText("先检查项目")
            XCTAssertTrue(app.buttons["search-result-2"].waitForExistence(timeout: 5))
            app.buttons["search-result-2"].tap()
            XCTAssertTrue(app.staticTexts["回复已中断"].exists)
            app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
            app.launchArguments.append("--ui-test-offline")
            app.launch(); app.buttons["示例对话"].tap()
            XCTAssertTrue(app.staticTexts["回复已中断"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["先检查项目结构。"].exists)
            XCTAssertFalse(preview.exists)
        }
    }
    func testLiveReplyGrowsAndIsReplacedByOneFinalMessage() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-live-reply", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let preview = app.descendants(matching: .any)["live-assistant-reply"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["先检查项目结构。"].exists)
        app.buttons["更新回复样例"].tap()
        let fullText = app.staticTexts["先检查项目结构。然后核对测试结果。"]
        XCTAssertTrue(fullText.waitForExistence(timeout: 5))
        XCTAssertTrue(preview.exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "live-assistant-reply"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.buttons["更新回复样例"].tap()
        XCTAssertTrue(preview.waitForNonExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts.matching(identifier: "先检查项目结构。然后核对测试结果。").count, 1)
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-offline")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(fullText.waitForExistence(timeout: 5))
        XCTAssertFalse(preview.exists)
    }
    func testClearDownloadsRequiresConfirmationAndUpdatesUsage() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-storage", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.revealConversationMenu(); app.buttons["账号"].tap()
        let clear = app.buttons["clear-downloads"]
        XCTAssertTrue(clear.waitForExistence(timeout: 5)); XCTAssertTrue(clear.isEnabled)
        clear.tap(); app.buttons["取消"].tap(); XCTAssertTrue(clear.isEnabled)
        clear.tap(); app.buttons["清除"].tap()
        XCTAssertFalse(clear.isEnabled)
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "storage-settings"; image.lifetime = .keepAlways; add(image)
        app.terminate(); app.launchArguments.removeAll { ["--ui-test-reset", "--ui-test-storage"].contains($0) }
        app.launch(); app.revealConversationMenu(); app.buttons["账号"].tap()
        XCTAssertTrue(clear.waitForExistence(timeout: 5)); XCTAssertFalse(clear.isEnabled)
    }
    func testSearchAttachmentNameAndOpenPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-message-file", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        app.buttons["search-conversation"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("说明")
        let result = app.buttons["search-result-0"]
        XCTAssertTrue(result.waitForExistence(timeout: 5)); XCTAssertTrue(result.label.contains("说明.txt")); result.tap()
        let file = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "search-attachment-")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 5)); file.tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 8))
    }
    func testActivityDetailsStayCollapsedAndAreSearchable() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-activity", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let group = app.buttons["activity-group-1"]
        XCTAssertTrue(group.waitForExistence(timeout: 5))
        XCTAssertEqual(group.value as? String, "已收起")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "工具调用")).firstMatch.exists)
        group.tap()
        let call = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "工具调用")).firstMatch
        XCTAssertTrue(call.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["notes.txt 不存在"].exists)
        call.tap()
        XCTAssertTrue(app.staticTexts["{\"command\":\"cat notes.txt\"}"].exists)
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "工具执行失败")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["notes.txt 不存在"].exists)
        XCTAssertTrue(app.buttons["copy-activity-2"].exists)
        XCTAssertFalse(app.staticTexts["工作区信息已更新"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "activity-details"; screenshot.lifetime = .keepAlways; add(screenshot)
        group.tap()
        XCTAssertEqual(group.value as? String, "已收起")
        XCTAssertFalse(app.buttons["copy-activity-2"].exists)
        app.buttons["search-conversation"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("notes.txt")
        XCTAssertTrue(app.buttons["search-result-1"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["search-result-2"].exists)
    }
    func testExecutionAndQueueFollowConfirmedState() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-queued", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let status = app.descendants(matching: .any)["execution-status"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["检查上传结果"].exists)
        XCTAssertTrue(app.staticTexts["已排队"].exists)
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "execution-and-queue"; image.lifetime = .keepAlways; add(image)
        app.buttons["完成测试任务"].tap()
        XCTAssertTrue(status.waitForNonExistence(timeout: 12))
        XCTAssertFalse(app.staticTexts["已排队"].exists)
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-offline")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.staticTexts["未连接"].waitForExistence(timeout: 5))
        XCTAssertFalse(status.exists); XCTAssertFalse(app.staticTexts["已排队"].exists)
    }
    func testMessageImageShowsThumbnailAndOpensPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-message-image", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let image = app.images.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-image-")).firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "inline-message-image"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-attachment-0-")).firstMatch.tap()
        XCTAssertTrue(app.images["gallery-image"].waitForExistence(timeout: 8))
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-offline")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(image.waitForExistence(timeout: 8))
    }
    func testMessageAttachmentOpensPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-message-file", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let file = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-attachment-0-")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 5))
        XCTAssertTrue(file.label.contains("说明.txt"))
        let card = XCTAttachment(screenshot: app.screenshot()); card.name = "message-attachment-card"; card.lifetime = .keepAlways; add(card)
        file.tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 8))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "message-attachment-preview"; image.lifetime = .keepAlways; add(image)
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-offline")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(file.waitForExistence(timeout: 5)); file.tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 8))
    }
    func testCapturedPhotoBecomesDraftWithoutSendingAndCancelKeepsDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-upload", "--ui-test-camera", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["conversation-files"].waitForExistence(timeout: 5))
        app.buttons["conversation-files"].tap(); app.buttons["拍照"].tap()
        XCTAssertTrue(app.staticTexts["已就绪"].waitForExistence(timeout: 5))
        let photo = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "photo-", ".jpg")).firstMatch
        XCTAssertTrue(photo.exists); let name = photo.label
        XCTAssertTrue(app.buttons["send-message"].isEnabled)
        XCTAssertFalse(app.staticTexts["最近操作"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "captured-photo-draft"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-camera-cancel")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["继续上传"].waitForExistence(timeout: 5))
        app.buttons["继续上传"].tap()
        XCTAssertTrue(app.staticTexts["已就绪"].waitForExistence(timeout: 5))
        app.buttons["conversation-files"].tap(); app.buttons["拍照"].tap()
        XCTAssertTrue(app.staticTexts[name].exists)
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "photo-", ".jpg")).count, 1)
        XCTAssertFalse(app.staticTexts["最近操作"].exists)
    }
    func testDraftAttachmentPreviewWorksBeforeSendingAndOffline() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-upload", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["conversation-files"].waitForExistence(timeout: 5))
        app.buttons["conversation-files"].tap(); app.buttons["添加文件"].tap()
        XCTAssertTrue(app.staticTexts["已就绪"].waitForExistence(timeout: 5))
        app.buttons["预览 项目说明.txt"].tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 8))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "draft-file-preview"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.buttons.matching(NSPredicate(format: "label IN %@", ["完成", "Done", "关闭", "Close"])).firstMatch.tap()
        XCTAssertTrue(app.buttons["预览 项目说明.txt"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["最近操作"].exists)
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-offline")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["预览 项目说明.txt"].waitForExistence(timeout: 5))
        app.buttons["预览 项目说明.txt"].tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 8))
    }
    func testOutgoingAttachmentSurvivesRelaunchAndSendsWithoutText() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-upload", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["conversation-files"].waitForExistence(timeout: 5))
        app.buttons["conversation-files"].tap(); app.buttons["添加文件"].tap()
        XCTAssertTrue(app.staticTexts["已就绪"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["项目说明.txt"].exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "outgoing-attachment"; shot.lifetime = .keepAlways; add(shot)
        app.terminate(); app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["继续上传"].waitForExistence(timeout: 5))
        app.buttons["继续上传"].tap()
        XCTAssertTrue(app.staticTexts["已就绪"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["send-message"].isEnabled)
        app.buttons["send-message"].tap()
        XCTAssertTrue(app.staticTexts["最近操作"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["项目说明.txt"].exists)
    }
    func testDiffDisplaysChangesAndCopiesOriginalText() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-diff", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let diff = app.scrollViews["message-diff"]
        XCTAssertTrue(diff.waitForExistence(timeout: 5))
        app.swipeDown()
        XCTAssertTrue(app.descendants(matching: .any)["新增: +let retries = 3"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["删除: -let retries = 1"].exists)
        app.buttons["copy-code"].tap()
        XCTAssertTrue(app.buttons["copy-code"].label.contains("已复制"))
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "code-diff"; image.lifetime = .keepAlways; add(image)
    }
    func testLongConversationJumpAndIncomingMessagePreserveReadingPosition() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-long-conversation", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let latest = app.staticTexts["消息 74：这是一段用于检查长对话阅读位置的内容。"]
        XCTAssertTrue(latest.waitForExistence(timeout: 5))
        XCTAssertTrue(latest.isHittable)
        app.swipeDown(); app.swipeDown()
        let jump = app.buttons["scroll-to-latest"]
        XCTAssertTrue(jump.waitForExistence(timeout: 5))
        XCTAssertEqual(jump.label, "回到最新")
        app.buttons["追加测试消息"].tap()
        let banner = app.buttons.matching(identifier: "scroll-to-latest").matching(NSPredicate(format: "label == %@", "有新消息")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 10))
        let incoming = app.staticTexts["消息 75：这是一段用于检查长对话阅读位置的内容。"]
        XCTAssertFalse(incoming.isHittable)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "incoming-message-shortcut"; shot.lifetime = .keepAlways; add(shot)
        jump.tap()
        XCTAssertTrue(incoming.waitForExistence(timeout: 5))
        XCTAssertTrue(incoming.isHittable)
        XCTAssertFalse(jump.exists)
        app.buttons["追加测试消息"].tap()
        let next = app.staticTexts["消息 76：这是一段用于检查长对话阅读位置的内容。"]
        XCTAssertTrue(next.waitForExistence(timeout: 10))
        XCTAssertTrue(next.isHittable)
        XCTAssertFalse(jump.exists)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["示例对话"].tap()
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        XCTAssertTrue(next.isHittable)
        XCTAssertFalse(jump.exists)
    }
    func testReadingPositionRestoresOnReturnAndOfflineRelaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-long-conversation", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let latest = app.staticTexts["消息 74：这是一段用于检查长对话阅读位置的内容。"]
        XCTAssertTrue(latest.waitForExistence(timeout: 5))
        app.swipeDown(); app.swipeDown()
        XCTAssertTrue(app.buttons["scroll-to-latest"].waitForExistence(timeout: 5))
        let visible = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "消息 ")).allElementsBoundByIndex.first { $0.isHittable }!
        let text = visible.label
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["scroll-to-latest"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[text].isHittable)
        XCTAssertFalse(latest.isHittable)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launchArguments.append("--ui-test-offline")
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["scroll-to-latest"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[text].isHittable)
        app.buttons["scroll-to-latest"].tap()
        XCTAssertTrue(latest.isHittable)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["示例对话"].tap()
        XCTAssertTrue(latest.waitForExistence(timeout: 5))
        XCTAssertTrue(latest.isHittable)
        XCTAssertFalse(app.buttons["scroll-to-latest"].exists)
    }
    func testVoiceInputRequiresReviewAndAppendsToDraft() {
        let app = open()
        let draft = app.descendants(matching: .any)["conversation-composer"]
        draft.tap(); draft.typeText("Existing draft")
        app.buttons["语音输入"].tap()
        app.buttons["开始录音"].tap()
        XCTAssertTrue(app.buttons["结束录音"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["使用文字"].isEnabled)
        app.buttons["结束录音"].tap()
        XCTAssertTrue(app.buttons["使用文字"].isEnabled)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "voice-input-review"; image.lifetime = .keepAlways; add(image)
        app.buttons["使用文字"].tap()
        XCTAssertEqual(draft.value as? String, "Existing draft\n语音草稿")
        XCTAssertFalse(app.staticTexts["最近操作"].exists)
        app.buttons["语音输入"].tap(); app.buttons["开始录音"].tap()
        app.buttons["取消"].tap()
        XCTAssertEqual(draft.value as? String, "Existing draft\n语音草稿")
    }
    func testSearchOpensMatchingMessage() {
        let app = open()
        app.buttons["search-conversation"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap(); search.typeText("草稿")
        let result = app.buttons["search-result-1"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        result.tap()
        XCTAssertTrue(app.staticTexts["已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["复制"].exists)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "message-search"; image.lifetime = .keepAlways; add(image)
    }
    func testReadingControlsAndLeavingConversation() {
        let app = open()
        let read = app.buttons["read-message"].firstMatch
        XCTAssertTrue(read.waitForExistence(timeout: 5))
        if !read.isHittable { app.swipeDown() }
        read.tap()
        XCTAssertTrue(app.buttons["暂停朗读"].waitForExistence(timeout: 5))
        app.buttons["暂停朗读"].tap()
        XCTAssertTrue(app.buttons["继续朗读"].exists)
        app.buttons["继续朗读"].tap()
        XCTAssertTrue(app.buttons["暂停朗读"].exists)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "message-reading"; image.lifetime = .keepAlways; add(image)
        app.buttons["停止朗读"].tap()
        XCTAssertFalse(app.buttons["暂停朗读"].exists)
        read.tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["示例对话"].tap()
        XCTAssertTrue(read.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["停止朗读"].exists)
    }
    func testMessageTableScrollsToTrailingColumns() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-table", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let table = app.scrollViews["message-table"]
        XCTAssertTrue(table.waitForExistence(timeout: 10))
        app.swipeDown()
        XCTAssertTrue(app.staticTexts["功能: 对话同步"].exists)
        table.swipeLeft()
        XCTAssertTrue(app.staticTexts["备注: 支持离线阅读"].isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "message-table"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testNestedMarkdownKeepsCodeTableAndQuotesInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-nested-markdown", "--ui-test-reset",
                                   "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            XCTAssertTrue(app.staticTexts["下一步"].waitForExistence(timeout: 10))
            app.swipeDown()
            XCTAssertTrue(app.staticTexts["已完成第一轮检查。"].exists)
            XCTAssertTrue(app.staticTexts["保留原始记录"].exists)
            let copy = app.buttons["copy-code"]
            XCTAssertTrue(copy.isHittable)
            copy.tap()
            XCTAssertTrue(copy.label.contains("已复制"))
            app.scrollViews["message-table"].swipeLeft()
            XCTAssertTrue(app.staticTexts["备注: 支持离线阅读"].isHittable)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "nested-markdown-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            app.terminate()
        }
    }
    func testNestedTaskListReadingInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-lists", "--ui-test-reset",
                                   "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            XCTAssertTrue(app.staticTexts["下一步"].waitForExistence(timeout: 10))
            app.swipeDown()
            XCTAssertTrue(app.staticTexts["准备"].exists)
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "阅读说明")).firstMatch.exists)
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "确认后再继续")).firstMatch.exists)
            XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "阅读说明")).firstMatch.exists)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "nested-task-list-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            app.terminate()
        }
    }
    func testMessageWebLinkOpensInAppAndReturnsToDraft() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-links", "--ui-test-reset",
                                   "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let composer = app.descendants(matching: .any)["conversation-composer"]
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            composer.tap(); composer.typeText("Keep this draft")
            app.swipeDown()
            let link = app.links["示例网站"]
            XCTAssertTrue(link.waitForExistence(timeout: 5))
            link.tap()
            let close = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "Close", "完成", "Done"])).firstMatch
            XCTAssertTrue(close.waitForExistence(timeout: 10))
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "message-browser-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            close.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 5))
            XCTAssertEqual(composer.value as? String, "Keep this draft")
            XCTAssertTrue(link.exists)
            app.terminate()
        }
    }
    func testAppearancePersistsAcrossRelaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        app.revealConversationMenu(); app.buttons["账号"].tap()
        XCTAssertTrue(app.buttons["appearance-setting"].waitForExistence(timeout: 5))
        app.buttons["appearance-setting"].tap()
        app.buttons["深色"].tap()
        if !app.buttons["appearance-setting"].exists { app.navigationBars.buttons.element(boundBy: 0).tap() }
        let dark = XCTAttachment(screenshot: app.screenshot())
        dark.name = "settings-dark"; dark.lifetime = .keepAlways; add(dark)
        app.terminate(); app.launch(); app.revealConversationMenu(); app.buttons["账号"].tap()
        XCTAssertTrue(app.buttons["appearance-setting"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["appearance-setting"].value as? String, "深色")
        XCTAssertTrue(app.descendants(matching: .any)["app-version"].exists)
        app.buttons["退出登录"].tap()
        app.buttons["取消"].tap()
        XCTAssertTrue(app.buttons["appearance-setting"].exists)
        app.buttons["appearance-setting"].tap()
        app.buttons["浅色"].tap()
        if !app.buttons["appearance-setting"].exists { app.navigationBars.buttons.element(boundBy: 0).tap() }
        let light = XCTAttachment(screenshot: app.screenshot())
        light.name = "settings-light"; light.lifetime = .keepAlways; add(light)
        app.buttons["appearance-setting"].tap()
        app.buttons["跟随系统"].tap()
    }
    func testArchiveAndRestoreConversation() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            XCTAssertTrue(app.staticTexts["整理会议纪要"].waitForExistence(timeout: 5))
            app.staticTexts["整理会议纪要"].press(forDuration: 1)
            app.buttons["归档"].tap()
            XCTAssertTrue(app.staticTexts["整理会议纪要"].waitForNonExistence(timeout: 5))
            app.revealConversationMenu(); app.buttons["已归档"].tap()
            XCTAssertTrue(app.staticTexts["整理会议纪要"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.staticTexts["修改登录页"].exists)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "archived-list-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            app.staticTexts["整理会议纪要"].press(forDuration: 1)
            app.buttons["取消归档"].tap()
            XCTAssertTrue(app.staticTexts["没有已归档的对话"].waitForExistence(timeout: 5))
            app.revealConversationMenu(); app.buttons["返回对话"].tap()
            XCTAssertTrue(app.staticTexts["整理会议纪要"].waitForExistence(timeout: 5))
            app.terminate()
        }
    }
    func testRenameConversationUpdatesListAndDestination() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        XCTAssertTrue(app.staticTexts["整理会议纪要"].waitForExistence(timeout: 5))
        app.staticTexts["整理会议纪要"].press(forDuration: 1)
        app.buttons["重命名"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        let old = field.value as? String ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + "项目复盘")
        let editor = XCTAttachment(screenshot: app.screenshot())
        editor.name = "rename-editor"; editor.lifetime = .keepAlways; add(editor)
        app.alerts.buttons["保存"].tap()
        XCTAssertTrue(app.staticTexts["项目复盘"].waitForExistence(timeout: 5))
        let list = XCTAttachment(screenshot: app.screenshot())
        list.name = "renamed-list"; list.lifetime = .keepAlways; add(list)
        app.staticTexts["项目复盘"].tap()
        XCTAssertEqual(app.staticTexts["selected-conversation"].label, "项目复盘")
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "renamed-conversation"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testPinPersistsAndCanBeRemoved() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.staticTexts["整理会议纪要"].waitForExistence(timeout: 5))
        app.staticTexts["整理会议纪要"].press(forDuration: 1)
        app.buttons["置顶"].tap()
        XCTAssertTrue(app.staticTexts["已置顶"].firstMatch.waitForExistence(timeout: 5))
        app.terminate()
        app.launchArguments.removeAll { $0 == "--ui-test-reset" }
        app.launch()
        XCTAssertTrue(app.staticTexts["已置顶"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertLessThan(app.staticTexts["整理会议纪要"].frame.minY, app.staticTexts["修改登录页"].frame.minY)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "pinned-conversation"; shot.lifetime = .keepAlways; add(shot)
        app.staticTexts["整理会议纪要"].press(forDuration: 1)
        app.buttons["取消置顶"].tap()
        XCTAssertFalse(app.staticTexts["已置顶"].firstMatch.exists)
        XCTAssertLessThan(app.staticTexts["修改登录页"].frame.minY, app.staticTexts["整理会议纪要"].frame.minY)
    }
    func testDirectorySelectionAndReturn() {
        if UIDevice.current.userInterfaceIdiom == .pad { XCUIDevice.shared.orientation = .landscapeLeft }
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 10))
        app.staticTexts["修改登录页"].tap()
        XCTAssertTrue(app.staticTexts["selected-conversation"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["selected-conversation"].label, "修改登录页")
        if UIDevice.current.userInterfaceIdiom == .phone {
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        app.staticTexts["整理会议纪要"].tap()
        XCTAssertEqual(app.staticTexts["selected-conversation"].label, "整理会议纪要")
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "conversation-navigation"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testComposerKeepsActionsVisibleWithKeyboard() {
        let app = open()
        let send = app.buttons["send-message"]
        XCTAssertFalse(send.isEnabled)
        let draft = app.descendants(matching: .any)["conversation-composer"]
        draft.tap(); draft.typeText("First line\nSecond line")
        XCTAssertTrue(send.isHittable)
        XCTAssertTrue(send.isEnabled)
        XCTAssertEqual(send.label, "排队发送")
        XCTAssertTrue(app.buttons["stop-generation"].isHittable)
        XCTAssertTrue(app.buttons["conversation-files"].isHittable)
        let keyboard = XCTAttachment(screenshot: app.screenshot())
        keyboard.name = "composer-keyboard"; keyboard.lifetime = .keepAlways; add(keyboard)
        app.buttons["收起键盘"].tap()
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        XCTAssertEqual(draft.value as? String, "First line\nSecond line")
        draft.tap()
        send.tap()
        XCTAssertTrue(app.staticTexts["最近操作"].waitForExistence(timeout: 5))
        XCTAssertEqual(draft.value as? String, "说点什么…")
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        let sent = XCTAttachment(screenshot: app.screenshot())
        sent.name = "composer-sent"; sent.lifetime = .keepAlways; add(sent)
    }
    func testMessageSelectionAndCopy() {
        let app = open()
        let select = app.buttons["select-message-text"].firstMatch
        XCTAssertTrue(select.waitForExistence(timeout: 10))
        if !select.isHittable { app.swipeDown() }
        let conversation = XCTAttachment(screenshot: app.screenshot())
        conversation.name = "chat-reading"; conversation.lifetime = .keepAlways; add(conversation)
        select.tap()
        let text = app.textViews["selectable-message-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        XCTAssertEqual(text.value as? String, "已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。")
        app.buttons["copy-entire-message"].tap()
        XCTAssertEqual(app.buttons["copy-entire-message"].label, "已复制")
        let selection = XCTAttachment(screenshot: app.screenshot())
        selection.name = "message-selection"; selection.lifetime = .keepAlways; add(selection)
        app.buttons["完成"].tap()
        XCTAssertTrue(app.buttons["copy-message"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["copy-message"].firstMatch.tap()
        XCTAssertEqual(app.buttons["copy-message"].firstMatch.label, "已复制")
    }
    private func open(questions: Bool = false, reset: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        if reset { app.launchArguments.append("--ui-test-reset") }
        if questions { app.launchArguments.append("--ui-test-questions") }
        app.launch()
        app.buttons["示例对话"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["conversation-composer"].waitForExistence(timeout: 10))
        return app
    }
    func testApprovalAndDraftSurviveNavigationAndRelaunch() {
        let app = open()
        XCTAssertTrue(app.buttons["本次允许"].waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "approval-light"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.buttons["本次允许"].tap()
        XCTAssertTrue(app.staticTexts["事项已更新"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["本次允许"].exists)
        app.buttons["最近操作"].tap()
        XCTAssertTrue(app.staticTexts["操作已接受"].waitForExistence(timeout: 5))
        let draft = app.descendants(matching: .any)["conversation-composer"]
        draft.tap(); draft.typeText("Draft survives")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["示例对话"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["conversation-composer"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.descendants(matching: .any)["conversation-composer"].value as? String, "Draft survives")
        app.terminate()
        let reopened = open(reset: false)
        XCTAssertEqual(reopened.descendants(matching: .any)["conversation-composer"].value as? String, "Draft survives")
    }
    func testQuestionAnswerAndSteerMenu() {
        let app = open(questions: true)
        XCTAssertTrue(app.buttons["提交回答"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["提交回答"].isEnabled)
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "iOS")).firstMatch.tap()
        XCTAssertTrue(app.buttons["提交回答"].isEnabled)
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "question-form"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.buttons["提交回答"].tap()
        let draft = app.descendants(matching: .any)["conversation-composer"]
        draft.tap(); draft.typeText("Focus on iOS")
        app.buttons["发送方式"].tap()
        XCTAssertTrue(app.buttons["立即补充"].waitForExistence(timeout: 5))
        app.buttons["立即补充"].tap()
        XCTAssertTrue(draft.waitForExistence(timeout: 5))
        XCTAssertEqual(draft.value as? String, "说点什么…")
    }
    func testGlobalSearchLocatesOldMessageAndLoadsMissingContext() {
        continueAfterFailure = false
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "--ui-test-long-conversation", "--ui-test-search-context",
                                   "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.beginConversationSearch(); app.buttons["搜索消息"].tap()
            let search = app.searchFields.firstMatch
            XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("release")
            let result = app.buttons["history-search-a:2"]
            XCTAssertTrue(result.waitForExistence(timeout: 5)); result.tap()
            app.buttons["open-search-conversation"].tap()
            XCTAssertTrue(app.staticTexts["消息 2：这是一段用于检查长对话阅读位置的内容。"].waitForExistence(timeout: 8))
            let initial = XCTAttachment(screenshot: app.screenshot())
            initial.name = "search-target-before-drawer-\(appearance)"; initial.lifetime = .keepAlways; add(initial)
            if !app.staticTexts["消息 2：这是一段用于检查长对话阅读位置的内容。"].isHittable {
                let tree = XCTAttachment(string: app.debugDescription); tree.lifetime = .keepAlways; add(tree)
            }
            XCTAssertTrue(app.staticTexts["消息 2：这是一段用于检查长对话阅读位置的内容。"].isHittable)
            app.revealConversationMenu(); app.dismissConversationMenu()
            XCTAssertTrue(app.staticTexts["消息 2：这是一段用于检查长对话阅读位置的内容。"].isHittable)
            XCTAssertTrue(app.staticTexts["located-message"].exists)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "search-context-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            let gap = app.buttons["history-gap-150"]
            for _ in 0..<24 {
                let height = app.frame.height
                if gap.exists {
                    let y = gap.frame.midY
                    if gap.isHittable && y > height * 0.27 && y < height * 0.65 { break }
                    if abs(y) < height * 1.2 {
                        // A fixed fling overshoots this small target in opposite directions.
                        // Move only the measured error and pause before lifting the finger.
                        let delta = max(-80, min(80, y - height * 0.46))
                        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5 - delta / height)),
                                   withVelocity: XCUIGestureVelocity(rawValue: 100), thenHoldForDuration: 0.3)
                        continue
                    }
                }
                app.swipeUp(velocity: .fast)
            }
            XCTAssertTrue(gap.isHittable)
            XCTAssertGreaterThan(gap.frame.midY, app.frame.height * 0.27)
            XCTAssertLessThan(gap.frame.midY, app.frame.height * 0.65)
            gap.tap()
            let loaded = app.staticTexts["消息 28：这是一段用于检查长对话阅读位置的内容。"].waitForExistence(timeout: 5)
            let context = XCTAttachment(screenshot: app.screenshot())
            context.name = "loaded-context-\(appearance)"; context.lifetime = .keepAlways; add(context)
            if !loaded { let tree = XCTAttachment(string: app.debugDescription); tree.lifetime = .keepAlways; add(tree) }
            XCTAssertTrue(loaded)
            app.buttons["scroll-to-latest"].tap()
            XCTAssertTrue(app.staticTexts["消息 199：这是一段用于检查长对话阅读位置的内容。"].isHittable)
            app.terminate()
        }
    }
    func testConversationSearchReturnsToMessageContextAndKeepsDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-reset", "--ui-test-long-conversation", "--ui-test-search-context", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let composer = app.descendants(matching: .any)["conversation-composer"]
        XCTAssertTrue(composer.waitForExistence(timeout: 5)); composer.tap(); composer.typeText("Keep this draft")
        app.buttons["search-conversation"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("消息 2：")
        let result = app.buttons["search-result-2"]
        XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
        app.buttons["open-message-context"].tap()
        XCTAssertTrue(app.staticTexts["located-message"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["消息 2：这是一段用于检查长对话阅读位置的内容。"].isHittable)
        XCTAssertEqual(composer.value as? String, "Keep this draft")
    }
    func testGlobalMessageSearchOpensFullMessageAndConversation() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch()
            if appearance == "light" {
                app.beginConversationSearch(); app.buttons["搜索消息"].tap()
                let search = app.searchFields.firstMatch
                XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("release")
            } else {
                app.beginConversationSearch()
                let search = app.textFields["sidebar-search"]
                XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("release")
                app.buttons["search-all-messages"].tap()
            }
            let first = app.buttons["history-search-a:2"]
            XCTAssertTrue(first.waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["history-search-c:2"].exists)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "global-message-search-\(appearance)"; screenshot.lifetime = .keepAlways; add(screenshot)
            first.tap()
            XCTAssertTrue(app.staticTexts["完整记录：release 检查已完成，等待确认后提交。"].waitForExistence(timeout: 5))
            app.buttons["open-search-conversation"].tap()
            XCTAssertTrue(app.staticTexts["selected-conversation"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["selected-conversation"].label, "修改登录页")
            app.terminate()
        }
    }
    func testDirectorySearchAndDeviceFilter() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 10))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "conversation-list"; image.lifetime = .keepAlways; add(image)
        app.buttons["device-filter"].tap()
        app.buttons["Windows"].tap()
        XCTAssertTrue(app.staticTexts["整理会议纪要"].exists)
        XCTAssertFalse(app.staticTexts["修改登录页"].exists)
        app.buttons["device-filter"].tap()
        app.buttons["全部设备"].tap()
        app.beginConversationSearch()
        let search = app.textFields["sidebar-search"].exists ? app.textFields["sidebar-search"] : app.searchFields.firstMatch
        if !search.exists { app.swipeDown() }
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap(); search.typeText("登录")
        XCTAssertTrue(app.staticTexts["修改登录页"].exists)
        XCTAssertFalse(app.staticTexts["整理会议纪要"].exists)
    }

    func testRichTextCopyAndPendingShortcut() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-rich-text", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.buttons["pending-shortcut"].waitForExistence(timeout: 10))
        app.swipeDown()
        let copy = app.buttons["copy-code"]
        XCTAssertTrue(copy.waitForExistence(timeout: 5))
        if !copy.isHittable { app.swipeDown() }
        copy.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "已复制")).firstMatch.waitForExistence(timeout: 2))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "message-formatting"; image.lifetime = .keepAlways; add(image)
        app.buttons["pending-shortcut"].tap()
        XCTAssertTrue(app.buttons["本次允许"].isHittable)
    }
    func testOfflineRelaunchShowsSavedMessagesAndDisablesSending() {
        let app = open()
        XCTAssertTrue(app.staticTexts["已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。"].waitForExistence(timeout: 5))
        app.terminate()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-offline", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(app.staticTexts["已保存的记录"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。"].exists)
        XCTAssertFalse(app.buttons["发送"].isEnabled)
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "offline-history"; image.lifetime = .keepAlways; add(image)
    }

    func testAttachmentListAndPreview() {
        let app = open()
        app.buttons["conversation-files"].tap(); app.buttons["对话文件"].tap()
        XCTAssertTrue(app.staticTexts["说明.txt"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["同步中"].exists)
        XCTAssertTrue(app.staticTexts["请在桌面打开"].exists)
        let list = XCTAttachment(screenshot: app.screenshot()); list.name = "attachment-list"; list.lifetime = .keepAlways; add(list)
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "说明.txt")).firstMatch.tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 5))
        let preview = XCTAttachment(screenshot: app.screenshot()); preview.name = "attachment-preview"; preview.lifetime = .keepAlways; add(preview)
        app.buttons["QLOverlayDoneButtonAccessibilityIdentifier"].tap()
        XCTAssertTrue(app.buttons["分享"].waitForExistence(timeout: 5))
        app.terminate()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-offline", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap(); app.buttons["conversation-files"].tap(); app.buttons["对话文件"].tap()
        XCTAssertTrue(app.staticTexts["已保存的文件列表"].waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "说明.txt")).firstMatch.tap()
        XCTAssertTrue(app.otherElements["QLPreviewControllerView"].waitForExistence(timeout: 5))
    }

    func testTerminalCreationFeedbackKeepsDraftAndOffersRecovery() {
        continueAfterFailure = false
        for (status, message) in [("rejected", "未能创建，请检查所选工作区。"), ("expired", "请求已过期，请重新选择。")] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "--ui-test-creation-" + status, "-AppleLanguages", "(zh-Hans)"]
            app.launch(); app.buttons["new-conversation"].tap()
            let draft = app.descendants(matching: .any)["creation-draft"].firstMatch
            XCTAssertTrue(draft.waitForExistence(timeout: 5)); draft.tap(); draft.typeText("Keep this task")
            app.buttons["收起键盘"].tap()
            app.buttons["create-conversation"].tap()
            XCTAssertTrue(app.staticTexts[message].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", message)).count, 1)
            XCTAssertFalse(app.staticTexts["创建结果待确认"].exists)
            XCTAssertTrue(app.buttons["核对并清除"].isEnabled)
            app.buttons["preview-submitted-task"].tap()
            XCTAssertTrue(app.staticTexts["这是本次提交的任务内容。"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["task-content-preview-text"].label.contains("Keep this task"))
            app.buttons["close-task-content-preview"].tap()
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "creation-" + status; shot.lifetime = .keepAlways; add(shot)
            app.buttons["核对并清除"].tap()
            app.buttons["确认清除"].tap()
            XCTAssertTrue(draft.waitForExistence(timeout: 5))
            XCTAssertEqual(draft.value as? String, "Keep this task")
            app.terminate()
        }
    }
    func testNewChatSelectsWorkspaceAndOpensAfterReceipt() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); app.buttons["new-conversation"].tap()
        let create = app.buttons["create-conversation"]
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        XCTAssertTrue(create.isEnabled)
        XCTAssertTrue(app.staticTexts["funnywork"].exists)
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "new-conversation"; image.lifetime = .keepAlways; add(image)
        create.tap()
        XCTAssertTrue(app.staticTexts["未命名对话"].waitForExistence(timeout: 12))
    }
    func testNewChatRemembersLocationAndDoesNotSwitchOfflineDevice() {
        let app = XCUIApplication()
        let arguments = ["--ui-test-directory", "--ui-test-creation-locations", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchArguments = arguments + ["--ui-test-reset", "-appearance", "light"]
        app.launch(); app.buttons["new-conversation"].tap()
        let create = app.buttons["create-conversation"]
        XCTAssertTrue(create.waitForExistence(timeout: 5)); XCTAssertFalse(create.isEnabled)
        app.buttons["creation-device"].tap()
        app.buttons["Mac mini"].tap()
        app.buttons["creation-workspace"].tap()
        app.buttons["Notes"].tap()
        XCTAssertTrue(create.isEnabled); create.tap()
        XCTAssertTrue(app.staticTexts["未命名对话"].waitForExistence(timeout: 12))
        app.terminate()
        app.launchArguments = arguments + ["-appearance", "dark"]
        app.launch(); app.buttons["new-conversation"].tap()
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        XCTAssertTrue(create.isEnabled)
        XCTAssertTrue(app.staticTexts["Mac mini"].exists); XCTAssertTrue(app.staticTexts["Notes"].exists)
        let restored = XCTAttachment(screenshot: app.screenshot())
        restored.name = "remembered-creation-location"; restored.lifetime = .keepAlways; add(restored)
        app.terminate()
        app.launchArguments = arguments + ["--ui-test-creation-offline", "-appearance", "light"]
        app.launch(); app.buttons["new-conversation"].tap()
        XCTAssertTrue(app.staticTexts["设备已离线，请选择其他设备或稍后重试。"].waitForExistence(timeout: 5))
        XCTAssertFalse(create.isEnabled)
        XCTAssertTrue(app.staticTexts["Notes"].exists)
        let offline = XCTAttachment(screenshot: app.screenshot())
        offline.name = "offline-creation-location"; offline.lifetime = .keepAlways; add(offline)
    }
    func testNewChatFromArchiveReturnsToActiveConversations() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-directory", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.revealConversationMenu(); app.buttons["已归档"].tap()
        XCTAssertTrue(app.staticTexts["没有已归档的对话"].waitForExistence(timeout: 5))
        app.buttons["new-conversation"].tap()
        let create = app.buttons["create-conversation"]
        XCTAssertTrue(create.waitForExistence(timeout: 5)); create.tap()
        XCTAssertTrue(app.staticTexts["未命名对话"].waitForExistence(timeout: 12))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["修改登录页"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["没有已归档的对话"].exists)
    }

}
