import XCTest
@testable import FunnyWork

@MainActor private final class PresenceService: NewConversationService {
    var error: Error?
    var pending: CheckedContinuation<[CreationDevice], Error>?
    var delay = false
    var rows: [CreationDevice] = []
    func devices() async throws -> [CreationDevice] {
        if delay { return try await withCheckedThrowingContinuation { pending = $0 } }
        if let error { throw error }; return rows
    }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt { throw URLError(.unsupportedURL) }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt { throw URLError(.unsupportedURL) }
}
@MainActor final class RemoteConnectionMonitorTests: XCTestCase {
    private func device(revoked: Bool = false) throws -> CreationDevice {
        try CreationDevice(["id": "mac", "name": "Mac", "online": true, "epoch": "epoch", "workspaces": [[String: Any]](), "revoked": revoked])
    }
    func testNetworkFailureIsDistinctAndExplicitRetryRecovers() async throws {
        let service = PresenceService(); service.error = URLError(.timedOut)
        let model = RemoteConnectionMonitor(service: service, authFailure: { _ in XCTFail("Not an authentication failure") })
        await model.refresh(); XCTAssertEqual(model.state, .failed)
        service.error = nil; service.delay = true
        let retry = Task { await model.refresh(userInitiated: true) }
        while service.pending == nil { await Task.yield() }
        XCTAssertEqual(model.state, .checking)
        service.pending?.resume(returning: [try device()]); service.pending = nil
        await retry.value; XCTAssertEqual(model.state, .online)
    }
    func testPollingKeepsStableStatusAndDeactivationRejectsLateResponse() async throws {
        let service = PresenceService(); service.rows = [try device()]
        let model = RemoteConnectionMonitor(service: service, authFailure: { _ in })
        await model.refresh(); service.delay = true
        let poll = Task { await model.refresh() }
        while service.pending == nil { await Task.yield() }
        XCTAssertEqual(model.state, .online)
        model.deactivate()
        service.pending?.resume(returning: service.rows); service.pending = nil
        await poll.value; XCTAssertEqual(model.state, .unknown)
    }
    func testCreationPresenceDoesNotFlashDuringPollingButFailureDisablesCreation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let service = PresenceService(); service.rows = [try device()]
        let model = NewConversationModel(service: service, store: ConversationStore(key: "presence", root: root))
        await model.refresh(); XCTAssertTrue(model.presenceFresh)
        service.delay = true
        let poll = Task { await model.refresh() }
        while service.pending == nil { await Task.yield() }
        XCTAssertTrue(model.presenceFresh)
        XCTAssertFalse(model.ready)
        service.pending?.resume(throwing: URLError(.timedOut)); service.pending = nil
        await poll.value
        XCTAssertFalse(model.presenceFresh); XCTAssertFalse(model.ready)
        service.delay = false; await model.refresh()
        XCTAssertTrue(model.presenceFresh); XCTAssertTrue(model.ready)
        model.deactivate(); XCTAssertFalse(model.presenceFresh)
    }
    func testCancellationIsNotShownAsFailureAndStaleDevicesAreNotGreen() async throws {
        let service = PresenceService(); service.rows = [try device()]
        let model = RemoteConnectionMonitor(service: service, authFailure: { _ in })
        await model.refresh(); service.error = CancellationError()
        await model.refresh(); XCTAssertEqual(model.state, .online)
        service.error = URLError(.cancelled)
        await model.refresh(); XCTAssertEqual(model.state, .online)
        XCTAssertEqual(RemoteConnectionState.device(try device(), fresh: false), .unknown)
        XCTAssertEqual(RemoteConnectionState.device(try device(), fresh: false, checking: true), .checking)
        XCTAssertEqual(RemoteConnectionState.device(try device(), fresh: false, failed: true), .failed)
        XCTAssertEqual(RemoteConnectionState.device(try device(revoked: true), fresh: true), .disabled)
    }
}
