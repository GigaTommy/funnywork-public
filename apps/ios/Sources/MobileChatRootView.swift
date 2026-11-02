import SwiftUI

struct MobileChatRootView<Desktop: View>: View {
    @StateObject private var remote: RemoteConnectionMonitor
    @StateObject private var model: MobileChatModel
    @StateObject private var history: MobileChatHistoryModel
    @State private var searchLocation: MobileChatSearchLocation?
    @State private var showSearch = UUID()
    @State private var searchReturnChat: String?
    @State private var historyOpenError: String?
    @State private var renaming: MobileChatSummary?
    @FocusState private var historySearchFocused: Bool
    @State private var searching = false
    @State private var compactColumn: NavigationSplitViewColumn = .detail
    @State private var dismissal = UUID()
    @State private var scope: ConversationScope = .mobile
    @State private var desktopContent: Desktop?
    @State private var showingAccount = false
    @State private var showingTransfer = false
    @State private var transferDraft = ""
    @State private var transferSourceChatID: String?
    @State private var desktopTarget: Conversation?
    @State private var resultLinks: [MobileResultLink] = []
    @State private var resultLinkError: String?
    @State private var refreshingResultLinks = false
    @State private var refreshResultLinksAgain = false
    private let creationService: (any NewConversationService)?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    let allowanceService: (any ModelAllowanceService)?
    let authFailure: (Error) -> Void
    let name: String
    @ObservedObject var store: ConversationStore
    let push: PushNotifications?
    let logout: () -> Void
    let desktop: () -> Desktop
    init(service: MobileChatService, store: ConversationStore, name: String, remoteService: (any NewConversationService)? = nil, push: PushNotifications? = nil, allowanceService: (any ModelAllowanceService)? = nil,
         authFailure: @escaping (Error) -> Void = { _ in }, logout: @escaping () -> Void, @ViewBuilder desktop: @escaping () -> Desktop) {
        _scope = State(initialValue: (try? store.loadNavigation()).flatMap { ConversationScope(rawValue: $0.scope) } ?? .mobile)
        _remote = StateObject(wrappedValue: RemoteConnectionMonitor(service: remoteService, authFailure: authFailure))
        _model = StateObject(wrappedValue: MobileChatModel(service: service, store: store, authFailure: authFailure))
        _history = StateObject(wrappedValue: MobileChatHistoryModel(service: service, store: store, authFailure: authFailure))
        self.allowanceService = allowanceService; self.authFailure = authFailure
        self.name = name; self.store = store; self.push = push; self.logout = logout; self.desktop = desktop
        self.creationService = remoteService
    }
    private var draft: Binding<String> { Binding(get: { model.draft }, set: model.updateDraft) }
    private var selectedModel: Binding<String> { Binding(get: { model.modelID }, set: model.selectModel) }
    var body: some View {
        GeometryReader { geometry in
        ZStack(alignment: .topLeading) {
            mobileContent.frame(width: geometry.size.width, height: geometry.size.height).opacity(scope == .mobile ? 1 : 0)
                .allowsHitTesting(scope == .mobile).accessibilityHidden(scope != .mobile)
            if let desktopContent {
                desktopContent.environment(\.desktopTaskLocation, $desktopTarget).frame(width: geometry.size.width, height: geometry.size.height).opacity(scope == .desktop ? 1 : 0)
                    .allowsHitTesting(scope == .desktop).accessibilityHidden(scope != .desktop)
            }
        }.frame(width: geometry.size.width, height: geometry.size.height)
        }
        .environment(\.conversationScope, ConversationScopeNavigation(scope: scope, select: { value in
            scope = value
            if value == .desktop && remote.state == .failed { Task { await remote.refresh(userInitiated: true) } }
        }))
        .environment(\.remoteConnection, remote.state)
        .task(id: scenePhase) {
            guard scenePhase == .active else { remote.deactivate(); return }
            repeat {
                await remote.refresh()
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
            } while !Task.isCancelled
        }
        .onChange(of: scope) { _, value in
            do { var saved = try store.loadNavigation(); saved.scope = value.rawValue; try store.saveNavigation(saved) }
            catch { if error is AuthenticationError { authFailure(error) } }
        }
        .onAppear { history.onUpdated = model.applyHistoryUpdate; if desktopContent == nil { desktopContent = desktop() } }
        .task(id: HistoryRequest(query: history.query, archived: history.archived, active: scenePhase == .active && scope == .mobile)) {
            guard scenePhase == .active && scope == .mobile else { history.deactivate(); return }
            await history.refresh()
        }
        .onChange(of: model.chats) { _, value in history.receive(value) }
        .overlay { if let push { MobileChatPushBridge(push: push) { showingAccount = false; scope = .desktop } } }
        .sheet(isPresented: $showingAccount) { AccountSettingsView(name: name, store: store, push: push, allowanceService: allowanceService, authFailure: authFailure, logout: logout) }
        .sheet(item: $renaming) { chat in MobileChatRenameView(chat: chat, history: history) }
        .sheet(isPresented: $showingTransfer) {
            if let creationService {
                NewConversationView(service: creationService, store: store, initialDraft: transferDraft, sourceChatID: transferSourceChatID, authFailure: authFailure) { conversation in
                    do {
                        resultLinks = try store.loadResultLinks()
                        desktopTarget = conversation; scope = .desktop; Task { await refreshResultLinks() }
                    } catch { resultLinkError = ClientError.displayMessage(for: error) }
                }
            }
        }
        .task(id: model.snapshot?.chat.id) { await refreshResultLinks() }
        .onChange(of: store.resultLinksRevision) { _, _ in Task { await refreshResultLinks() } }
        .task(id: scenePhase) {
            guard scenePhase == .active else { model.deactivate(); return }
            await model.refresh(); await refreshResultLinks()
        }
        .onDisappear { model.deactivate() }
    }
    private var mobileContent: some View {
        ConversationNavigationShell(compactColumn: $compactColumn, initiallyOpen: false, dismissal: dismissal, contentScope: .mobile, presentation: showSearch,
            search: ConversationSidebarSearch(active: searching, activate: { searching = true; historySearchFocused = true },
                cancel: { historySearchFocused = false; history.query = ""; searching = false })) {
            ScrollViewReader { searchProxy in
            VStack(spacing: 0) {
                    if searching {
                        HStack(spacing: 8) {
                            TextField("搜索聊天", text: $history.query).textInputAutocapitalization(.never).autocorrectionDisabled()
                                .focused($historySearchFocused).submitLabel(.search).onSubmit { historySearchFocused = false }
                                .onAppear { historySearchFocused = true }.accessibilityIdentifier("mobile-history-search")
                            if !history.query.isEmpty {
                                Button { history.query = "" } label: { Image(systemName: "xmark.circle").font(.body).dynamicTypeSize(...DynamicTypeSize.xxxLarge).frame(width: 44, height: 44) }
                                    .buttonStyle(.plain).accessibilityLabel("清除筛选").accessibilityIdentifier("mobile-history-clear")
                            }
                        }.padding(.horizontal, 12).frame(minHeight: 44)
                            .background(Theme.input, in: RoundedRectangle(cornerRadius: 12))
                            .padding(.horizontal, 16).padding(.vertical, 8)
                    }
            List {
                Group {
                    if !searching {
                        Button { history.archived = false; history.query = ""; model.newChat(); dismissal = UUID(); compactColumn = .detail } label: { Label("新对话", systemImage: "square.and.pencil") }
                            .listRowSeparator(.hidden)
                            .accessibilityIdentifier("new-mobile-chat")
                        if history.supported {
                            Button { history.archived.toggle() } label: { Label(history.archived ? String(localized: "返回对话") : String(localized: "已归档"), systemImage: "archivebox") }
                                .listRowSeparator(.hidden)
                                .accessibilityIdentifier("mobile-history-archive-scope")
                        }
                    }
                    if searching && !history.supported && !history.loading {
                        Text("只搜索最近已加载的聊天标题。").font(.caption).foregroundStyle(.secondary)
                    }
                    if history.pending != nil {
                        VStack(alignment: .leading, spacing: 8) {
                            StatusIndicator(title: String(localized: "历史操作结果待确认"), style: .waiting).font(.callout)
                            Button("核对结果") { Task { await history.reconcile() } }.disabled(history.updating)
                                .accessibilityIdentifier("mobile-history-reconcile")
                        }
                    }
                    if let error = history.operationError { Text(error).font(.caption).foregroundStyle(.secondary) }
                    if let error = historyOpenError { Text(error).font(.caption).foregroundStyle(.secondary) }
                    if sidebarChats.isEmpty && !history.loading && history.error == nil {
                        Text(history.query.isEmpty ? (history.archived ? String(localized: "没有已归档的对话") : String(localized: "还没有对话")) : String(localized: "没有找到对话")).foregroundStyle(.secondary).listRowBackground(Color.clear)
                    }
                    ForEach(sidebarChats.isEmpty ? [] : (history.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? model.navigationGroups(rows: sidebarChats) : [MobileChatHistoryGroup(id: .earlier, chats: sidebarChats)])) { group in
                        Section {
                            ForEach(group.chats) { chat in
                                Button {
                                    historySearchFocused = false; historyOpenError = nil
                                    let searchQuery = history.query
                                    Task {
                                        await model.open(chat)
                                        guard !Task.isCancelled, scope == .mobile, history.query == searchQuery, model.saved.selected == chat.id else { return }
                                        guard model.snapshot?.chat.id == chat.id else { historyOpenError = model.error ?? String(localized: "无法打开对话，请重试。"); return }
                                        if !searchQuery.isEmpty {
                                            searchReturnChat = chat.id
                                            searchLocation = MobileChatSearchLocation(chatID: chat.id, turnID: chat.searchMatch?.turnID, field: chat.searchMatch?.field ?? "title", query: searchQuery)
                                        } else { searchLocation = nil }
                                        dismissal = UUID(); compactColumn = .detail
                                    }
                                } label: {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(verbatim: chat.title).foregroundStyle(.primary).lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                                            if !history.query.isEmpty, let match = chat.searchMatch {
                                                SearchMatchText(text: match.excerpt, query: history.query).font(.callout).foregroundStyle(.secondary)
                                                HStack { Text(match.label); Text(match.date, style: .date) }.font(.caption).foregroundStyle(.secondary)
                                            }
                                        }
                                        Spacer()
                                        if model.navigationNeedsReview(chat) { StatusIndicator(title: String(localized: "结果待确认"), style: .waiting).font(.caption) }
                                        else if model.saved.pendingChats[chat.id] != nil { StatusIndicator(title: String(localized: "进行中"), style: .running).font(.caption) }
                                        if model.saved.selected == chat.id { Image(systemName: "checkmark").foregroundStyle(.secondary).accessibilityLabel("当前对话") }
                                    }
                                }
                                .frame(minHeight: 44)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                                .id(chat.id).accessibilityIdentifier("mobile-chat-" + chat.id)
                                .contextMenu {
                                    if history.supported && chat.historyRevision != nil {
                                        Button("重命名", systemImage: "pencil") { renaming = chat }.disabled(!history.canUpdate)
                                        Button(chat.archived == true ? String(localized: "恢复对话") : String(localized: "归档"), systemImage: "archivebox") {
                                            Task { await history.change(chat, archived: chat.archived != true) }
                                        }.disabled(!history.canUpdate)
                                    }
                                }
                            }
                        } header: {
                            Text(history.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? group.title : (history.archived ? String(localized: "搜索已归档对话") : String(localized: "搜索结果")))
                                .font(.caption.weight(.medium)).textCase(nil)
                        }
                    }
                    if history.loading { ProgressView().listRowBackground(Color.clear) }
                    if let error = history.error {
                        Text(error).font(.caption).foregroundStyle(.secondary)
                        Button("重试") { Task { await history.retry() } }
                    } else if history.hasMore && !history.loading {
                        Button("加载更多聊天") { Task { await history.more() } }.accessibilityIdentifier("mobile-history-more")
                    }
                }.listRowBackground(Theme.surface).listRowSeparator(.hidden)
            }.listStyle(.plain).listSectionSpacing(8).font(.subheadline)
                .environment(\.defaultMinListRowHeight, 44)
                .listRowSeparator(.hidden).listRowBackground(Color.clear)
                .contentMargins(.top, 8, for: .scrollContent)
                .scrollContentBackground(.hidden).background(Theme.surface)
                .navigationTitle("FunnyWork")
                .refreshable { await history.refresh() }
                .safeAreaInset(edge: .bottom) {
                    Button { showingAccount = true } label: {
                        HStack(spacing: 10) {
                            AccountPicture(name: name)
                            Text(name.isEmpty ? String(localized: "账号") : name).lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                            Spacer(minLength: 8)
                        }.font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10).frame(minHeight: 56).contentShape(Rectangle())
                    }.buttonStyle(.plain).background(Theme.surface).accessibilityLabel("账号").accessibilityValue(name).accessibilityIdentifier("mobile-sidebar-account")
                }
                .onAppear {
                    if !history.query.isEmpty, let id = searchReturnChat, sidebarChats.contains(where: { $0.id == id }) {
                        Task { await Task.yield(); searchProxy.scrollTo(id, anchor: .center) }
                    }
                }
            }.background(Theme.surface)
            }
        } detail: {
            Group {
            if let snapshot = model.snapshot, model.saved.selected == snapshot.chat.id {
                chatContent(snapshot)
            } else {
                ChatHomeView(name: name, draft: draft, modelID: selectedModel, models: model.options, reasoningEffort: Binding(get: { model.reasoningEffort }, set: model.selectReasoningEffort), busy: model.activeBusy,
                    canSend: model.canSend, status: model.status, error: model.error, send: model.send,
                    refresh: { if model.activeBusy { model.retryPreparation() } else { Task { await model.refresh() } } },
                    edit: model.activeBusy && model.saved.pending?.executionRequested == false ? { Task { await model.editUnsent() } } : nil,
                    connectionIssue: model.connectionIssue)
            }
            }.safeAreaInset(edge: .top) {
                if model.busy && !model.activeBusy {
                    Button { Task { await model.openRunningChat() } } label: {
                        Label("查看运行中的聊天", systemImage: "clock")
                            .font(.footnote).padding(10).frame(maxWidth: .infinity)
                    }.buttonStyle(.plain).background(Theme.input).accessibilityIdentifier("open-running-chat")
                }
            }
        }
    }
    private func chatContent(_ snapshot: MobileChatSnapshot) -> some View {
        MobileChatConversationView(model: model, snapshot: snapshot, location: searchLocation?.chatID == snapshot.chat.id ? searchLocation : nil,
            returnToSearch: { searching = true; showSearch = UUID(); compactColumn = .sidebar }, transferToDesktop: creationService == nil ? nil : { beginTransfer(snapshot) },
            newChat: {
                history.archived = false; history.query = ""; searchLocation = nil; searchReturnChat = nil
                model.newChat(); dismissal = UUID(); compactColumn = .detail
            })
            .id(snapshot.chat.id)
            .safeAreaInset(edge: .top) {
                let links = resultLinks.filter { $0.chatID == snapshot.chat.id }
                if !links.isEmpty {
                    Menu("相关电脑任务") {
                        ForEach(links) { link in Button(link.conversation.title) { desktopTarget = link.conversation; scope = .desktop } }
                    }.font(.caption).padding(8)
                }
                if let error = resultLinkError { HStack { Text(error).font(.caption); Button("重试") { Task { await refreshResultLinks() } } }.padding(8) }
            }
    }
    private func beginTransfer(_ snapshot: MobileChatSnapshot) {
        do {
            if try store.loadCreation()?.pending != nil {
                resultLinkError = String(localized: "请先完成或取消尚未创建的电脑任务，再转交这段聊天。")
                return
            }
            transferSourceChatID = snapshot.chat.id
            let latest = snapshot.turns.last
            var parts = [String(localized: "请根据下面的聊天内容生成结果文件，并用 publish_result 发布：")]
            if let latest { parts.append(String((latest.text + "\n\n" + latest.reply).prefix(12000))) }
            if !model.draft.isEmpty { parts.append(model.draft) }
            transferDraft = parts.joined(separator: "\n\n")
            showingTransfer = true
        } catch { resultLinkError = ClientError.displayMessage(for: error) }
    }
    private func refreshResultLinks() async {
        guard !refreshingResultLinks else { refreshResultLinksAgain = true; return }
        refreshingResultLinks = true
        defer {
            refreshingResultLinks = false
            if refreshResultLinksAgain && !Task.isCancelled {
                refreshResultLinksAgain = false; Task { await refreshResultLinks() }
            }
        }
        do {
            var links = try store.loadResultLinks(); resultLinks = links
            guard let service = creationService as? HTTPNewConversationService else { return }
            try await MobileResultLinkSync.flush(store: store, service: HTTPMobileResultLinkService(api: service.api))
            links = try store.loadResultLinks()
            if let chatID = model.snapshot?.chat.id, let rows = try await service.api.request("chats/\(chatID)/desktop-tasks") as? [[String: Any]] {
                for row in rows {
                    guard let conversation = Conversation(row), !links.contains(where: { $0.chatID == chatID && $0.id == conversation.id }) else { continue }
                    links.append(MobileResultLink(chatID: chatID, conversation: conversation, confirmed: true))
                }
            }
            var latest = try store.loadResultLinks()
            for link in links where !latest.contains(where: { $0.chatID == link.chatID && $0.id == link.id }) { latest.append(link) }
            try store.saveResultLinks(latest); resultLinks = latest; resultLinkError = nil
        } catch { if !(error is CancellationError) { resultLinkError = ClientError.displayMessage(for: error); if error is AuthenticationError { authFailure(error) } } }
    }
    private var sidebarChats: [MobileChatSummary] {
        var rows = history.rows
        if !history.archived && history.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let known = Set(rows.map(\.id))
            rows += model.navigationChats.filter { model.saved.pendingChats[$0.id] != nil && !known.contains($0.id) }
        }
        return rows.sorted {
            let left = model.saved.pendingChats[$0.id] != nil, right = model.saved.pendingChats[$1.id] != nil
            if left != right { return left }
            if $0.updatedAt != $1.updatedAt { return ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
            return $0.id < $1.id
        }
    }
    private struct HistoryRequest: Equatable {
        let query: String; let archived: Bool; let active: Bool
    }

}

struct MobileChatsView: View {
    @EnvironmentObject var account: Account
    var push: PushNotifications? = nil
    var body: some View {
        if let api = account.api, let store = account.store {
            MobileChatRootView(service: HTTPMobileChatService(api: api), store: store, name: account.name, remoteService: HTTPNewConversationService(api: api), push: push, allowanceService: HTTPModelAllowanceService(api: api),
                authFailure: { account.handle($0, using: api.credentials) }, logout: { Task { await account.logout() } }) {
                ConversationsView(push: push)
            }
        }
    }
}

private struct MobileChatPushBridge: View {
    @ObservedObject var push: PushNotifications
    let open: () -> Void
    var body: some View {
        Color.clear.frame(width: 0, height: 0).onChange(of: push.openedConversation?.id, initial: true) { _, id in
            if id != nil { open() }
        }
    }
}
