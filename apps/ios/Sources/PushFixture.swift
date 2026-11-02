#if DEBUG
import SwiftUI
import UserNotifications

@MainActor private final class PushFixturePermission: PushPermission {
    var value: UNAuthorizationStatus = ProcessInfo.processInfo.arguments.contains("--ui-test-push-denied") ? .denied : .notDetermined
    func status() async -> UNAuthorizationStatus { value }
    func request() async throws -> Bool { value = .authorized; return true }
    func register() {}
    func clearDelivered() {}
}
@MainActor private struct PushFixtureService: PushService {
    func available() async throws -> Bool {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-push-offline") { throw URLError(.notConnectedToInternet) }
        return true
    }
    func register(_ installation: PushInstallation, token: String, preferences: PushPreferences) async throws -> (String, Date, Bool) { ("fixture-binding", Date().addingTimeInterval(86400), true) }
    func remove(_ installation: PushInstallation) async throws {}
    func conversation(_ id: String) async throws -> Conversation { Conversation(["id": id, "title": "修改登录页", "device_name": "Mac mini", "online": true])! }
}
@MainActor enum PushFixture {
    static func make() -> PushNotifications {
        let defaults = UserDefaults(suiteName: "PushUITests")!
        if ProcessInfo.processInfo.arguments.contains("--ui-test-reset") { defaults.removePersistentDomain(forName: "PushUITests") }
        var installation: PushInstallation?
        let model = PushNotifications(installation: PushInstallationStore(load: { installation }, save: { installation = $0 }),
                                      permission: PushFixturePermission(), defaults: defaults, service: { _ in PushFixtureService() })
        model.activate(Credentials(server: URL(string: "https://example.com")!, token: "fixture", principalID: "fixture", tenant: "fixture"))
        return model
    }
}
struct PushFixtureTrigger: View {
    @ObservedObject var model: PushNotifications
    var body: some View {
        Button("打开提醒") {
            Task {
                if let receipt = PushReceipt(["version": 1, "sessionId": "a", "bindingId": "fixture-binding", "kind": "approval", "notificationId": "fixture-event"]) { await model.receive(receipt) }
            }
        }.accessibilityIdentifier("push-fixture-open")
            .task { await model.receivedToken(Data(repeating: 1, count: 32)) }
    }
}
#endif
