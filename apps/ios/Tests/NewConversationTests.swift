import XCTest
@testable import FunnyWork

@MainActor final class CreationServiceStub: NewConversationService {
    var deviceRows: [CreationDevice]?
    var epoch = "epoch-1"
    var online = true
    var failedSubmit = false
    var stored: CreationReceipt?
    var submissions: [CreationRequest] = []
    func devices() async throws -> [CreationDevice] {
        if let deviceRows { return deviceRows }
        return [try CreationDevice(["id": "device", "name": "Mac", "online": online, "epoch": epoch,
            "workspaces": [["id": "work", "title": "Work"]]])]
    }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt {
        submissions.append(request)
        if failedSubmit { throw URLError(.timedOut) }
        let value = try CreationReceipt(["id": request.id, "status": "queued"])
        stored = value; return value
    }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt {
        try stored ?? CreationReceipt(["id": request.id, "status": "missing"])
    }
}
@MainActor final class NewConversationTests: XCTestCase {
    var root: URL!
    override func setUp() async throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }
    func store() -> ConversationStore { ConversationStore(key: "account", root: root) }
    func testUncreatedTaskRestoresTargetAndExplicitChoicesAndKeepsUnavailableLocation() async throws {
        let service = CreationServiceStub(); let cache = store()
        let choices: [String: Any] = ["models": [["provider": "enterprise", "model": "qwen", "name": "Enterprise"]], "presets": [["id": "helper", "name": "Helper"]]]
        let a = try CreationDevice(["id": "a", "name": "First", "online": true, "epoch": "one", "workspaces": [["id": "main", "title": "Main"]]])
        let b = try CreationDevice(["id": "b", "name": "Second", "online": true, "epoch": "two", "choices": choices, "workspaces": [["id": "notes", "title": "Notes"], ["id": "project", "title": "Project"]]])
        service.deviceRows = [a, b]
        let first = NewConversationModel(service: service, store: cache); await first.refresh()
        first.chooseDevice("b"); first.chooseWorkspace("notes"); first.chooseModel("enterprise\nqwen"); first.choosePreset("helper"); first.updateDraft("Task for second computer")
        let restored = NewConversationModel(service: service, store: cache); await restored.refresh()
        XCTAssertEqual(restored.deviceID, "b"); XCTAssertEqual(restored.workspaceID, "notes")
        XCTAssertEqual(restored.modelID, "enterprise\nqwen"); XCTAssertEqual(restored.presetID, "helper"); XCTAssertTrue(restored.canCreate)
        service.deviceRows = [a]; await restored.refresh()
        XCTAssertEqual(restored.deviceID, "b"); XCTAssertEqual(restored.workspaceID, "notes"); XCTAssertFalse(restored.canCreate)
        XCTAssertNotNil(restored.createDisabledReason); XCTAssertEqual(restored.draft, "Task for second computer")
        let absent = NewConversationModel(service: service, store: cache); await absent.refresh()
        XCTAssertEqual(absent.deviceID, "b"); XCTAssertFalse(absent.canCreate)
        absent.chooseDevice("a"); XCTAssertEqual(absent.workspaceID, "main"); XCTAssertTrue(absent.modelID.isEmpty); XCTAssertTrue(absent.presetID.isEmpty)
        XCTAssertTrue(absent.canCreate); XCTAssertTrue(service.submissions.isEmpty)
    }
    func testRememberedDraftKeepsRemovedWorkspaceAndShowsReason() async throws {
        let service = CreationServiceStub(); let cache = store(); let first = NewConversationModel(service: service, store: cache)
        await first.refresh(); first.chooseWorkspace("work"); first.updateDraft("Keep target")
        service.deviceRows = [try CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch-1", "workspaces": [["id": "other", "title": "Other"]]])]
        let restored = NewConversationModel(service: service, store: cache); await restored.refresh()
        XCTAssertEqual(restored.workspaceID, "work"); XCTAssertFalse(restored.canCreate); XCTAssertNotNil(restored.createDisabledReason)
        restored.chooseWorkspace("other"); XCTAssertTrue(restored.canCreate)
    }
    func testTaskDraftSurvivesDismissalAndAcceptedCreationSeedsOnlyOnce() async throws {
        let service = CreationServiceStub(); let cache = store()
        let first = NewConversationModel(service: service, store: cache)
        first.updateDraft("Check the release")
        let restored = NewConversationModel(service: service, store: cache)
        XCTAssertEqual(restored.draft, "Check the release")
        await restored.refresh(); await restored.create()
        let request = try XCTUnwrap(restored.pending)
        var existing = SavedConversation(); existing.draft = "Newer typing"
        try cache.save(existing, for: "created")
        service.stored = try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "created"]])
        let afterRestart = NewConversationModel(service: service, store: cache); await afterRestart.refresh()
        XCTAssertEqual(afterRestart.created?.id, "created")
        XCTAssertEqual(try cache.load("created").draft, "Newer typing\n\nCheck the release")
        XCTAssertEqual(try cache.load("created").seededCreationID, request.id)
        XCTAssertEqual(try cache.loadCreation()?.draft, "")
        await afterRestart.refresh()
        XCTAssertEqual(try cache.load("created").draft, "Newer typing\n\nCheck the release")
        XCTAssertEqual(service.submissions.count, 1)
        XCTAssertNil((request.body["payload"] as? [String: Any])?["text"])
    }
    func testTooLongTaskDraftCannotCreateAndOldCreationCacheDecodes() async throws {
        let service = CreationServiceStub(); let first = NewConversationModel(service: service, store: store())
        await first.refresh(); first.updateDraft(String(repeating: "a", count: 16001))
        XCTAssertFalse(first.canCreate); await first.create(); XCTAssertTrue(service.submissions.isEmpty)
        let old = try JSONDecoder().decode(SavedCreation.self, from: Data("{}".utf8))
        XCTAssertNil(old.draft); XCTAssertNil(old.pendingDraft)
    }
    func testManagedChoicesPersistWithoutCredentialsAndRevokeBlocksCreation() async throws {
        let service = CreationServiceStub(); let cache = store()
        let choices: [String: Any] = ["models": [["provider": "enterprise", "model": "qwen", "name": "企业千问"]],
            "presets": [["id": "enterprise-helper-v1", "name": "企业助手"]]]
        service.deviceRows = [try CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch-1",
            "choices": choices, "workspaces": [["id": "work", "title": "Work"]]])]
        let model = NewConversationModel(service: service, store: cache)
        await model.refresh(); model.modelID = "enterprise\nqwen"; model.presetID = "enterprise-helper-v1"
        XCTAssertTrue(model.canCreate)
        await model.create()
        let pending = try XCTUnwrap(model.pending)
        XCTAssertEqual((pending.body["payload"] as? [String: Any])?["agentPreset"] as? String, "enterprise-helper-v1")
        XCTAssertEqual(pending.model?.model, "qwen")
        let restored = NewConversationModel(service: service, store: cache)
        XCTAssertEqual(restored.pending, pending)
        XCTAssertEqual(service.submissions.count, 1)
        let fresh = NewConversationModel(service: service, store: ConversationStore(key: "other", root: root))
        await fresh.refresh(); fresh.modelID = "enterprise\nqwen"
        service.deviceRows = [try CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch-1",
            "choices": ["models": [], "presets": []], "workspaces": [["id": "work", "title": "Work"]]])]
        await fresh.refresh(); XCTAssertFalse(fresh.canCreate); XCTAssertFalse(fresh.validChoices)
        fresh.resetChoices(); XCTAssertTrue(fresh.canCreate)
    }
    func testAcceptedReceiptAfterRelaunchOpensChatWithoutResubmitting() async throws {
        let service = CreationServiceStub(); let cache = store()
        let first = NewConversationModel(service: service, store: cache)
        await first.refresh(); XCTAssertTrue(first.canCreate)
        await first.create(); let request = try XCTUnwrap(first.pending)
        XCTAssertFalse(first.canCreate)
        service.stored = try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "new-chat"]])
        let restored = NewConversationModel(service: service, store: cache)
        await restored.refresh()
        XCTAssertEqual(restored.created?.id, "new-chat")
        XCTAssertEqual(restored.created?.device, "Mac")
        XCTAssertNil(restored.pending)
        XCTAssertEqual(service.submissions.count, 1)
    }
    func testLostSubmissionRetriesSameIDOnlyWithFreshEpoch() async throws {
        let service = CreationServiceStub(); service.failedSubmit = true
        let model = NewConversationModel(service: service, store: store())
        await model.refresh(); await model.create()
        let original = try XCTUnwrap(model.pending)
        service.failedSubmit = false; await model.retry()
        XCTAssertEqual(service.submissions.map(\.id), [original.id, original.id])
        XCTAssertEqual(model.status, "queued")
        await model.retry(); XCTAssertEqual(service.submissions.count, 2)
    }
    func testExpiredMissingAndChangedEpochNeverReplay() async throws {
        let service = CreationServiceStub()
        let devices = try await service.devices()
        let device = try XCTUnwrap(devices.first)
        for expired in [false, true] {
            let cache = store()
            let request = CreationRequest(device: device, workspaceID: "work", now: Date().addingTimeInterval(expired ? -60 : 0))
            try cache.saveCreation(SavedCreation(pending: request))
            service.epoch = expired ? "epoch-1" : "epoch-2"
            let model = NewConversationModel(service: service, store: cache)
            await model.retry()
            XCTAssertTrue(service.submissions.isEmpty)
            XCTAssertFalse(model.canRetry)
            XCTAssertNotNil(model.pending)
        }
    }
    func testUncertainResultRemainsExplicitAndClearDoesNotResend() async throws {
        let service = CreationServiceStub(); let model = NewConversationModel(service: service, store: store())
        await model.refresh(); await model.create()
        let request = try XCTUnwrap(model.pending)
        service.stored = try CreationReceipt(["id": request.id, "status": "uncertain"])
        await model.retry()
        XCTAssertFalse(model.canRetry); XCTAssertTrue(model.canClear)
        XCTAssertNotNil(model.pending); XCTAssertNil(model.created)
        model.clearAfterReview()
        XCTAssertNil(model.pending); XCTAssertEqual(service.submissions.count, 1)
    }
    func testOfflineAndInvalidatedStoragePreventCreation() async throws {
        let service = CreationServiceStub(); let cache = store()
        let model = NewConversationModel(service: service, store: cache)
        service.online = false; await model.refresh(); await model.create()
        XCTAssertTrue(service.submissions.isEmpty)
        service.online = true; await model.refresh()
        try cache.invalidateAndClear(); await model.create()
        XCTAssertTrue(service.submissions.isEmpty)
        XCTAssertNotNil(model.error)
    }
    func testSuccessfulReceiptRequiresSessionIdentity() {
        XCTAssertThrowsError(try CreationReceipt(["id": "request", "status": "accepted"]))
        XCTAssertThrowsError(try CreationReceipt(["id": "request", "status": "accepted", "result": ["sessionId": "../escape"]]))
    }
}

