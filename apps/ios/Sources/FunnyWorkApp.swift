import SwiftUI
import OSLog

@MainActor final class Account: ObservableObject {
    @Published var credentials: Credentials?
    @Published var store: ConversationStore?
    @Published var name = ""
    @Published var avatarURL = ""
    @Published var error: String?
    @Published var busy = false
    private var generation = UUID()
    private let loadCredentials: () -> Credentials?
    private let saveCredentials: (Credentials) throws -> Void
    private let clearCredentials: () -> Void
    private let request: (Credentials, String, [String: Any]?) async throws -> Any
    private let storeRoot: URL?
    private let sessionChanged: (Credentials?) -> Void
    init(loadCredentials: @escaping () -> Credentials? = CredentialStore.load,
         saveCredentials: @escaping (Credentials) throws -> Void = CredentialStore.save,
         clearCredentials: @escaping () -> Void = CredentialStore.clear,
         storeRoot: URL? = nil,
         sessionChanged: ((Credentials?) -> Void)? = nil,
         request: @escaping (Credentials, String, [String: Any]?) async throws -> Any = { credentials, path, body in
             try await API(credentials).request(path, body: body)
         }) {
        self.loadCredentials = loadCredentials; self.saveCredentials = saveCredentials
        self.clearCredentials = clearCredentials; self.storeRoot = storeRoot; self.request = request; self.sessionChanged = sessionChanged ?? { PushNotifications.shared.activate($0) }
    }
    var api: API? { credentials.map(API.init) }
    private func use(_ saved: Credentials) {
        if let principal = saved.principalID, let tenant = saved.tenant {
            let key = ConversationStore.accountKey(server: saved.server, tenant: tenant, principal: principal)
            let previousKey = credentials.flatMap { value -> String? in
                guard let principal = value.principalID, let tenant = value.tenant else { return nil }
                return ConversationStore.accountKey(server: value.server, tenant: tenant, principal: principal)
            }
            if store == nil || previousKey != key {
                store?.invalidate()
                store = ConversationStore(key: key, root: storeRoot)
            }
        }
        credentials = saved; sessionChanged(saved)
    }
    func restore() async {
        guard credentials == nil, !busy, let saved = loadCredentials() else { return }
        generation = UUID(); let attempt = generation
        busy = true
        defer { if generation == attempt { busy = false } }
        if saved.principalID != nil { use(saved) }
        do { try await profile(using: saved, attempt: attempt) }
        catch { if generation == attempt { handle(error) } }
    }
    private func profile(using saved: Credentials, attempt: UUID, persist: Bool = true) async throws {
        let response = try await request(saved, "session", nil)
        try Task.checkCancellation()
        guard generation == attempt else { return }
        guard let row = response as? [String: Any],
              let principal = row["principalId"] as? String, !principal.isEmpty,
              let tenant = row["tenant"] as? String else { throw ClientError.invalidResponse }
        var confirmed = saved
        confirmed.principalID = principal; confirmed.tenant = tenant
        if let principal = saved.principalID, let tenant = saved.tenant,
           (principal != confirmed.principalID || tenant != confirmed.tenant) { throw AuthenticationError() }
        if persist { try saveCredentials(confirmed) }
        if credentials != confirmed { use(confirmed) }
        let picture = row["avatarUrl"] as? String ?? ""
        let displayName = (row["nickname"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? row["username"] as? String ?? ""
        if avatarURL != picture { avatarURL = picture }
        if name != displayName { name = displayName }
    }
    func refreshProfile() async {
        guard let saved = credentials else { return }
        let attempt = generation
        do { try await profile(using: saved, attempt: attempt, persist: false) }
        catch { if generation == attempt, error is AuthenticationError { handle(error, using: saved) } }
    }
    func saveAvatar(_ image: String) async throws {
        guard let saved = credentials else { throw ClientError.invalidResponse }
        let attempt = generation
        _ = try await request(saved, "avatar", ["image": image])
        guard generation == attempt, credentials == saved else { return }
        try await profile(using: saved, attempt: attempt)
    }
    func login(server: String, username: String, password: String) async {
        generation = UUID(); let attempt = generation
        busy = true; error = nil
        let started = ContinuousClock.now
        defer {
            if generation == attempt { busy = false }
            Logger(subsystem: "com.funnyworker.ios", category: "startup").info("login duration=\(String(describing: started.duration(to: .now)), privacy: .public) connected=\(self.credentials != nil)")
        }
        do {
            let url = try API.serverURL(server)
            let response = try await request(Credentials(server: url, token: ""), "login", ["username": username, "password": password, "device": "FunnyWork iOS", "client": "ios"])
            try Task.checkCancellation()
            guard generation == attempt else { return }
            guard let row = response as? [String: Any],
                  let token = row["token"] as? String, !token.isEmpty else { throw ClientError.invalidResponse }
            try await profile(using: Credentials(server: url, token: token), attempt: attempt)
        } catch { if generation == attempt { handle(error) } }
    }
    func handle(_ failure: Error, using source: Credentials) {
        guard credentials == source else { return }
        handle(failure)
    }
    func handle(_ failure: Error) {
        if failure is CancellationError || (failure as? URLError)?.code == .cancelled { return }
        if failure is AuthenticationError {
            generation = UUID(); busy = false
            store?.invalidate(); clearCredentials(); credentials = nil; store = nil; name = ""; avatarURL = ""; sessionChanged(nil)
        }
        error = ClientError.displayMessage(for: failure)
    }
    func logout() async {
        generation = UUID(); busy = false
        let old = credentials
        do { try store?.invalidateAndClear(); error = nil }
        catch { self.error = String(localized: "部分本地内容未能清理，请重新登录后重试退出。") }
        clearCredentials(); credentials = nil; store = nil; name = ""; avatarURL = ""; sessionChanged(nil)
        if let old { _ = try? await request(old, "logout", [:]) }
    }
}

enum Theme {
    static let warningStatus = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 244/255, green: 190/255, blue: 98/255, alpha: 1) : UIColor(red: 152/255, green: 92/255, blue: 10/255, alpha: 1) })
    static let errorStatus = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 255/255, green: 151/255, blue: 141/255, alpha: 1) : UIColor(red: 189/255, green: 58/255, blue: 50/255, alpha: 1) })
    static let onlineStatus = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 112/255, green: 206/255, blue: 145/255, alpha: 1) : UIColor(red: 35/255, green: 122/255, blue: 67/255, alpha: 1) })
    static let switchAccent = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.45, green: 0.43, blue: 0.40, alpha: 1) : UIColor(red: 0.161, green: 0.153, blue: 0.141, alpha: 1) })
    static let accent = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.933, green: 0.918, blue: 0.886, alpha: 1) : UIColor(red: 0.161, green: 0.153, blue: 0.141, alpha: 1) })
    static let onAccent = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.141, green: 0.133, blue: 0.122, alpha: 1) : .white })
    static let surface = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.125, green: 0.122, blue: 0.114, alpha: 1) : UIColor(red: 0.973, green: 0.965, blue: 0.945, alpha: 1) })
    static let input = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.165, green: 0.157, blue: 0.145, alpha: 1) : UIColor(red: 1, green: 0.992, blue: 0.98, alpha: 1) })
    static let line = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 69/255, green: 65/255, blue: 60/255, alpha: 1) : UIColor(red: 221/255, green: 217/255, blue: 210/255, alpha: 1) })
}

