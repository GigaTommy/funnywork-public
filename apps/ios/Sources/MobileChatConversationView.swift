import SwiftUI

struct MobileChatConversationView: View {
    @ObservedObject var model: MobileChatModel
    let snapshot: MobileChatSnapshot
    var location: MobileChatSearchLocation? = nil
    var returnToSearch: (() -> Void)? = nil
    var transferToDesktop: (() -> Void)? = nil
    var newChat: (() -> Void)? = nil
    @State private var locatedRequest: UUID?
    @State private var missingSearchHit = false
    @StateObject private var reading = MessageReading()
    @State private var atBottom = true
    @State private var positioned = false
    @State private var newMessages = false
    @State private var visibleTurn: String?
    @State private var previousRevision: Revision?
    @State private var selecting = false
    @FocusState private var focused: Bool
    @AppStorage(ChatInputPreferences.keepKeyboardKey) private var keepKeyboard = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.conversationScope) private var scope
    private var active: Bool { scenePhase == .active && (scope == nil || scope?.scope == .mobile) }
    private var draft: Binding<String> { Binding(get: { model.draft }, set: model.updateDraft) }
    private var revision: Revision { Revision(turns: snapshot.turns.map(\.id), lastReply: snapshot.turns.last?.reply, live: model.liveReply, status: model.status, active: active, location: location?.requestID) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if missingSearchHit { Label("这条消息暂不可用", systemImage: "magnifyingglass").font(.caption).foregroundStyle(.secondary) }
                    ForEach(Array(snapshot.turns.enumerated()), id: \.element.id) { index, turn in
                        VStack(alignment: .leading, spacing: 12) {
                            VStack(alignment: .leading, spacing: 8) {
                                searchMarker(turn: turn.id, field: "user")
                                ChatMessageView(text: turn.text, label: "", isUser: true, readAloud: {}, selectionChanged: selectionChanged)
                            }.id("search-user-" + turn.id)
                            if !turn.reply.isEmpty {
                                VStack(alignment: .leading, spacing: 8) {
                                searchMarker(turn: turn.id, field: "reply")
                                ChatMessageView(text: turn.reply, label: "FunnyWork", isUser: false,
                                    readAloud: { reading.read(turn.reply, messageID: index) },
                                    usageDetails: ChatUsageDetails(summary: turn.usage.map(ChatUsageSummary.init) ?? ChatUsageSummary(),
                                        model: turn.modelLabel, title: String(localized: "本轮用量"), performance: turn.performance.map(ChatPerformanceSummary.init) ?? ChatPerformanceSummary()),
                                    selectionChanged: selectionChanged)
                                }.id("search-reply-" + turn.id)
                            }
                            if turn.state == "FAILED" || ["FAILED", "CANCELLED", "EXPIRED", "UNKNOWN"].contains(turn.ticketState) {
                                Text(turn.ticketState == "UNKNOWN" ? String(localized: "结果待确认") : String(localized: "这次回复未完成，内容已保留。"))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }.id(turn.id).background {
                            GeometryReader { geometry in
                                Color.clear.preference(key: MobileVisibleTurns.self, value: [turn.id: geometry.frame(in: .named("mobile-reading"))])
                            }
                        }
                    }
                    if !model.liveReply.isEmpty, snapshot.turns.last?.reply.isEmpty != false {
                        MessageContent(text: model.liveReply).accessibilityIdentifier("mobile-chat-live-reply")
                    }
                    if let status = model.status { Label(status, systemImage: "clock").font(.callout).foregroundStyle(.secondary) }
                    if let error = model.error {
                        Text(error).font(.callout).foregroundStyle(.secondary)
                        if model.activeBusy {
                            Button("核对结果") { model.retryPreparation() }.accessibilityIdentifier("mobile-chat-reconcile")
                            if model.saved.pending?.executionRequested == false { Button("编辑消息") { Task { await model.editUnsent() } } }
                        }
                    }
                    Color.clear.frame(height: 1).id("mobile-bottom")
                }.padding(20).frame(maxWidth: 760).frame(maxWidth: .infinity)
                    .background {
                        GeometryReader { geometry in
                            Color.clear.preference(key: MobileReadingGeometry.self, value: .init(bottom: geometry.frame(in: .named("mobile-reading")).maxY))
                        }
                    }
            }.coordinateSpace(name: "mobile-reading").background(Theme.surface)
                .refreshable { await model.refresh() }
                .accessibilityIdentifier("mobile-chat-transcript")
                .scrollDismissesKeyboard(.interactively)
                .background {
                    GeometryReader { geometry in
                        Color.clear.preference(key: MobileReadingGeometry.self, value: .init(height: geometry.size.height))
                    }
                }
                .onPreferenceChange(MobileVisibleTurns.self) { rows in
                    guard active, positioned, !selecting else { return }
                    if let first = rows.filter({ $0.value.maxY > 0 }).min(by: { $0.value.minY < $1.value.minY })?.key { visibleTurn = first }
                }
                .onPreferenceChange(MobileReadingGeometry.self) { metrics in
                    guard active, positioned, !selecting, previousRevision == revision,
                          let bottom = metrics.bottom, let height = metrics.height else { return }
                    atBottom = bottom <= height + 60
                    if atBottom { newMessages = false }
                }
                .overlay(alignment: .bottomTrailing) {
                    if !atBottom && positioned {
                        Button { proxy.scrollTo("mobile-bottom", anchor: .bottom); atBottom = true; newMessages = false } label: {
                            HStack(spacing: 8) {
                                if newMessages { Text("有新消息").font(.callout) }
                                Image(systemName: "arrow.down").dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                            }.padding(.horizontal, newMessages ? 16 : 0).frame(minWidth: 44, minHeight: 44)
                                .background(Theme.input, in: Capsule()).overlay(Capsule().stroke(Theme.line))
                        }.buttonStyle(.plain).padding().accessibilityIdentifier("mobile-scroll-to-latest")
                            .accessibilityLabel(newMessages ? String(localized: "有新消息") : String(localized: "回到最新"))
                    }
                }
                .task(id: SearchActivation(location: location, active: active, turns: snapshot.turns.map(\.id))) {
                    guard active, let location, locatedRequest != location.requestID || missingSearchHit else { return }
                    await Task.yield()
                    guard !Task.isCancelled else { return }
                    if let turn = location.turnID {
                        if snapshot.turns.contains(where: { $0.id == turn }) {
                            proxy.scrollTo("search-" + location.field + "-" + turn, anchor: .top)
                            atBottom = false; visibleTurn = turn; missingSearchHit = false
                        } else {
                            missingSearchHit = true; proxy.scrollTo("mobile-bottom", anchor: .bottom)
                        }
                    } else {
                        if let restore = model.saved.readingPositions[snapshot.chat.id], snapshot.turns.contains(where: { $0.id == restore }) {
                            proxy.scrollTo(restore, anchor: .top); atBottom = false
                        } else { proxy.scrollTo("mobile-bottom", anchor: .bottom) }
                    }
                    positioned = true; newMessages = false; locatedRequest = location.requestID; previousRevision = revision
                }
                .task(id: revision) {
                    guard active else { return }
                    if let location, locatedRequest != location.requestID { return }
                    let current = revision
                    let restore = !positioned ? model.saved.readingPositions[snapshot.chat.id] : nil
                    if !positioned || (atBottom && !selecting) {
                        await Task.yield()
                        guard !Task.isCancelled else { return }
                        if let restore, snapshot.turns.contains(where: { $0.id == restore }) {
                            atBottom = false; proxy.scrollTo(restore, anchor: .top)
                        } else { proxy.scrollTo("mobile-bottom", anchor: .bottom) }
                        positioned = true
                    } else if previousRevision?.turns != current.turns || previousRevision?.lastReply != current.lastReply || previousRevision?.live != current.live {
                        newMessages = true
                    }
                    previousRevision = current
                }
                .onChange(of: active) { _, value in
                    if !value { savePosition(); reading.stop(); focused = false }
                    else { previousRevision = nil }
                }
                .task(id: ReadingCheckpoint(turn: visibleTurn, atBottom: atBottom, active: active)) {
                    guard active, positioned else { return }
                    do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                    guard !Task.isCancelled else { return }
                    savePosition()
                }
        }.navigationTitle(snapshot.chat.title).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { savePosition(); focused = false; reading.stop(); if let newChat { newChat() } else { model.newChat() } } label: { Image(systemName: "square.and.pencil") }
                        .accessibilityLabel("新对话").accessibilityIdentifier("new-mobile-chat-top")
                    if transferToDesktop != nil || location != nil {
                        Menu {
                            if let transferToDesktop {
                                Button { focused = false; reading.stop(); transferToDesktop() } label: {
                                    Label("交给电脑生成", systemImage: "desktopcomputer")
                                }.accessibilityIdentifier("transfer-result-to-desktop")
                            }
                            if location != nil {
                                Button { savePosition(); focused = false; reading.stop(); returnToSearch?() } label: {
                                    Label("返回搜索", systemImage: "magnifyingglass")
                                }.accessibilityIdentifier("mobile-return-search")
                            }
                        } label: { Image(systemName: "ellipsis") }
                            .accessibilityLabel("更多").accessibilityIdentifier("mobile-chat-more")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) { composer }
            .onAppear { if keepKeyboard { focused = true } }
            .onDisappear { savePosition(); reading.stop() }
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if reading.messageID != nil {
                HStack {
                    Text(reading.paused ? String(localized: "朗读已暂停") : String(localized: "正在朗读")).font(.caption)
                    Spacer()
                    Button { reading.togglePause() } label: { Image(systemName: reading.paused ? "play.fill" : "pause.fill").frame(width: 44, height: 44) }
                        .accessibilityLabel(reading.paused ? String(localized: "继续朗读") : String(localized: "暂停朗读"))
                    Button { reading.stop() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("停止朗读")
                }.padding(.horizontal, 20)
            }
            if let error = reading.error { Text(error).font(.caption).foregroundStyle(.secondary) }
            if snapshot.chat.archived == true { Text("对话已归档，恢复后可继续发送。").font(.caption).foregroundStyle(.secondary).padding(8) }
            if model.activeBusy, !model.draft.isEmpty { Text("这条草稿会保留，回复结束后可发送。").font(.caption).foregroundStyle(.secondary).padding(8) }
            if let issue = model.connectionIssue {
                HStack {
                    Label("聊天服务暂时未连接", systemImage: "wifi.exclamationmark").font(.caption)
                    Spacer()
                    Button("重试") { Task { await model.refresh() } }
                }.padding(12).accessibilityElement(children: .contain).accessibilityHint(issue)
            }
            ChatComposer(text: draft, focused: $focused, storageError: model.error, identifier: "mobile-chat-message") {
                ChatModelMenu(modelID: Binding(get: { model.modelID }, set: model.selectModel), options: model.options, busy: model.activeBusy, identifier: "mobile-chat-model", reasoningEffort: Binding(get: { model.reasoningEffort }, set: model.selectReasoningEffort))
                ChatUsageMenu(summary: snapshot.usageSummary, model: snapshot.turns.last(where: { $0.attempted == true })?.modelLabel ?? "", performance: snapshot.performanceSummary)
                Spacer(minLength: 0)
                if model.activeBusy {
                    Button { Task { await model.stop() } } label: { Image(systemName: "stop.fill").frame(width: 44, height: 44) }
                        .accessibilityLabel("停止").accessibilityIdentifier("mobile-chat-stop")
                } else {
                    Button { model.send(); if !keepKeyboard { focused = false } } label: {
                        Image(systemName: "arrow.up").fontWeight(.semibold).frame(width: 44, height: 44)
                            .background(model.canSend ? Theme.accent : Theme.line, in: Circle()).foregroundStyle(model.canSend ? Theme.onAccent : Theme.accent)
                    }.disabled(!model.canSend).accessibilityLabel("发送").accessibilityIdentifier("mobile-chat-send")
                }
            }
        }.background(Theme.surface)
    }
    @ViewBuilder private func searchMarker(turn: String, field: String) -> some View {
        if let location, location.turnID == turn, location.field == field {
            HStack(spacing: 6) {
                Label("搜索命中", systemImage: "magnifyingglass")
                Text(verbatim: location.query).lineLimit(1)
            }.font(.caption).foregroundStyle(.secondary).accessibilityElement(children: .combine).accessibilityIdentifier("mobile-search-hit")
        }
    }
    private func selectionChanged(_ value: Bool) {
        selecting = value
        if value { atBottom = false; focused = false }
    }
    private func savePosition() {
        guard positioned else { return }
        model.saveReadingPosition(atBottom ? nil : visibleTurn, for: snapshot.chat.id)
    }
    private struct SearchActivation: Hashable { let location: MobileChatSearchLocation?; let active: Bool; let turns: [String] }
    private struct Revision: Hashable {
        let turns: [String]
        let lastReply: String?
        let live: String
        let status: String?
        let active: Bool
        let location: UUID?
    }
    private struct ReadingCheckpoint: Hashable { let turn: String?; let atBottom: Bool; let active: Bool }
}

private struct MobileVisibleTurns: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) { value.merge(nextValue(), uniquingKeysWith: { _, next in next }) }
}
private struct MobileReadingMetrics: Equatable { var bottom: CGFloat?; var height: CGFloat? }
private struct MobileReadingGeometry: PreferenceKey {
    static let defaultValue = MobileReadingMetrics()
    static func reduce(value: inout MobileReadingMetrics, nextValue: () -> MobileReadingMetrics) {
        let next = nextValue()
        if let bottom = next.bottom { value.bottom = bottom }
        if let height = next.height { value.height = height }
    }
}