@MainActor final class DelayedCreationServiceStub: NewConversationService {
    var continuation: CheckedContinuation<CreationReceipt, Error>?
    func devices() async throws -> [CreationDevice] {
        [try CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch-1", "workspaces": [["id": "work", "title": "Work"]]])]
    }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt { fatalError("unused") }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt {
        try await withCheckedThrowingContinuation { continuation = $0 }
    }
}
extension NewConversationTests {
    func testCancelledReceiptKeepsPendingForNextPage() async throws {
        let cache = store(); let service = DelayedCreationServiceStub()
        let devices = try await service.devices()
        let request = CreationRequest(device: devices[0], workspaceID: "work")
        try cache.saveCreation(SavedCreation(pending: request))
        let model = NewConversationModel(service: service, store: cache)
        let checking = Task { await model.refresh() }
        while service.continuation == nil { await Task.yield() }
        checking.cancel()
        service.continuation?.resume(returning: try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "chat"]]))
        await checking.value
        XCTAssertEqual(try cache.loadCreation()?.pending?.id, request.id)
        XCTAssertNil(model.created)
        let restoredService = CreationServiceStub()
        restoredService.stored = try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "chat"]])
        let restored = NewConversationModel(service: restoredService, store: cache)
        await restored.refresh()
        XCTAssertEqual(restored.created?.id, "chat")
        XCTAssertNil(try cache.loadCreation()?.pending)
        XCTAssertTrue(restoredService.submissions.isEmpty)
    }
    func testOldPageManualClearPreservesNewPendingRequest() async throws {
        let cache = store(); let service = CreationServiceStub()
        let devices = try await service.devices()
        let request = CreationRequest(device: devices[0], workspaceID: "work", now: Date().addingTimeInterval(-60))
        try cache.saveCreation(SavedCreation(pending: request))
        let oldScreen = NewConversationModel(service: service, store: cache)
        let newScreen = NewConversationModel(service: service, store: cache)
        await newScreen.refresh(); newScreen.clearAfterReview(); await newScreen.create()
        let newID = try XCTUnwrap(newScreen.pending?.id)
        oldScreen.clearAfterReview()
        XCTAssertEqual(try cache.loadCreation()?.pending?.id, newID)
        XCTAssertEqual(oldScreen.pending?.id, newID)
    }
    func testOldSheetReceiptDoesNotEraseNewPendingRequest() async throws {
        let cache = store()
        let delayed = DelayedCreationServiceStub()
        let devices = try await delayed.devices()
        let original = CreationRequest(device: devices[0], workspaceID: "work", now: Date().addingTimeInterval(-60))
        try cache.saveCreation(SavedCreation(pending: original))
        let oldScreen = NewConversationModel(service: delayed, store: cache)
        let checking = Task { await oldScreen.refresh() }
        while delayed.continuation == nil { await Task.yield() }
        oldScreen.deactivate()
        let service = CreationServiceStub()
        let newScreen = NewConversationModel(service: service, store: cache)
        await newScreen.refresh()
        newScreen.clearAfterReview()
        await newScreen.create()
        let newID = try XCTUnwrap(newScreen.pending?.id)
        XCTAssertEqual(try cache.loadCreation()?.pending?.id, newID)
        delayed.continuation?.resume(returning: try CreationReceipt(["id": original.id, "status": "accepted", "result": ["sessionId": "old-chat"]]))
        await checking.value
        XCTAssertEqual(try cache.loadCreation()?.pending?.id, newID)
        XCTAssertNil(oldScreen.created)
        XCTAssertEqual(oldScreen.pending?.id, newID)
    }
}