struct PrimaryActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Theme.onAccent)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Theme.accent, in: Capsule())
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.35)
    }
}

@main struct FunnyWorkApp: App {
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var push = PushNotifications.shared
    @AppStorage(AppAppearance.storageKey) private var appearance = AppAppearance.system.rawValue
    @StateObject private var account = Account()
    private var localProfileTest: Bool {
        #if DEBUG && targetEnvironment(simulator)
        return ProcessInfo.processInfo.environment["FUNNYWORK_LOCAL_PROFILE_TEST"] == "1"
        #else
        return false
        #endif
    }
    var body: some Scene {
        WindowGroup {
            Group {
                if localProfileTest { Color.clear } else {
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("--ui-test-workspace") {
                    WorkspaceFixtureScreen()
                } else if ProcessInfo.processInfo.arguments.contains("--ui-test-allowance") {
                    ModelAllowanceFixtureScreen()
                } else if ProcessInfo.processInfo.arguments.contains("--ui-test-device-link") {
                    DeviceLinkFixtureScreen()
                } else if ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-chat") {
                    MobileChatFixtureScreen()
                } else if ProcessInfo.processInfo.arguments.contains("--ui-test-conversation") {
                    ConversationFixtureScreen()
                } else if ProcessInfo.processInfo.arguments.contains("--ui-test-login") {
                    LoginFixtureScreen()
                } else if ProcessInfo.processInfo.arguments.contains("--ui-test-directory") {
                    DirectoryFixtureScreen()
                } else {
                    accountContent
                }
                #else
                accountContent
                #endif
                }
            }
            .environmentObject(account).tint(.primary)
            .preferredColorScheme((AppAppearance(rawValue: appearance) ?? .system).colorScheme)
            .task {
                #if DEBUG
                if localProfileTest { return }
                if ProcessInfo.processInfo.arguments.contains("--ui-test-workspace") || ProcessInfo.processInfo.arguments.contains("--ui-test-allowance") || ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-chat") || ProcessInfo.processInfo.arguments.contains("--ui-test-device-link") || ProcessInfo.processInfo.arguments.contains("--ui-test-conversation") || ProcessInfo.processInfo.arguments.contains("--ui-test-directory") || ProcessInfo.processInfo.arguments.contains("--ui-test-login") { return }
                #endif
                push.authFailure = { failure, credentials in account.handle(failure, using: credentials) }
                await account.restore()
                push.activate(account.credentials)
                await push.refresh()
            }
        }
    }
    private var accountContent: some View {
            Group {
                if account.credentials != nil && account.store != nil { MobileChatsView(push: push).id(account.credentials) }
                else { LoginView() }
            }.task(id: PushLifecycleKey(credentials: account.credentials, active: scenePhase == .active)) {
                guard scenePhase == .active else { return }
                var refreshCount = 0
                repeat {
                    if refreshCount % 20 == 0 {
                        push.activate(account.credentials)
                        await push.refresh()
                    }
                    refreshCount += 1
                    await account.refreshProfile()
                    do { try await Task.sleep(for: .seconds(15)) } catch { return }
                } while !Task.isCancelled
            }
    }
}

