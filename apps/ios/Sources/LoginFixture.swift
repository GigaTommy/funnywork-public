#if DEBUG
import SwiftUI

/// Isolated authentication fixture: never reads Keychain or sends network requests.
@MainActor struct LoginFixtureScreen: View {
    @StateObject private var account: Account
    init() {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("LoginUITests")
        if ProcessInfo.processInfo.arguments.contains("--ui-test-reset") { try? FileManager.default.removeItem(at: root) }
        _account = StateObject(wrappedValue: Account(loadCredentials: { nil }, saveCredentials: { _ in }, clearCredentials: {}, storeRoot: root,
            request: { _, path, body in
                if path == "login" {
                    let name = body?["username"] as? String ?? ""
                    try await Task.sleep(for: .milliseconds(name == "slow" ? 2500 : 200))
                    if name == "invalid" { throw ClientError(message: String(localized: "登录失败，请检查账号和密码。")) }
                    if name == "offline" { throw URLError(.notConnectedToInternet) }
                    return ["token": "local-preview"]
                }
                if path == "session" { return ["principalId": "login-preview", "tenant": "preview", "nickname": "Mina"] }
                return [:] as [String: String]
            }))
    }
    var body: some View {
        Group {
            if account.credentials != nil {
                ConversationListView(service: DirectoryFixtureService(), store: account.store, name: account.name, initialSidebarOpen: true,
                    logout: { Task { await account.logout() } }) { row in Text(row.title) }
            } else { LoginView() }
        }.environmentObject(account)
    }
}
#endif
