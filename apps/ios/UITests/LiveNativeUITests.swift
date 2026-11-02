import XCTest

/// Opt-in acceptance against real services, normal Account and SwiftUI navigation.
/// The host supplies a private configuration path; never use fixture/reset arguments.
final class LiveNativeUITests: XCTestCase {
    func testExistingTaskDraftResultsAndOptionalApproval() throws {
        guard let path = ProcessInfo.processInfo.environment["FUNNYWORK_LIVE_UI_CONFIG"] else {
            throw XCTSkip("Requires an authorized private live UI configuration")
        }
        let config = try JSONDecoder().decode(Configuration.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        guard ["https://funnywork.funnypixelsapp.com", "https://funnywork.funnypixelsapp.com"].contains(config.origin),
              ["3c938b76-8e69-46ff-ab05-b86c67200df8", "cc7e42b0-33a9-4892-b169-68af8530d957"].contains(config.sessionID) else {
            return XCTFail("Live acceptance must use an existing authorized UAT task")
        }
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.funnyworker.ios.liveui")
        app.launchArguments = ["--live-ui", "-AppleLanguages", "(zh-Hans)"]
        app.launchEnvironment["FUNNYWORK_LIVE_UI_ORIGIN"] = config.origin
        app.launch()
        defer { capture(app, "live-final-state"); app.terminate() }
        let submit = app.buttons["login-submit"]
        if submit.waitForExistence(timeout: 8) {
            app.textFields["login-username"].tap()
            app.textFields["login-username"].typeText(config.username)
            app.secureTextFields["login-password"].tap()
            app.secureTextFields["login-password"].typeText(config.password)
            submit.tap()
        }
        XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 60))
        app.revealConversationMenu()
        let remote = app.buttons["scope-desktop"]
        XCTAssertTrue(remote.waitForExistence(timeout: 15)); remote.tap()
        app.revealConversationMenu()
        XCTAssertEqual(app.buttons["账号"].value as? String, config.displayName, "Live UI must use the authorized account")
        app.textFields["sidebar-search"].tap(); app.textFields["sidebar-search"].typeText("恢复验收")
        let row = app.buttons["conversation-" + config.sessionID]
        XCTAssertTrue(row.waitForExistence(timeout: 45)); row.tap()
        let composer = app.descendants(matching: .any)["conversation-composer"].firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 30))
        let ownership: URL? = try config.ownershipDirectory.map { path in
            guard path.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-real-results-20261022/"), !path.split(separator: "/").contains("..") else { throw NSError(domain: "LiveOwnership", code: 1) }
            return URL(fileURLWithPath: path).appendingPathComponent("owned-draft.json")
        }
        if let ownership, let data = try? Data(contentsOf: ownership), let saved = try? JSONSerialization.jsonObject(with: data) as? [String: String], saved["expected"] == composer.value as? String, let before = saved["before"] { replace(composer, with: before, app: app) }
        let original = composer.value as? String ?? ""
        let marker = " LIVE-UI-" + UUID().uuidString
        if let ownership { try JSONSerialization.data(withJSONObject: ["before": original, "expected": original + marker]).write(to: ownership, options: .atomic) }
        replace(composer, with: original + marker, app: app)
        XCTAssertEqual(composer.value as? String, original + marker)
        app.terminate(); app.launch()
        XCTAssertTrue(composer.waitForExistence(timeout: 45))
        XCTAssertEqual(composer.value as? String, original + marker)
        capture(app, "live-restored-task")
        // Only the exact new event supplied by the host may be decided. No old-event replay.
        var expectedDraft = original + marker
        if config.previewRetry == true {
            openResultDirectory(app)
            let result = app.buttons["result-directory-" + config.resultID]
            XCTAssertTrue(result.waitForExistence(timeout: 45)); result.tap()
            let version = config.targetVersion ?? 1
            if app.buttons["result-version-menu"].exists {
                waitEnabled(app.buttons["result-version-menu"])
                app.buttons["result-version-menu"].tap(); app.buttons["版本 \(version)"].tap()
            }
            XCTAssertTrue(app.staticTexts["预览未完成，原文件仍可下载"].waitForExistence(timeout: 30))
            XCTAssertTrue(app.buttons["下载原文件"].exists)
            waitEnabled(app.buttons["重试预览"]); app.buttons["重试预览"].tap()
            if app.navigationBars["结果文件"].exists { app.buttons["完成"].firstMatch.tap() }
            XCTAssertTrue(composer.waitForExistence(timeout: 30))
            let retry = "请重新生成这个结果的预览：\nresultId=\(config.resultID)\nversion=\(version)\n调用 prepare_result_preview，并保持原文件不变。"
            XCTAssertEqual(composer.value as? String, original + marker + "\n\n" + retry)
            app.terminate(); app.launch(); XCTAssertTrue(composer.waitForExistence(timeout: 45))
            XCTAssertEqual(composer.value as? String, original + marker + "\n\n" + retry)
            capture(app, "live-preview-retry-draft")
            replace(composer, with: original + marker, app: app)
            if let gate = config.previewRetryDirectory { try approvalReport(gate, name: "passed.json", event: config.resultID) }
        } else if let gate = config.latePreviewDirectory {
            openResultDirectory(app)
            let result = app.buttons["result-directory-" + config.resultID]
            XCTAssertTrue(result.waitForExistence(timeout: 45)); result.tap()
            if let version = config.lateSelectedVersion {
                waitEnabled(app.buttons["result-version-menu"])
                app.buttons["result-version-menu"].tap(); app.buttons["版本 \(version)"].tap()
                XCTAssertEqual(app.staticTexts["result-version-current"].label, "版本 \(version)")
            }
            waitEnabled(app.buttons["下载原文件"]); app.buttons["下载原文件"].tap()
            let image = app.images["gallery-image"]
            XCTAssertTrue(image.waitForExistence(timeout: 60)); XCTAssertEqual(image.label, config.lateOriginalName)
            XCTAssertTrue(app.buttons["分享原文件"].exists)
            try approvalReport(gate, name: "original-open.json", event: config.resultID)
            try awaitApprovalGate(gate, name: "released") { XCTAssertTrue(image.exists); XCTAssertEqual(image.label, config.lateOriginalName); XCTAssertTrue(app.buttons["分享原文件"].exists) }
            let until = Date().addingTimeInterval(12)
            while Date() < until { XCTAssertTrue(image.exists); XCTAssertEqual(image.label, config.lateOriginalName); XCTAssertTrue(app.buttons["分享原文件"].exists); Thread.sleep(forTimeInterval: 0.5) }
            capture(app, "live-real-late-original")
            if let version = config.lateSelectedVersion {
                XCTAssertEqual(app.staticTexts["result-version-current"].label, "版本 \(version)")
                app.buttons["result-version-refresh"].tap()
                XCTAssertEqual(app.staticTexts["result-version-current"].label, "版本 \(version)")
            }
            try approvalReport(gate, name: "passed.json", event: config.resultID)
        } else if let rounds = config.reviewRounds {
            XCTAssertEqual(rounds.count, 2)
            for round in rounds {
                openReview(app, sequence: round.sequence, marker: round.marker ?? config.marker, prefix: round.prefix ?? "ROUND\(round.version)_")
                let aggregate = app.descendants(matching: .any)["review-comment"].firstMatch
                XCTAssertTrue(aggregate.waitForExistence(timeout: 15))
                let existing = aggregate.value as? String ?? ""
                XCTAssertTrue(["", "写下需要调整的地方…", round.opinion].contains(existing))
                if existing != round.opinion { aggregate.tap(); aggregate.typeText(round.opinion) }
                app.buttons["完成"].firstMatch.tap(); app.terminate(); app.launch(); XCTAssertTrue(composer.waitForExistence(timeout: 45))
            }
            app.terminate(); app.launch(); XCTAssertTrue(composer.waitForExistence(timeout: 45))
            for round in rounds {
                openReview(app, sequence: round.sequence, marker: round.marker ?? config.marker, prefix: round.prefix ?? "ROUND\(round.version)_")
                let aggregate = app.descendants(matching: .any)["review-comment"].firstMatch
                XCTAssertTrue(aggregate.waitForExistence(timeout: 15)); XCTAssertEqual(aggregate.value as? String, round.opinion)
                capture(app, "live-restored-round-\(round.version)")
                app.buttons["完成"].firstMatch.tap(); app.terminate(); app.launch(); XCTAssertTrue(composer.waitForExistence(timeout: 45))
            }
            let chosen = rounds[1]
            openReview(app, sequence: chosen.sequence, marker: chosen.marker ?? config.marker, prefix: chosen.prefix ?? "ROUND\(chosen.version)_")
            app.buttons["stage-review-feedback"].firstMatch.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 15))
            let staged = composer.value as? String ?? ""
            if let ownership { try JSONSerialization.data(withJSONObject: ["before": original, "expected": staged]).write(to: ownership, options: .atomic) }
            XCTAssertTrue(staged.hasPrefix(original + marker)); XCTAssertEqual(staged.components(separatedBy: chosen.opinion).count, 2)
            XCTAssertFalse(staged.contains(rounds[0].opinion))
            replace(composer, with: original + marker, app: app)
            if let ownership { try JSONSerialization.data(withJSONObject: ["before": original, "expected": original + marker]).write(to: ownership, options: .atomic) }
            openReview(app, sequence: rounds[0].sequence, marker: rounds[0].marker ?? config.marker, prefix: rounds[0].prefix ?? "ROUND1_")
            let aggregate = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(aggregate.waitForExistence(timeout: 15)); XCTAssertEqual(aggregate.value as? String, rounds[0].opinion)
            replace(aggregate, with: "", app: app)
            app.buttons["完成"].firstMatch.tap()
        } else if let sequence = config.reviewSequence {
            openReview(app, sequence: sequence, marker: config.marker)
            let file = app.buttons["review-change-\(sequence)-0-0"]
            XCTAssertTrue(file.waitForExistence(timeout: 30)); file.tap()
            let line = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "+V2_" + config.marker)).firstMatch
            XCTAssertTrue(line.waitForExistence(timeout: 15)); line.tap(); line.tap()
            let note = app.descendants(matching: .any)["review-file-comment"].firstMatch
            XCTAssertTrue(note.waitForExistence(timeout: 10))
            XCTAssertTrue(["", "写下需要调整的地方…"].contains(note.value as? String ?? ""))
            let opinion = "LIVE-REVIEW-" + UUID().uuidString
            note.tap(); note.typeText(opinion)
            capture(app, "live-line-review")
            app.terminate(); app.launch()
            XCTAssertTrue(composer.waitForExistence(timeout: 45))
            XCTAssertEqual(composer.value as? String, expectedDraft)
            openReview(app, sequence: sequence, marker: config.marker)
            XCTAssertTrue(file.waitForExistence(timeout: 30)); file.tap()
            XCTAssertTrue(note.waitForExistence(timeout: 10))
            XCTAssertEqual(note.value as? String, opinion)
            XCTAssertTrue(line.waitForExistence(timeout: 15)); line.tap()
            app.buttons["review-file-done"].tap()
            let aggregate = app.descendants(matching: .any)["review-comment"].firstMatch
            XCTAssertTrue(aggregate.waitForExistence(timeout: 10))
            let feedback = aggregate.value as? String ?? ""
            XCTAssertEqual(feedback.components(separatedBy: "+V2_" + config.marker).count, 2)
            XCTAssertEqual(feedback.components(separatedBy: opinion).count, 2)
            XCTAssertTrue(feedback.contains("#\(sequence) · review.txt"))
            capture(app, "live-restored-line-review")
            app.buttons["stage-review-feedback"].tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 15))
            expectedDraft = composer.value as? String ?? ""
            XCTAssertTrue(expectedDraft.hasPrefix(original + marker))
            XCTAssertTrue(expectedDraft.contains(opinion))
            XCTAssertEqual(expectedDraft.components(separatedBy: opinion).count, 2)
        } else if let event = config.eventID {
            let actionID = (config.allow == true ? "approval-allow-" : "approval-deny-") + event
            let action = app.buttons[actionID]
            if app.buttons["pending-shortcut"].exists { app.buttons["pending-shortcut"].tap() }
            XCTAssertTrue(action.waitForExistence(timeout: 60)); XCTAssertTrue(action.isEnabled)
            if let gate = config.approvalGateDirectory {
                try approvalReport(gate, name: "ready.json", event: event)
                try awaitApprovalGate(gate, name: "go") {
                    if config.observeOnly != true { XCTAssertTrue(action.exists); XCTAssertTrue(action.isEnabled) }
                    XCTAssertEqual(composer.value as? String, original + marker)
                }
                capture(app, "live-slow-control-approval")
            }
            capture(app, "live-native-approval"); if config.observeOnly != true { action.tap() }
            let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                app.buttons.matching(identifier: actionID).matching(NSPredicate(format: "enabled == true")).count == 0
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 60), .completed)
            if let gate = config.approvalGateDirectory {
                try approvalReport(gate, name: config.observeOnly == true ? "observed.json" : "posted.json", event: event)
                capture(app, "live-approval-posted")
                try awaitApprovalGate(gate, name: "finish") { XCTAssertEqual(composer.value as? String, original + marker) }
            }
            XCTAssertEqual(composer.value as? String, original + marker)
        } else if config.draftOnly != true {
            openResultDirectory(app)
            let result = app.buttons["result-directory-" + config.resultID]
            XCTAssertTrue(result.waitForExistence(timeout: 45)); capture(app, "live-result-directory")
            result.tap()
            XCTAssertTrue(app.buttons["下载原文件"].waitForExistence(timeout: 45))
            XCTAssertTrue(app.staticTexts[(config.complex == true ? "COMPLEX_R\(config.targetVersion ?? 2)_" : "V2_") + config.marker].waitForExistence(timeout: 45))
            if config.targetVersion != 1 {
            waitEnabled(app.buttons["result-version-menu"])
            app.buttons["result-version-menu"].tap(); app.buttons["版本 1"].tap()
            let version = app.staticTexts["result-version-current"]
            XCTAssertTrue(NSPredicate(format: "label == %@", "版本 1").evaluate(with: version))
            if config.complex == true {
                XCTAssertTrue(app.staticTexts["COMPLEX_R1_" + config.marker].waitForExistence(timeout: 45))
                capture(app, "live-real-complex-v1")
            }
            app.buttons["result-version-refresh"].tap()
            XCTAssertEqual(version.label, "版本 1")
            if config.complex == true { XCTAssertTrue(app.staticTexts["COMPLEX_R1_" + config.marker].exists) }
            waitEnabled(app.buttons["result-version-menu"])
            app.buttons["result-version-menu"].tap(); app.buttons["版本 \(config.targetVersion ?? 2)"].tap()
            XCTAssertTrue(app.staticTexts[(config.complex == true ? "COMPLEX_R\(config.targetVersion ?? 2)_" : "V2_") + config.marker].waitForExistence(timeout: 45))
            }
            capture(app, "live-result-preview")
            if config.complex == true {
                app.buttons["workbook-next-columns"].tap()
                XCTAssertTrue(app.staticTexts["COLP_" + config.marker].waitForExistence(timeout: 15))
                app.buttons["workbook-next-columns"].tap()
                XCTAssertTrue(app.staticTexts["COLAD_" + config.marker].waitForExistence(timeout: 15))
                app.buttons["前一组列"].tap(); app.buttons["前一组列"].tap()
                app.buttons["workbook-row-windows"].tap(); app.buttons["201–300"].tap()
                XCTAssertTrue(app.staticTexts["ROW201_" + config.marker].waitForExistence(timeout: 30))
                app.buttons["workbook-sheets"].tap(); app.buttons["Empty"].tap()
                XCTAssertTrue(app.staticTexts["此范围没有已保存的值"].waitForExistence(timeout: 15))
                capture(app, "live-empty-sheet")
                app.buttons["workbook-sheets"].tap(); app.buttons["Summary"].tap()
            }
            app.buttons["workbook-sheets"].tap(); app.buttons["Details"].tap()
            XCTAssertTrue(app.staticTexts[(config.complex == true ? "DETAILS_" : "SECOND_") + config.marker].waitForExistence(timeout: 45))
            if config.complex != true {
                app.buttons["workbook-next-columns"].tap()
                XCTAssertTrue(app.staticTexts["COL_P_" + config.marker].waitForExistence(timeout: 10))
            }
            capture(app, "live-workbook-details")
        }
        // Restore only the text added by this acceptance run, leaving existing draft intact.
        app.terminate(); app.launch()
        XCTAssertTrue(composer.waitForExistence(timeout: 45))
        XCTAssertEqual(composer.value as? String, expectedDraft)
        if config.observeOnly == true, let event = config.eventID {
            for id in ["approval-allow-" + event, "approval-deny-" + event] {
                XCTAssertEqual(app.buttons.matching(identifier: id).matching(NSPredicate(format: "enabled == true")).count, 0)
            }
        }
        replace(composer, with: original, app: app)
        XCTAssertEqual(composer.value as? String, original)
        if let ownership { try? FileManager.default.removeItem(at: ownership) }
        if config.logoutAfter == true {
            app.revealConversationMenu(); app.buttons["desktop-sidebar-account"].tap()
            let exit = app.buttons["退出登录"]
            if !exit.waitForExistence(timeout: 5) { app.swipeUp() }
            XCTAssertTrue(exit.waitForExistence(timeout: 10))
            exit.tap(); app.alerts.buttons["退出登录"].tap()
            XCTAssertTrue(app.buttons["login-submit"].waitForExistence(timeout: 30))
        }
    }
    private func approvalDirectory(_ path: String) throws -> URL {
        guard (path.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-mac-authorization-20261022/slow-ui/") || path.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-final-epoch-20261022/") || path.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-dev7-final-20261022/") || path.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-real-results-20261022/") || path.hasPrefix("/Volumes/SSD/Code/funnyworker/.local/agent/apps/android-dev9-final-20261022/")),
              !path.split(separator: "/").contains("..") else { throw NSError(domain: "LiveApprovalGate", code: 1) }
        return URL(fileURLWithPath: path, isDirectory: true)
    }
    private func approvalReport(_ path: String, name: String, event: String) throws {
        let file = try approvalDirectory(path).appendingPathComponent(name)
        try JSONSerialization.data(withJSONObject: ["eventId": event, "observedAt": Date().timeIntervalSince1970]).write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
    private func awaitApprovalGate(_ path: String, name: String, verify: () -> Void) throws {
        let file = try approvalDirectory(path).appendingPathComponent(name)
        let deadline = Date().addingTimeInterval(360)
        while !FileManager.default.fileExists(atPath: file.path), Date() < deadline { verify(); Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "Host gate timed out")
    }
    private func openResultDirectory(_ app: XCUIApplication) {
        let directory = app.navigationBars["结果文件"]
        for _ in 0..<3 {
            if directory.exists { return }
            let item = app.buttons["对话文件"]
            if !item.exists { app.buttons["conversation-files"].tap() }
            XCTAssertTrue(item.waitForExistence(timeout: 10))
            let hittable = NSPredicate(format: "exists == true AND hittable == true")
            expectation(for: hittable, evaluatedWith: item)
            waitForExpectations(timeout: 10)
            item.tap()
            if directory.waitForExistence(timeout: 8) { return }
        }
        XCTFail("Normal files menu did not open the result directory")
    }
    private func openReview(_ app: XCUIApplication, sequence: Int, marker: String, prefix: String = "V2_") {
        app.buttons["search-conversation"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText(prefix + marker)
        let hit = app.descendants(matching: .any)["search-result-\(sequence)"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 45)); hit.tap()
        app.buttons["open-message-context"].tap()
        let review = app.buttons["review-round-\(sequence)"]
        XCTAssertTrue(review.waitForExistence(timeout: 45))
        for _ in 0..<3 where !review.isHittable { app.swipeUp() }
        review.tap()
    }
    private func replace(_ field: XCUIElement, with text: String, app: XCUIApplication) {
        field.tap()
        if let old = field.value as? String, !old.isEmpty {
            guard old.utf16.count <= 1000 else { return XCTFail("Live test draft exceeds its bounded editor check") }
            // Prefer the native editing menu. This bounds accessibility traffic for
            // multiline staged feedback while retaining the verified keyboard fallback.
            field.press(forDuration: 1.2)
            let selectAll = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["全选", "Select All"])).firstMatch
            if selectAll.waitForExistence(timeout: 2) {
                selectAll.tap(); field.typeText(XCUIKeyboardKey.delete.rawValue)
                XCTAssertEqual(field.value as? String, "", "Native Select All must clear exactly")
                if !text.isEmpty { field.typeText(text) }
                return
            }
            field.tap()
            for _ in 0...old.utf16.count {
                field.typeKey(XCUIKeyboardKey.rightArrow.rawValue, modifierFlags: [])
            }
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.utf16.count + 1))
            XCTAssertEqual(field.value as? String, "", "Native selection must clear exactly before replacement")
        }
        if !text.isEmpty { field.typeText(text) }
    }
    private func waitEnabled(_ element: XCUIElement) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in element.exists && element.isEnabled }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private struct Configuration: Decodable {
        let origin: String, username: String, password: String, sessionID: String, resultID: String, displayName: String, marker: String
        struct Round: Decodable { let sequence: Int, version: Int, opinion: String; let marker: String?; let prefix: String? }
        let latePreviewDirectory: String?
        let previewRetry: Bool?
        let previewRetryDirectory: String?
        let lateOriginalName: String?
        let lateSelectedVersion: Int?
        let reviewRounds: [Round]?
        let targetVersion: Int?
        let complex: Bool?
        let ownershipDirectory: String?
        let eventID: String?
        let allow: Bool?
        let logoutAfter: Bool?
        let reviewSequence: Int?
        let approvalGateDirectory: String?
        let draftOnly: Bool?
        let observeOnly: Bool?
    }
}
