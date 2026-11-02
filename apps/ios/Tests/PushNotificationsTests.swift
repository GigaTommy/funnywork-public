import XCTest
import UserNotifications
@testable import FunnyWork

@MainActor final class PushPermissionStub: PushPermission {
    var authorization: UNAuthorizationStatus = .authorized
    var requests = 0
    var registrations = 0
    var cleared = 0
    func status() async -> UNAuthorizationStatus { authorization }
    func request() async throws -> Bool { requests += 1; authorization = .authorized; return true }
    func register() { registrations += 1 }
    func clearDelivered() { cleared += 1 }
}
@MainActor final class PushServiceStub: PushService {
    var isAvailable = true
    var failure: Error?
    var registrations: [PushInstallation] = []
    var removals: [PushInstallation] = []
    var opened: [String] = []
    var binding = "binding"
    var continuation: CheckedContinuation<(String, Date, Bool), Error>?
    var suspend = false
    func available() async throws -> Bool { if let failure { throw failure }; return isAvailable }
    func register(_ installation: PushInstallation, token: String, preferences: PushPreferences) async throws -> (String, Date, Bool) {
        registrations.append(installation)
        if suspend { return try await withCheckedThrowingContinuation { continuation = $0 } }
        return (binding, Date().addingTimeInterval(86400), true)
    }
    func remove(_ installation: PushInstallation) async throws { removals.append(installation); if let failure { throw failure } }
    func conversation(_ id: String) async throws -> Conversation { opened.append(id); if let failure { throw failure }; return Conversation(["id": id, "title": "Current account"])! }
}
@MainActor final class PushNotificationsTests: XCTestCase {
    var defaults: UserDefaults!
    var saved: PushInstallation?
    var service: PushServiceStub!
    var permission: PushPermissionStub!
    var model: PushNotifications!
    let credentials = Credentials(server: URL(string: "https://example.com")!, token: "a", principalID: "user-a", tenant: "tenant")
    override func setUp() async throws {
        defaults = UserDefaults(suiteName: UUID().uuidString)!
        service = PushServiceStub(); permission = PushPermissionStub(); saved = nil
        model = makeModel(); model.activate(credentials)
        await model.receivedToken(Data(repeating: 1, count: 32))
    }
    func makeModel() -> PushNotifications {
        PushNotifications(installation: PushInstallationStore(load: { self.saved }, save: { self.saved = $0 }), permission: permission, defaults: defaults, service: { _ in self.service })
    }
    func receipt(binding: String = "binding", kind: String = "approval") -> PushReceipt {
        PushReceipt(["version": 1, "sessionId": "chat", "bindingId": binding, "kind": kind, "notificationId": "event"])!
    }
    func testOptInRegistersAndRenewalPreservesInstallationWithIncreasingRevision() async {
        XCTAssertTrue(service.registrations.isEmpty); XCTAssertEqual(permission.requests, 0)
        await model.update(PushPreferences(approvals: true))
        XCTAssertTrue(model.connected); XCTAssertEqual(service.registrations.count, 1)
        let first = service.registrations[0]
        await model.refresh()
        XCTAssertEqual(service.registrations.last?.id, first.id)
        XCTAssertEqual(service.registrations.last?.secret, first.secret)
        XCTAssertGreaterThan(service.registrations.last!.revision, first.revision)
        await model.open(receipt()); XCTAssertEqual(model.openedConversation?.id, "chat")
    }
    func testAllOffAndDeniedRemoveSubscriptionAndDiscardClicks() async {
        await model.update(PushPreferences(approvals: true))
        await model.update(PushPreferences())
        XCTAssertFalse(model.connected); await model.open(receipt()); XCTAssertTrue(service.opened.isEmpty)
        permission.authorization = .denied
        await model.update(PushPreferences(approvals: true))
        XCTAssertFalse(model.connected); XCTAssertEqual(model.status, .denied)
        XCTAssertEqual(permission.requests, 0)
        XCTAssertGreaterThan(service.removals.count, 1)
    }
    func testSystemPermissionOnlyRequestedByExplicitOptIn() async {
        permission.authorization = .notDetermined
        await model.refresh(); XCTAssertEqual(permission.requests, 0)
        await model.update(PushPreferences(questions: true))
        XCTAssertEqual(permission.requests, 1); XCTAssertTrue(model.connected)
    }
    func testAccountSwitchDropsOldBindingAndKeepsPreferencesSeparate() async {
        await model.update(PushPreferences(approvals: true))
        var other = credentials; other.principalID = "user-b"; other = Credentials(server: other.server, token: "b", principalID: other.principalID, tenant: other.tenant)
        model.activate(other)
        XCTAssertFalse(model.preferences.enabled); XCTAssertFalse(model.connected)
        await model.open(receipt()); XCTAssertTrue(service.opened.isEmpty)
        model.activate(credentials); XCTAssertTrue(model.preferences.approvals)
        await model.open(receipt(binding: "wrong")); XCTAssertTrue(service.opened.isEmpty)
        await model.open(receipt(kind: "question")); XCTAssertTrue(service.opened.isEmpty)
    }
    func testLogoutDiscardsRoutingAndNewLoginCannotReuseOldBinding() async {
        await model.update(PushPreferences(approvals: true)); model.activate(nil)
        await model.open(receipt()); XCTAssertTrue(service.opened.isEmpty)
        let fresh = Credentials(server: credentials.server, token: "new-login", principalID: credentials.principalID, tenant: credentials.tenant)
        model.activate(fresh); await model.open(receipt()); XCTAssertTrue(service.opened.isEmpty)
        XCTAssertEqual(permission.cleared, 1)
    }
    func testLateRegistrationResponseCannotChangeNewAccountState() async {
        service.suspend = true
        let old = Task { await model.update(PushPreferences(approvals: true)) }
        while service.continuation == nil { await Task.yield() }
        model.activate(Credentials(server: credentials.server, token: "b", principalID: "user-b", tenant: "tenant"))
        service.continuation?.resume(returning: ("old-binding", Date().addingTimeInterval(86400), true)); await old.value
        XCTAssertFalse(model.connected); XCTAssertFalse(model.preferences.enabled)
        await model.open(receipt(binding: "old-binding")); XCTAssertTrue(service.opened.isEmpty)
    }
    func testFailureRemainsRetryableAndProviderUnavailableDoesNotAskPermission() async {
        service.failure = URLError(.notConnectedToInternet)
        await model.update(PushPreferences(approvals: true))
        XCTAssertNotNil(model.error); XCTAssertFalse(model.connected)
        service.failure = nil; await model.refresh(); XCTAssertNil(model.error); XCTAssertTrue(model.connected)
        permission.authorization = .notDetermined; service.isAvailable = false
        await model.refresh(requestPermission: true); XCTAssertEqual(permission.requests, 0); XCTAssertNotNil(model.error)
    }
    func testReceiptValidationRejectsUnexpectedVersionAndPathCharacters() {
        XCTAssertNil(PushReceipt(["version": 2, "sessionId": "chat", "bindingId": "binding", "kind": "approval", "notificationId": "event"]))
        XCTAssertNil(PushReceipt(["version": 1, "sessionId": "../other", "bindingId": "binding", "kind": "approval", "notificationId": "event"]))
        XCTAssertNil(PushReceipt(["version": 1, "sessionId": "chat", "bindingId": "binding", "kind": "unknown", "notificationId": "event"]))
    }
    func testInstallationStorageFailureCannotAllocateAnUnpersistedRevision() throws {
        let store = PushInstallationStore(load: { self.saved }, save: { _ in throw ClientError.invalidResponse })
        XCTAssertThrowsError(try store.next())
        XCTAssertEqual(saved?.revision, service.removals.last?.revision)
    }
    func testColdStartReceiptWaitsForRestoredMatchingSession() async {
        await model.update(PushPreferences(approvals: true))
        let restored = makeModel()
        await restored.receive(receipt())
        XCTAssertNil(restored.openedConversation)
        restored.activate(credentials)
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(restored.openedConversation?.id, "chat")
    }
    func testTokenChangeDuringRegistrationSchedulesAnotherRegistration() async {
        service.suspend = true
        let first = Task { await model.update(PushPreferences(approvals: true)) }
        while service.continuation == nil { await Task.yield() }
        await model.receivedToken(Data(repeating: 2, count: 32))
        service.suspend = false
        service.continuation?.resume(returning: ("binding", Date().addingTimeInterval(86400), true))
        await first.value
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(service.registrations.count, 2)
        XCTAssertGreaterThan(service.registrations[1].revision, service.registrations[0].revision)
    }

}
