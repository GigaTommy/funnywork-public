import XCTest
@testable import FunnyWork

@MainActor private final class DeviceServiceStub: NewConversationService, DeviceManagementService {
    var rows: [CreationDevice] = []
    var failure: Error?
    var wait = false
    var changes = 0
    var changeFailure: Error?
    var applyChange = true
    func setRevoked(_ revoked: Bool, deviceID: String, revision: Int64) async throws {
        changes += 1
        if applyChange {
            rows = [try CreationDevice(["id": deviceID, "name": "Mac", "online": false, "epoch": "", "workspaces": [], "revoked": revoked, "accessRevision": revision + 1])]
        }
        if let changeFailure { throw changeFailure }
    }
    var continuation: CheckedContinuation<[CreationDevice], Error>?
    func devices() async throws -> [CreationDevice] {
        if wait { return try await withCheckedThrowingContinuation { continuation = $0 } }
        if let failure { throw failure }; return rows
    }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt { throw ClientError.invalidResponse }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt { throw ClientError.invalidResponse }
}
@MainActor final class DeviceDirectoryTests: XCTestCase {
    func testManualRetryClearsErrorWhilePollingKeepsFailureVisible() async throws {
        let service = DeviceServiceStub(); service.failure = URLError(.timedOut)
        let model = DeviceDirectory(service: service)
        await model.refresh(); XCTAssertNotNil(model.error)
        service.wait = true
        let poll = Task { await model.refresh() }
        while service.continuation == nil { await Task.yield() }
        XCTAssertNotNil(model.error)
        service.continuation?.resume(throwing: URLError(.timedOut)); service.continuation = nil
        await poll.value
        let retry = Task { await model.refresh(userInitiated: true) }
        while service.continuation == nil { await Task.yield() }
        XCTAssertNil(model.error); XCTAssertTrue(model.loading); XCTAssertFalse(model.fresh)
        service.continuation?.resume(returning: []); service.continuation = nil
        await retry.value
        XCTAssertTrue(model.fresh); XCTAssertNil(model.error)
    }

