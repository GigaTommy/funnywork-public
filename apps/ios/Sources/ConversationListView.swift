import SwiftUI

struct ConversationListView<Destination: View>: View {
    @Environment(\.desktopTaskLocation) private var externalDesktopTask
    @StateObject private var directory: ConversationDirectory
    @State private var showingSearch = false
    @State private var searching = false
    @FocusState private var sidebarSearchFocused: Bool
    private let push: PushNotifications?
    @State private var searchedResult: HistorySearchResult?
    @State private var messageLocation: ConversationMessageLocation?
    private let searchService: (any HistorySearchService)?
    @State private var showingAccount = false
    @State private var showingCreation = false
    @State private var showingDevices = false
    @State private var creationDeviceID: String?
    @State private var createAfterDevices = false
    @State private var renamingConversation: Conversation?
    @State private var editedTitle = ""
    @State private var showingRename = false
    @State private var selectedID: String?
    @State private var selectedConversation: Conversation?
    @State private var compactColumn: NavigationSplitViewColumn = .sidebar
    @State private var createdConversation: Conversation?
    @State private var sidebarDismissal = UUID()
    private let createService: NewConversationService?
    private let store: ConversationStore?
    private let allowanceService: (any ModelAllowanceService)?
    private let authFailure: (Error) -> Void
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.conversationScope) private var scopeNavigation
    private var directoryActive: Bool { scenePhase == .active && (scopeNavigation == nil || scopeNavigation?.scope == .desktop) }
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var usesDrawer: Bool { UIDevice.current.userInterfaceIdiom == .phone || sizeClass == .compact }
    private let initialSidebarOpen: Bool
    let name: String
    let logout: () -> Void
    let destination: (Conversation) -> Destination
    init(service: ConversationDirectoryService, store: ConversationStore? = nil, createService: NewConversationService? = nil, searchService: (any HistorySearchService)? = nil, push: PushNotifications? = nil, allowanceService: (any ModelAllowanceService)? = nil, name: String, initialSidebarOpen: Bool = false, authFailure: @escaping (Error) -> Void = { _ in }, logout: @escaping () -> Void, @ViewBuilder destination: @escaping (Conversation) -> Destination) {
        if let store, let id = (try? store.loadNavigation())?.desktopID,
           let row = (try? store.loadDirectory())?.rows.first(where: { $0.id == id && $0.archived != true }) {
            _selectedID = State(initialValue: id)
            _selectedConversation = State(initialValue: row)
            _compactColumn = State(initialValue: .detail)
        }
        _directory = StateObject(wrappedValue: ConversationDirectory(service: service, store: store, authFailure: authFailure))
        self.allowanceService = allowanceService
        self.searchService = searchService; self.push = push
        self.store = store; self.createService = createService; self.authFailure = authFailure
        self.name = name; self.logout = logout; self.destination = destination
        self.initialSidebarOpen = initialSidebarOpen
    }
    var body: some View {
        let groups = directory.groups
        ConversationNavigationShell(compactColumn: $compactColumn, initiallyOpen: initialSidebarOpen, dismissal: sidebarDismissal,
            search: ConversationSidebarSearch(active: searching, activate: { searching = true; sidebarSearchFocused = true },
                cancel: { sidebarSearchFocused = false; directory.search = ""; searching = false })) {
            VStack(spacing: 0) {
                if searching {
                    HStack(spacing: 10) {
                        TextField("搜索对话", text: $directory.search)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($sidebarSearchFocused).submitLabel(.search).onSubmit { sidebarSearchFocused = false }
                            .accessibilityIdentifier("sidebar-search")
                            .onAppear { sidebarSearchFocused = true }
                    }.padding(10).frame(minHeight: 44).background(Theme.input, in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 16).padding(.vertical, 8)
                }
            List(selection: $selectedID) {
                Section {
                    if !searching {
                        if createService != nil && store != nil {
                            Button { directory.showingArchived = false; createdConversation = nil; creationDeviceID = nil; showingCreation = true } label: { Label("新对话", systemImage: "square.and.pencil") }
                                .accessibilityIdentifier("new-conversation")
                        }
                        if createService != nil {
                            Button { createAfterDevices = false; showingDevices = true } label: { Label("设备", systemImage: "desktopcomputer") }
                        }
                        Button { directory.showingArchived.toggle() } label: {
                            Label(directory.showingArchived ? String(localized: "返回对话") : String(localized: "已归档"), systemImage: directory.showingArchived ? "bubble.left.and.bubble.right" : "archivebox")
                        }
                    }
                }.listRowBackground(Color.clear)
                if !usesDrawer { Section {
                    HStack {
                        Text(name.isEmpty ? String(localized: "HI，你好") : "HI，\(name)").font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        deviceFilter
                    }.listRowBackground(Color.clear).listRowSeparator(.hidden)
                } }
                if usesDrawer && groups.isEmpty {
                    deviceFilter.listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
                if searchService != nil && searching {
                    Button {
                        sidebarSearchFocused = false; searchedResult = nil; showingSearch = true
                    } label: { Text("搜索消息").frame(minHeight: 44) }
                        .listRowBackground(Theme.input).accessibilityIdentifier("search-all-messages")
                }
                if directory.showingCache {
                    Label("已保存的对话", systemImage: "clock")
                        .font(.caption).foregroundStyle(.secondary).listRowBackground(Color.clear)
                }
                if let error = directory.error {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(error).font(.callout).foregroundStyle(.secondary)
                        Button("重试") { Task { await directory.refresh() } }.disabled(directory.loading)
                    }.listRowBackground(Theme.input)
                }
                if directory.visible.isEmpty && !directory.loading && directory.error == nil {
                    if directory.filtered {
                        ContentUnavailableView {
                            Label("没有找到对话", systemImage: "magnifyingglass")
                        } description: { Text("换个关键词试试") } actions: {
                            Button("清除筛选") { directory.clearFilters() }
                        }.listRowBackground(Color.clear).listRowSeparator(.hidden)
                    } else {
                        ContentUnavailableView(directory.showingArchived ? "没有已归档的对话" : "还没有对话", systemImage: directory.showingArchived ? "archivebox" : "bubble.left.and.bubble.right")
                            .listRowBackground(Color.clear).listRowSeparator(.hidden)
                    }
                }
                ForEach(groups) { group in
                    Section {
                        ForEach(group.rows) { row in
                            conversationLink(row) {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(alignment: .firstTextBaseline) {
                                        Text(row.title).font(usesDrawer ? .subheadline : .headline).lineLimit(1)
                                        if directory.hasUnread(row) {
                                            Image(systemName: "circle.fill").font(.system(size: 7)).foregroundStyle(Theme.accent)
                                                .accessibilityLabel("有更新").accessibilityIdentifier("unread-\(row.id)")
                                        }
                                        Spacer(minLength: 6)
                                        if let date = row.updatedAt { Text(ConversationTimestamp.label(date)).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
                                        if !directory.showingArchived && directory.pinnedIDs.contains(row.id) {
                                            Image(systemName: "pin.fill").font(.caption).foregroundStyle(.secondary)
                                                .accessibilityLabel("已置顶")
                                        }
                                    }
                                    if let preview = row.preview {
                                        Text(preview).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                            .accessibilityIdentifier("conversation-preview-\(row.id)")
                                    }
                                    HStack(spacing: 6) {
                                        Image(systemName: "desktopcomputer")
                                        Text(remoteHostName(row.device, deviceID: row.deviceID)).lineLimit(1)
                                        Text("·")
                                        ConversationStatusLabel(conversation: row, showingCache: directory.showingCache, active: scenePhase == .active)
                                    }.font(.caption2).foregroundStyle(.secondary)
                                }.padding(.vertical, 4)
                            }.listRowBackground(Color.clear)
                                .contextMenu {
                                    if !directory.showingArchived { pinButton(row) }
                                    if row.titleRevision != nil {
                                        Button {
                                            renamingConversation = row; editedTitle = row.title; showingRename = true
                                        } label: { Label("重命名", systemImage: "pencil") }
                                            .disabled(directory.loading || directory.modifying)
                                    }
                                    archiveButton(row)
                                }
                                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                                    if !usesDrawer && !directory.showingArchived { pinButton(row).tint(.gray) }
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) { if !usesDrawer { archiveButton(row).tint(.gray) } }
                        }
                    } header: {
                        HStack(spacing: 12) {
                            Text(group.title).font(.caption.weight(.medium))
                            Spacer(minLength: 12)
                            if usesDrawer && group.id == groups.first?.id { deviceFilter }
                        }
                    }
                }
                if directory.loading || directory.modifying {
                    HStack { Spacer(); ProgressView("正在加载…"); Spacer() }.listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
            }
            .modifier(DirectoryListStyle(compact: usesDrawer))
            .scrollContentBackground(.hidden).background(Theme.surface)
            .navigationTitle(directory.showingArchived ? String(localized: "已归档") : "FunnyWork")
            .alert("重命名", isPresented: $showingRename) {
                TextField("对话名称", text: $editedTitle)
                Button("取消", role: .cancel) { renamingConversation = nil }
                Button("保存") {
                    guard let row = renamingConversation else { return }
                    Task {
                        if await directory.rename(row.id, to: editedTitle, expectedRevision: row.titleRevision), selectedID == row.id {
                            selectedConversation = directory.rows.first { $0.id == row.id }
                        }
                    }
                }.disabled(editedTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || editedTitle.utf16.count > 160)
            }
            .onChange(of: selectedID) { _, id in
                if messageLocation?.sessionID != id { messageLocation = nil }
                if let row = directory.rows.first(where: { $0.id == id }) {
                    selectedConversation = row
                }
            }
            .onChange(of: directory.showingArchived) { _, _ in
                directory.clearFilters(); selectedID = nil; selectedConversation = nil; compactColumn = .sidebar
            }
            .sheet(isPresented: $showingCreation, onDismiss: {
                if let row = createdConversation {
                    selectedConversation = row; selectedID = row.id; compactColumn = .detail; sidebarDismissal = UUID()
                }
            }) {
                if let createService, let store {
                    NewConversationView(service: createService, store: store, preferredDeviceID: creationDeviceID, authFailure: authFailure) { row in
                        createdConversation = row
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button { showingAccount = true } label: {
                    HStack(spacing: 12) {
                        AccountPicture(name: name)
                        Text(name.isEmpty ? String(localized: "账号") : name).lineLimit(1)
                        Spacer()
                    }.font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10).frame(minHeight: 56).contentShape(Rectangle())
                }.buttonStyle(.plain).background(Theme.surface).accessibilityLabel("账号").accessibilityValue(name)
            }
            .sheet(isPresented: $showingSearch, onDismiss: {
                if let result = searchedResult {
                    let row = result.conversation
                    messageLocation = .init(sessionID: row.id, sequence: result.sequence)
                    selectedConversation = row; selectedID = row.id; compactColumn = .detail; sidebarDismissal = UUID()
                }
            }) {
                if let searchService {
                    HistorySearchView(service: searchService, store: store, query: directory.search,
                        archived: directory.showingArchived, deviceID: directory.deviceID, authFailure: authFailure) { row in
                            searchedResult = row; showingSearch = false
                        }
                }
            }
            .sheet(isPresented: $showingDevices, onDismiss: {
                if createAfterDevices {
                    createAfterDevices = false; directory.showingArchived = false; createdConversation = nil; showingCreation = true
                }
            }) {
                if let createService {
                    DeviceDirectoryView(service: createService, authFailure: authFailure) { id in
                        creationDeviceID = id; createAfterDevices = true
                    }
                }
            }
            .sheet(isPresented: $showingAccount) {
                AccountSettingsView(name: name, store: store, push: push, allowanceService: allowanceService, authFailure: authFailure, logout: logout)
            }
            .refreshable { await directory.refresh() }
            }.background(Theme.surface)
            .navigationSplitViewColumnWidth(min: 280, ideal: 320, max: 400)
        } detail: {
            if let row = selectedConversation, selectedID == row.id {
                destination(row).id(row.id).environment(\.conversationMessageLocation, $messageLocation)
                    .environment(\.openConversation, { destination in
                        messageLocation = nil; selectedConversation = destination; selectedID = destination.id; compactColumn = .detail; sidebarDismissal = UUID()
                        Task { await directory.refresh() }
                    })
            } else {
                Text("今天想做点什么？").font(.title2.weight(.semibold))
                    .navigationTitle("FunnyWork").navigationBarTitleDisplayMode(.inline)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Theme.surface)
            }
        }
        .onChange(of: selectedID) { _, id in
            guard let store else { return }
            do { var saved = try store.loadNavigation(); saved.desktopID = id; try store.saveNavigation(saved) }
            catch { if error is AuthenticationError { authFailure(error) } }
        }
        .onChange(of: externalDesktopTask.wrappedValue?.id, initial: true) { _, id in
            guard id != nil, let row = externalDesktopTask.wrappedValue else { return }
            selectedConversation = row; selectedID = row.id; messageLocation = nil; compactColumn = .detail; sidebarDismissal = UUID()
            externalDesktopTask.wrappedValue = nil
        }
        // The drawer is conditionally mounted. Directory synchronization must outlive it.
        .task(id: directoryActive) {
            if directoryActive || directory.rows.isEmpty { await directory.refresh() }
            guard directoryActive else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(directory.groups.contains { [.active, .attention].contains($0.id) } ? 3 : 10)) } catch { return }
                await directory.refresh()
            }
        }
        .overlay { if let push { PushNavigationObserver(model: push) { row in
            showingAccount = false; showingCreation = false; showingDevices = false; showingSearch = false
            selectedConversation = row; selectedID = row.id; messageLocation = nil; compactColumn = .detail; sidebarDismissal = UUID()
        } } }
    }
    @ViewBuilder private func conversationLink<Label: View>(_ row: Conversation, @ViewBuilder label: () -> Label) -> some View {
        if usesDrawer {
            Button { selectedConversation = row; selectedID = row.id; compactColumn = .detail; sidebarDismissal = UUID() } label: { label().contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("conversation-\(row.id)")
        } else { NavigationLink(value: row.id, label: label).accessibilityIdentifier("conversation-\(row.id)") }
    }
    private var deviceFilter: some View {
        Menu {
            Button {
                directory.deviceID = nil
            } label: {
                if directory.deviceID == nil { Label("全部设备", systemImage: "checkmark") }
                else { Text("全部设备") }
            }
            ForEach(directory.devices, id: \.id) { device in
                Button {
                    directory.deviceID = device.id
                } label: {
                    if directory.deviceID == device.id { Label(device.name, systemImage: "checkmark") }
                    else { Text(device.name) }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(directory.devices.first(where: { $0.id == directory.deviceID })?.name ?? String(localized: "全部设备")).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption2)
            }.font(.caption).padding(.vertical, 8)
        }.accessibilityIdentifier("device-filter").accessibilityLabel("筛选设备")
    }
    private func pinButton(_ row: Conversation) -> some View {
        Button { directory.togglePin(row.id) } label: {
            Label(directory.pinnedIDs.contains(row.id) ? String(localized: "取消置顶") : String(localized: "置顶"),
                  systemImage: directory.pinnedIDs.contains(row.id) ? "pin.slash" : "pin")
        }
    }
    @ViewBuilder private func archiveButton(_ row: Conversation) -> some View {
        if row.archiveRevision != nil {
            Button {
                Task {
                    if await directory.setArchived(row, to: row.archived != true), selectedID == row.id {
                        selectedID = nil; selectedConversation = nil; compactColumn = .sidebar
                    }
                }
            } label: {
                Label(row.archived == true ? String(localized: "取消归档") : String(localized: "归档"),
                      systemImage: row.archived == true ? "tray.and.arrow.up" : "archivebox")
            }.disabled(directory.loading || directory.modifying)
        }
    }
}

private struct DirectoryListStyle: ViewModifier {
    let compact: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if compact { content.listStyle(.plain).listSectionSpacing(8).font(.subheadline)
                .environment(\.defaultMinListRowHeight, 44) }
        else { content }
    }
}

private enum ConversationTimestamp {
    private static let formatter: RelativeDateTimeFormatter = {
        let value = RelativeDateTimeFormatter()
        value.unitsStyle = .short
        value.dateTimeStyle = .named
        return value
    }()
    static func label(_ date: Date) -> String { formatter.localizedString(for: date, relativeTo: Date()) }
}

private struct PushNavigationObserver: View {
    @ObservedObject var model: PushNotifications
    let open: (Conversation) -> Void
    @Environment(\.conversationScope) private var scopeNavigation
    var body: some View {
        Color.clear.frame(width: 0, height: 0)
            .onChange(of: model.openedConversation?.id, initial: true) { _, _ in drain() }
            .onChange(of: scopeNavigation?.scope) { _, _ in drain() }
    }
    private func drain() {
        guard scopeNavigation == nil || scopeNavigation?.scope == .desktop else { return }
        if let row = model.openedConversation { model.openedConversation = nil; open(row) }
    }
}