extension NewConversationTests {
    private func device(_ id: String, online: Bool = true, workspaces: [String] = ["one", "two"]) throws -> CreationDevice {
        try CreationDevice(["id": id, "name": id, "online": online, "epoch": "epoch-2",
                            "workspaces": workspaces.map { ["id": $0, "title": $0] }])
    }
    func testSuccessfulCreationRemembersExactLocationAcrossRelaunchAndAccounts() async throws {
        let cache = store(); let service = CreationServiceStub()
        service.deviceRows = try [device("first"), device("second")]
        let model = NewConversationModel(service: service, store: cache)
        await model.refresh(); XCTAssertFalse(model.canCreate)
        model.deviceID = "second"; model.selectDevice(); model.workspaceID = "two"
        await model.create()
        XCTAssertNil(try cache.loadCreation()?.recentTarget)
        let request = try XCTUnwrap(model.pending)
        service.stored = try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "chat"]])
        await model.refresh()
        let target = CreationTarget(deviceID: "second", workspaceID: "two")
        XCTAssertEqual(try cache.loadCreation()?.recentTarget, target)
        let restored = NewConversationModel(service: service, store: store())
        XCTAssertFalse(restored.canCreate)
        await restored.refresh()
        XCTAssertEqual(restored.deviceID, "second"); XCTAssertEqual(restored.workspaceID, "two")
        XCTAssertTrue(restored.canCreate); XCTAssertEqual(service.submissions.count, 1)
        let other = ConversationStore(key: "another-account", root: root)
        XCTAssertNil(try other.loadCreation())
        try cache.invalidateAndClear()
        XCTAssertNil(try store().loadCreation())
    }
    func testUnavailableRecentLocationDoesNotFallBackToAnotherTarget() async throws {
        let cache = store(); let service = CreationServiceStub()
        try cache.saveCreation(SavedCreation(pending: nil, recentTarget: .init(deviceID: "remembered", workspaceID: "two")))
        for devices in [try [device("remembered", online: false), device("other")],
                        try [device("other")],
                        try [device("remembered", workspaces: ["one"]), device("other")]] {
            service.deviceRows = devices
            let model = NewConversationModel(service: service, store: cache)
            await model.refresh(); await model.create()
            XCTAssertFalse(model.canCreate)
            XCTAssertTrue(service.submissions.isEmpty)
        }
    }
    func testRefreshPreservesManualChoiceAndDeviceSwitchResetsWorkspace() async throws {
        let cache = store(); let service = CreationServiceStub()
        service.deviceRows = try [device("remembered"), device("other")]
        try cache.saveCreation(SavedCreation(pending: nil, recentTarget: .init(deviceID: "remembered", workspaceID: "two")))
        let model = NewConversationModel(service: service, store: cache)
        await model.refresh()
        model.workspaceID = "one"; await model.refresh()
        XCTAssertEqual(model.workspaceID, "one")
        model.deviceID = "other"; model.selectDevice()
        XCTAssertEqual(model.workspaceID, ""); XCTAssertFalse(model.canCreate)
        model.workspaceID = "two"
        service.deviceRows = try [device("remembered"), device("other", workspaces: ["one"])]
        await model.refresh()
        XCTAssertEqual(model.workspaceID, ""); XCTAssertFalse(model.canCreate)
        service.deviceRows = try [device("remembered")]
        await model.refresh()
        XCTAssertEqual(model.deviceID, ""); XCTAssertFalse(model.canCreate)
    }
    func testCreationFeedbackDistinguishesRunningFailureAndUncertainReceipts() async throws {
        let service = CreationServiceStub(), cache = store()
        let model = NewConversationModel(service: service, store: cache)
        await model.refresh(); await model.create()
        let request = try XCTUnwrap(model.pending)
        XCTAssertEqual(model.creationStatusStyle, .running)
        for (status, style) in [("claimed", StatusStyle.running), ("uncertain", .waiting), ("rejected", .failed), ("expired", .failed)] {
            service.stored = try CreationReceipt(["id": request.id, "status": status])
            await model.refresh()
            XCTAssertEqual(model.creationStatusStyle, style)
            XCTAssertEqual(model.pending?.id, request.id)
            XCTAssertFalse(model.canCreate)
            XCTAssertEqual(model.canClear, ["uncertain", "rejected", "expired"].contains(status))
        }
    }
    func testClearingRejectedCreationPreservesPreviousLocationAndLegacyCacheDecodes() async throws {
        let cache = store(); let service = CreationServiceStub()
        let target = CreationTarget(deviceID: "device", workspaceID: "work")
        try cache.saveCreation(SavedCreation(pending: nil, recentTarget: target))
        let model = NewConversationModel(service: service, store: cache)
        await model.refresh(); await model.create()
        let request = try XCTUnwrap(model.pending)
        service.stored = try CreationReceipt(["id": request.id, "status": "rejected"])
        await model.refresh(); model.clearAfterReview()
        XCTAssertEqual(try cache.loadCreation()?.recentTarget, target)
        XCTAssertNil(try cache.loadCreation()?.pending)
        XCTAssertNil(try JSONDecoder().decode(SavedCreation.self, from: Data("{}".utf8)).recentTarget)
    }
    func testTransferredCreationRestoresItsSourceInAnyCreationEntryAndLinksOnlyOnce() async throws {
        let service = CreationServiceStub(), cache = store()
        let first = NewConversationModel(service: service, store: cache, sourceChatID: "phone-chat")
        await first.refresh(); first.chooseWorkspace("work"); first.updateDraft("Generate a report"); await first.create()
        let request = first.pending!; XCTAssertEqual(try cache.loadCreation()?.pendingSourceChatID, "phone-chat")
        service.stored = try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "desktop-result"]])
        // Recovery uses the ordinary desktop entry: it deliberately has no sourceChatID argument.
        let reopened = ConversationStore(key: "account", root: root), recovered = NewConversationModel(service: service, store: reopened)
        await recovered.refresh(); XCTAssertEqual(recovered.created?.id, "desktop-result")
        let links = try reopened.loadResultLinks(); XCTAssertEqual(links.count, 1); XCTAssertEqual(links[0].chatID, "phone-chat"); XCTAssertFalse(links[0].confirmed)
        XCTAssertNil(try reopened.loadCreation()?.pendingSourceChatID); XCTAssertNil(try reopened.loadCreation()?.pending)
        let revision = reopened.resultLinksRevision; await recovered.refresh(); XCTAssertEqual(try reopened.loadResultLinks().count, 1); XCTAssertEqual(reopened.resultLinksRevision, revision)
        service.stored = nil
        try reopened.saveResultTransferSource("stale-legacy-chat")
        let ordinary = NewConversationModel(service: service, store: reopened); await ordinary.refresh(); ordinary.chooseWorkspace("work"); await ordinary.create()
        service.stored = try CreationReceipt(["id": ordinary.pending!.id, "status": "accepted", "result": ["sessionId": "ordinary-task"]])
        await ordinary.refresh(); XCTAssertEqual(ordinary.created?.id, "ordinary-task"); XCTAssertEqual(try reopened.loadResultLinks().count, 1)
    }
    func testCancelledTransferredCreationClearsSourceAndDoesNotLink() async throws {
        let service = CreationServiceStub(), cache = store(), model = NewConversationModel(service: service, store: cache, sourceChatID: "phone-chat")
        await model.refresh(); model.chooseWorkspace("work"); await model.create()
        service.stored = try CreationReceipt(["id": model.pending!.id, "status": "rejected"]); await model.refresh(); model.clearAfterReview()
        XCTAssertNil(try cache.loadCreation()?.pendingSourceChatID); XCTAssertTrue(try cache.loadResultLinks().isEmpty)
        let legacy = try JSONDecoder().decode(SavedCreation.self, from: Data("{\"pending\":null}".utf8)); XCTAssertNil(legacy.pendingSourceChatID)
    }

}
