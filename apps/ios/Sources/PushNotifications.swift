import SwiftUI
import UserNotifications
import Security
import CryptoKit

struct PushInstallation: Codable, Equatable {
    let id: String
    let secret: String
    var revision: Int64
    static func create() throws -> Self {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw ClientError.invalidResponse }
        return Self(id: UUID().uuidString, secret: bytes.map { String(format: "%02x", $0) }.joined(), revision: 0)
    }
}
@MainActor final class PushInstallationStore {
    private let load: () throws -> PushInstallation?
    private let save: (PushInstallation) throws -> Void
    init(load: (() throws -> PushInstallation?)? = nil,
         save: ((PushInstallation) throws -> Void)? = nil) { self.load = load ?? Self.readKeychain; self.save = save ?? Self.writeKeychain }
    func next() throws -> PushInstallation {
        var value = try load() ?? PushInstallation.create()
        guard value.revision >= 0, value.revision < 9_007_199_254_740_991 else { throw ClientError.invalidResponse }
        value.revision += 1
        // Persist before sending; a delayed request keeps its original revision.
        try save(value)
        return value
    }
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.funnyworker.ios.push", kSecAttrAccount as String: "installation"] }
    static func readKeychain() throws -> PushInstallation? {
        var request = query; request[kSecReturnData as String] = true
        var result: CFTypeRef?; let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw ClientError(message: String(localized: "无法安全保存提醒设置，请重试。")) }
        let value = try JSONDecoder().decode(PushInstallation.self, from: data)
        guard PushReceipt.validID(value.id), value.secret.count == 64, value.secret.allSatisfy({ $0.isHexDigit && $0.isASCII }) else { throw ClientError.invalidResponse }
        return value
    }
    static func writeKeychain(_ value: PushInstallation) throws {
        let attributes: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(value), kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw ClientError(message: String(localized: "无法安全保存提醒设置，请重试。")) }
    }
}
struct PushPreferences: Codable, Equatable {
    var approvals = false
    var questions = false
    var turnEnded = false
    var enabled: Bool { approvals || questions || turnEnded }
}
struct PushBinding: Codable, Equatable {
    let id: String
    let sessionKey: String
    let expiresAt: Date
}
struct PushReceipt: Equatable {
    let sessionID: String
    let bindingID: String
    let kind: String
    static func validID(_ value: String) -> Bool { !value.isEmpty && value.count <= 64 && value.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") } }
    init?(_ row: [AnyHashable: Any]) {
        guard let version = row["version"] as? Int, version == 1,
              let session = row["sessionId"] as? String, Self.validID(session),
              let binding = row["bindingId"] as? String, Self.validID(binding),
              let kind = row["kind"] as? String, ["approval", "question", "turn-ended"].contains(kind),
              let notification = row["notificationId"] as? String, Self.validID(notification) else { return nil }
        sessionID = session; bindingID = binding; self.kind = kind
    }
}
@MainActor protocol PushService {
    func available() async throws -> Bool
    func register(_ installation: PushInstallation, token: String, preferences: PushPreferences) async throws -> (String, Date, Bool)
    func remove(_ installation: PushInstallation) async throws
    func conversation(_ id: String) async throws -> Conversation
}
@MainActor struct HTTPPushService: PushService {
    let api: API
    func available() async throws -> Bool {
        guard let response = try await api.request("push") as? [String: Any], let available = response["available"] as? Bool else { throw ClientError.invalidResponse }
        return available
    }
    func register(_ installation: PushInstallation, token: String, preferences: PushPreferences) async throws -> (String, Date, Bool) {
        // The signed entitlement selects APNs; a Release development build may still use sandbox.
        let environment = PushEnvironment.current
        let response = try await api.request("push/installations/\(installation.id)", body: ["secret": installation.secret, "revision": installation.revision, "token": token, "environment": environment, "locale": Locale.preferredLanguages.first?.hasPrefix("zh") == true ? "zh-Hans" : "en", "approvals": preferences.approvals, "questions": preferences.questions, "turnEnded": preferences.turnEnded], method: "PUT")
        guard let row = response as? [String: Any], let binding = row["bindingId"] as? String, PushReceipt.validID(binding),
              let expiry = row["expiresAt"] as? NSNumber, expiry.doubleValue.isFinite, expiry.doubleValue > Date().timeIntervalSince1970 * 1000,
              let available = row["available"] as? Bool else { throw ClientError.invalidResponse }
        return (binding, Date(timeIntervalSince1970: expiry.doubleValue / 1000), available)
    }
    func remove(_ installation: PushInstallation) async throws {
        _ = try await api.request("push/installations/\(installation.id)", method: "DELETE", headers: ["X-Installation-Secret": installation.secret, "X-Installation-Revision": String(installation.revision)])
    }
    func conversation(_ id: String) async throws -> Conversation {
        guard PushReceipt.validID(id), let row = try await api.request("sync/sessions/\(id)") as? [String: Any], let value = Conversation(row), value.id == id else { throw ClientError.invalidResponse }
        return value
    }
}
enum PushEnvironment {
    static var current: String { Bundle.main.object(forInfoDictionaryKey: "PushEnvironment") as? String == "production" ? "production" : "sandbox" }
}
@MainActor protocol PushPermission {
    func status() async -> UNAuthorizationStatus
    func request() async throws -> Bool
    func register()
    func clearDelivered()
}
@MainActor struct SystemPushPermission: PushPermission {
    func status() async -> UNAuthorizationStatus { await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
    func request() async throws -> Bool { try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
    func register() { UIApplication.shared.registerForRemoteNotifications() }
    func clearDelivered() { UNUserNotificationCenter.current().removeAllDeliveredNotifications() }
}
@MainActor final class PushNotifications: ObservableObject {
    static let shared = PushNotifications()
    @Published private(set) var preferences = PushPreferences()
    @Published private(set) var status: UNAuthorizationStatus = .notDetermined
    @Published private(set) var busy = false
    @Published private(set) var connected = false
    @Published private(set) var error: String?
    @Published var openedConversation: Conversation?
    private var credentials: Credentials?
    private var generation = UUID()
    private var token: String?
    private var binding: PushBinding?
    private var pendingReceipt: PushReceipt?
    private let installation: PushInstallationStore
    private let permission: any PushPermission
    private let defaults: UserDefaults
    private let service: (Credentials) -> any PushService
    var authFailure: (Error, Credentials) -> Void = { _, _ in }
    init(installation: PushInstallationStore? = nil, permission: (any PushPermission)? = nil, defaults: UserDefaults = .standard, service: @escaping (Credentials) -> any PushService = { HTTPPushService(api: API($0)) }) {
        self.installation = installation ?? PushInstallationStore(); self.permission = permission ?? SystemPushPermission(); self.defaults = defaults; self.service = service
    }
    private func accountKey(_ value: Credentials) -> String? {
        guard let tenant = value.tenant, let principal = value.principalID else { return nil }
        return "push." + ConversationStore.accountKey(server: value.server, tenant: tenant, principal: principal)
    }
    private func sessionKey(_ value: Credentials) -> String { SHA256.hash(data: Data((value.server.absoluteString + "/" + value.token).utf8)).map { String(format: "%02x", $0) }.joined() }
    func activate(_ value: Credentials?) {
        guard value != credentials else { return }
        generation = UUID(); busy = false; connected = false; error = nil; openedConversation = nil; binding = nil
        if credentials != nil { permission.clearDelivered(); pendingReceipt = nil }
        credentials = value; preferences = PushPreferences()
        if let value, let key = accountKey(value) {
            if let data = defaults.data(forKey: key + ".preferences"), let saved = try? JSONDecoder().decode(PushPreferences.self, from: data) { preferences = saved }
            if let data = defaults.data(forKey: key + ".binding"), let saved = try? JSONDecoder().decode(PushBinding.self, from: data), saved.sessionKey == sessionKey(value), saved.expiresAt > Date() { binding = saved }
        }
        if value != nil, let receipt = pendingReceipt { pendingReceipt = nil; Task { await open(receipt) } }
    }
    func receive(_ receipt: PushReceipt) async {
        if credentials == nil { pendingReceipt = receipt; return }
        await open(receipt)
    }
    func receivedToken(_ data: Data) async {
        token = data.map { String(format: "%02x", $0) }.joined()
        await refresh()
    }
    func registrationFailed() { connected = false; error = String(localized: "无法开启提醒，请重试。") }
    func update(_ value: PushPreferences) async {
        guard let credentials, let key = accountKey(credentials), !busy else { return }
        preferences = value; defaults.set(try? JSONEncoder().encode(value), forKey: key + ".preferences")
        await refresh(requestPermission: value.enabled)
    }
    func refresh(requestPermission: Bool = false) async {
        guard let credentials, let key = accountKey(credentials), !busy else { return }
        let attempt = generation; let requested = preferences; let originalToken = token; busy = true; error = nil
        defer {
            if generation == attempt {
                busy = false
                if token != originalToken && preferences.enabled { Task { await refresh() } }
            }
        }
        do {
            let current = await permission.status()
            guard generation == attempt else { return }; status = current
            if !requested.enabled || current == .denied {
                binding = nil; connected = false; defaults.removeObject(forKey: key + ".binding")
                try await service(credentials).remove(installation.next())
                return
            }
            let available = try await service(credentials).available()
            guard generation == attempt else { return }
            guard available else { connected = false; error = String(localized: "提醒暂不可用，请稍后重试。"); return }
            if current == .notDetermined {
                guard requestPermission else { return }
                let allowed = try await permission.request()
                guard generation == attempt else { return }
                status = await permission.status()
                guard generation == attempt, allowed else { return }
            }
            guard status == .authorized || status == .provisional || status == .ephemeral else { return }
            permission.register()
            guard let token else { return }
            let next = try installation.next()
            let result = try await service(credentials).register(next, token: token, preferences: requested)
            guard generation == attempt, preferences == requested else { return }
            binding = PushBinding(id: result.0, sessionKey: sessionKey(credentials), expiresAt: result.1)
            connected = result.2
            defaults.set(try JSONEncoder().encode(binding), forKey: key + ".binding")
        } catch {
            guard generation == attempt else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
            connected = false; self.error = ClientError.displayMessage(for: error); authFailure(error, credentials)
        }
    }
    func open(_ receipt: PushReceipt) async {
        guard (receipt.kind == "approval" && preferences.approvals) || (receipt.kind == "question" && preferences.questions) || (receipt.kind == "turn-ended" && preferences.turnEnded) else { return }
        guard let credentials, let binding, binding.id == receipt.bindingID, binding.sessionKey == sessionKey(credentials), binding.expiresAt > Date() else { return }
        let attempt = generation
        do {
            let row = try await service(credentials).conversation(receipt.sessionID)
            guard generation == attempt, self.binding == binding else { return }
            openedConversation = row
        } catch {
            guard generation == attempt else { return }
            self.error = ClientError.displayMessage(for: error); authFailure(error, credentials)
        }
    }
}
@MainActor final class PushAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self; return true
    }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) { Task { await PushNotifications.shared.receivedToken(deviceToken) } }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) { PushNotifications.shared.registrationFailed() }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let receipt = response.actionIdentifier == UNNotificationDefaultActionIdentifier ? PushReceipt(response.notification.request.content.userInfo) : nil
        completionHandler()
        Task { @MainActor in if let receipt { await PushNotifications.shared.receive(receipt) } }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { completionHandler([]) }
}