struct LoginView: View {
    @EnvironmentObject var account: Account
    @State private var server = "https://funnywork.funnypixelsapp.com"
    @State private var username = ""
    @State private var password = ""
    @State private var reveal = false
    @State private var showingLoginHelp = false
    private enum Field: Hashable { case username, password }
    @FocusState private var focusedField: Field?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(spacing: 8) {
                    Image("BrandMark")
                        .resizable().scaledToFit().frame(width: 44, height: 44)
                        .accessibilityHidden(true)
                    Text("FunnyWork").font(.subheadline.weight(.semibold))
                }.frame(maxWidth: .infinity).accessibilityElement(children: .combine).accessibilityIdentifier("login-brand")
                Text("开始吧").font(.title.bold()).frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 18) {
                  VStack(alignment: .leading, spacing: 8) {
                    Text("账号").font(.caption).foregroundStyle(.secondary)
                    TextField("输入账号", text: $username).accessibilityLabel("账号").accessibilityIdentifier("login-username").textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($focusedField, equals: .username).submitLabel(.next)
                        .onSubmit { focusedField = .password }
                        .padding(14).background(Theme.input, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(focusedField == .username ? Theme.accent : Theme.line))
                  }
                  VStack(alignment: .leading, spacing: 8) {
                    Text("密码").font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Group {
                            if reveal { TextField("输入密码", text: $password) }
                            else { SecureField("输入密码", text: $password) }
                        }.accessibilityLabel("密码").accessibilityIdentifier("login-password").textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focusedField, equals: .password).submitLabel(.go).onSubmit { login() }
                        Button { reveal.toggle() } label: { Image(systemName: reveal ? "eye.slash" : "eye")
                            .dynamicTypeSize(...DynamicTypeSize.xxxLarge).frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(reveal ? "隐藏密码" : "显示密码")
                    }.padding(.horizontal, 14).padding(.vertical, 2)
                        .background(Theme.input, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(focusedField == .password ? Theme.accent : Theme.line))
                  }
                }.disabled(account.busy)
                if let error = account.error { Text(error).foregroundStyle(.red).font(.callout).accessibilityIdentifier("login-error") }
                Button { login() } label: {
                    HStack { Spacer(); if account.busy { ProgressView() }; Text("登录"); Spacer() }.padding(8)
                }.buttonStyle(PrimaryActionStyle()).disabled(account.busy || username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty).accessibilityIdentifier("login-submit")
                Text(account.busy ? "正在连接…" : "安全连接")
                    .font(.footnote).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).accessibilityIdentifier("login-connection-status")
                Button("登录帮助") { focusedField = nil; showingLoginHelp = true }
                    .font(.footnote).frame(minHeight: 44).frame(maxWidth: .infinity)
                    .disabled(account.busy).accessibilityIdentifier("login-help")
            }.padding(28).frame(maxWidth: 416).frame(maxWidth: .infinity).padding(.top, 64)
        }.background(Theme.surface).scrollDismissesKeyboard(.interactively)
        .sheet(isPresented: $showingLoginHelp) {
            NavigationStack {
                Form {
                    Section {
                        Text("使用 FunnyWork 账号和密码登录，连接失败时请检查网络后重试。")
                    }
                    Section {
                        DisclosureGroup("自建服务配置") {
                            TextField("HTTPS", text: $server)
                                .accessibilityLabel("服务地址").accessibilityIdentifier("login-server")
                                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                                .submitLabel(.done)
                        }
                    } footer: {
                        Text("官方服务无需设置；仅在管理员提供自建服务地址时修改。")
                    }
                }
                .navigationTitle("登录帮助").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { showingLoginHelp = false }
                            .accessibilityIdentifier("login-help-done")
                    }
                }
            }
        }
    }
    func login() {
        let loginName = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !account.busy, !loginName.isEmpty, !password.isEmpty else { return }
        focusedField = nil
        Task { await account.login(server: server, username: loginName, password: password); if account.credentials != nil { password = "" } }
    }
}

struct ConversationsView: View {
    @EnvironmentObject var account: Account
    var push: PushNotifications? = nil
    var body: some View {
        if let api = account.api, let store = account.store {
            ConversationListView(service: HTTPConversationDirectory(api: api), store: store, createService: HTTPNewConversationService(api: api), searchService: HTTPHistorySearchService(api: api), push: push, allowanceService: HTTPModelAllowanceService(api: api), name: account.name,
                authFailure: { account.handle($0, using: api.credentials) },
                logout: { Task { await account.logout() } }) { row in
                    ConversationView(conversation: row, service: HTTPConversationService(api: api, sessionID: row.id), store: store,
                        attachmentService: HTTPAttachmentService(api: api, sessionID: row.id), uploadService: HTTPUploadService(api: api, sessionID: row.id),
                        authFailure: { account.handle($0, using: api.credentials) })
                }
        }
    }
}

private struct PushLifecycleKey: Hashable {
    let credentials: Credentials?
    let active: Bool
}