    func testAccessChangeReadsBackLostResponseWithoutReplaying() async throws {
        let service = DeviceServiceStub()
        service.rows = [try CreationDevice(["id": "a", "name": "Mac", "online": false, "epoch": "", "workspaces": [], "accessRevision": 0])]
        let model = DeviceDirectory(service: service)
        await model.refresh()
        let old = model.devices[0]
        XCTAssertTrue(model.canManage(old))
        service.changeFailure = URLError(.networkConnectionLost)
        await model.changeAccess(old)
        XCTAssertTrue(model.devices[0].revoked); XCTAssertTrue(model.fresh)
        XCTAssertFalse(model.canManage(old)); XCTAssertEqual(service.changes, 1)
        await model.changeAccess(old); await model.refresh()
        XCTAssertEqual(service.changes, 1)
        service.changeFailure = nil
        await model.changeAccess(model.devices[0])
        XCTAssertFalse(model.devices[0].revoked); XCTAssertEqual(service.changes, 2)
    }
    func testUnconfirmedChangeDisablesActionsUntilRefresh() async throws {
        let service = DeviceServiceStub()
        service.rows = [try CreationDevice(["id": "a", "name": "Mac", "online": false, "epoch": "", "workspaces": [], "accessRevision": 0])]
        let model = DeviceDirectory(service: service)
        await model.refresh()
        service.failure = URLError(.notConnectedToInternet)
        await model.changeAccess(model.devices[0])
        XCTAssertFalse(model.fresh); XCTAssertFalse(model.changing); XCTAssertNotNil(model.error)
        XCTAssertFalse(model.canManage(model.devices[0]))
        service.failure = nil; await model.refresh()
        XCTAssertTrue(model.fresh); XCTAssertTrue(model.devices[0].revoked); XCTAssertEqual(service.changes, 1)
    }
    func testLatePollCannotOverwriteAccessChange() async throws {
        let service = DeviceServiceStub()
        service.rows = [try CreationDevice(["id": "a", "name": "Mac", "online": false, "epoch": "", "workspaces": [], "accessRevision": 0])]
        let model = DeviceDirectory(service: service)
        await model.refresh(); let original = service.rows
        service.wait = true
        let poll = Task { await model.refresh() }
        while service.continuation == nil { await Task.yield() }
        service.wait = false
        await model.changeAccess(model.devices[0])
        XCTAssertTrue(model.devices[0].revoked)
        service.continuation?.resume(returning: original); await poll.value
        XCTAssertTrue(model.devices[0].revoked); XCTAssertTrue(model.fresh); XCTAssertEqual(service.changes, 1)
    }
    func testLeavingDuringChangeIgnoresReadback() async throws {
        let service = DeviceServiceStub()
        service.rows = [try CreationDevice(["id": "a", "name": "Mac", "online": false, "epoch": "", "workspaces": [], "accessRevision": 0])]
        let model = DeviceDirectory(service: service)
        await model.refresh(); service.wait = true
        let action = Task { await model.changeAccess(model.devices[0]) }
        while service.continuation == nil { await Task.yield() }
        model.deactivate(); service.continuation?.resume(returning: service.rows)
        await action.value
        XCTAssertFalse(model.fresh); XCTAssertFalse(model.devices[0].revoked); XCTAssertFalse(model.changing)
    }
    func testMetadataAndLegacyResponse() throws {
        let base: [String: Any] = ["id": "a", "name": "Mac", "online": true, "epoch": "one", "workspaces": []]
        let old = try CreationDevice(base)
        XCTAssertNil(old.accessRevision); XCTAssertNil(old.lastSeen); XCTAssertNil(old.platform); XCTAssertFalse(old.revoked)
        let current = try CreationDevice(base.merging(["lastSeen": 123000, "platform": "darwin", "revoked": true]) { _, b in b })
        XCTAssertEqual(current.lastSeen, Date(timeIntervalSince1970: 123)); XCTAssertEqual(current.platform, "darwin")
        XCTAssertFalse(current.online); XCTAssertTrue(current.revoked)
    }
    func testMalformedAccessRevisionIsRejected() throws {
        let base: [String: Any] = ["id": "a", "name": "Mac", "online": false, "epoch": "", "workspaces": []]
        for value: Any in [true, -1, 0.5, "1", 9007199254740992] {
            XCTAssertThrowsError(try CreationDevice(base.merging(["accessRevision": value]) { _, b in b }))
        }
    }
    func testFailedRefreshKeepsDevicesButDisablesCreationAndCanRecover() async throws {
        let service = DeviceServiceStub()
        service.rows = [try CreationDevice(["id": "a", "name": "Mac", "online": true, "epoch": "one", "workspaces": [["id": "work", "title": "Work"]]])]
        let model = DeviceDirectory(service: service)
        await model.refresh(); XCTAssertTrue(model.canCreate(service.rows[0]))
        service.failure = URLError(.notConnectedToInternet)
        await model.refresh(); XCTAssertEqual(model.devices.count, 1); XCTAssertFalse(model.fresh); XCTAssertNotNil(model.error)
        XCTAssertFalse(model.canCreate(service.rows[0]))
        service.failure = nil; await model.refresh(); XCTAssertTrue(model.fresh); XCTAssertNil(model.error)
        model.deactivate(); XCTAssertFalse(model.canCreate(service.rows[0]))
    }
    func testLeavingPageIgnoresLateDeviceResponse() async throws {
        let service = DeviceServiceStub(); service.wait = true
        let model = DeviceDirectory(service: service)
        let refresh = Task { await model.refresh() }
        while service.continuation == nil { await Task.yield() }
        model.deactivate()
        service.continuation?.resume(returning: [])
        await refresh.value
        XCTAssertFalse(model.fresh); XCTAssertFalse(model.loading)
    }
    func testChosenDeviceOverridesRememberedLocationWithoutFallback() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = CreationServiceStub()
        service.deviceRows = try ["a", "b"].map { try CreationDevice(["id": $0, "name": $0, "online": true, "epoch": "one", "workspaces": [["id": "work", "title": "Work"]]]) }
        try store.saveCreation(SavedCreation(pending: nil, recentTarget: .init(deviceID: "a", workspaceID: "work")))
        let model = NewConversationModel(service: service, store: store, preferredDeviceID: "b")
        await model.refresh(); XCTAssertEqual(model.deviceID, "b"); XCTAssertTrue(model.canCreate)
        let missing = NewConversationModel(service: service, store: store, preferredDeviceID: "gone")
        await missing.refresh(); XCTAssertEqual(missing.deviceID, ""); XCTAssertFalse(missing.canCreate)
    }
}
