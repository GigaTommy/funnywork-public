import SwiftUI
import UniformTypeIdentifiers
import PhotosUI
import QuickLook

struct ConversationView: View {
    let conversation: Conversation
    private let attachmentService: AttachmentService?
    private let attachmentStore: ConversationStore
    private let attachmentAuthFailure: (Error) -> Void
    @State private var showingAttachments = false
    @State private var selectedAttachment: ChatAttachment?
    @State private var selectedImages: MessageImageSelection?
    @State private var draftPreview: URL?
    @State private var importingFiles = false
    @State private var importingPhotos = false
    @State private var photoSelection: [PhotosPickerItem] = []
    @State private var showingSearch = false
    @State private var showingTaskDetails = false
    @State private var editingQueue: RemoteQueueItem?
    @State private var queuedText = ""
    @State private var confirmingWithdrawalCopy = false
    @State private var reviewingResult: RemoteResultReview?
    @State private var resultAfterDetails: RemoteResultReview?
    @State private var showingVoiceInput = false
    @State private var showingExpandedComposer = false
    private let searchService: ConversationService
    private let voiceDriver: VoiceInputDriver?
    private let attachmentSelection: (() throws -> [URL])?
    private let cameraSelection: (() -> UIImage?)?
    @StateObject private var camera = CameraCapture()
    @StateObject private var reading: MessageReading
    @StateObject private var model: ConversationModel
    @StateObject private var fork: ForkConversationModel
    @Environment(\.openConversation) private var openConversation
    @State private var showingFork = false
    @State private var forkSequence: Int?
    @State private var forkPreview = ""
    @State private var forkDestination: Conversation?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.conversationScope) private var scopeNavigation
    private var conversationActive: Bool { scenePhase == .active && (scopeNavigation == nil || scopeNavigation?.scope == .desktop) }
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @FocusState private var composerFocused: Bool
    @AppStorage(ChatInputPreferences.keepKeyboardKey) private var keepKeyboard = false
    @State private var showingClearConfirmation = false
    @State private var atBottom = true
    @State private var newMessages = false
    @State private var didInitialScroll = false
    @State private var previousLast: Int?
    @State private var previousScrollRevision: ConversationScrollRevision?
    @State private var visibleInteractions = Set<String>()
    @State private var handledNotice = false
    @State private var readingPosition: Int?
    @Environment(\.conversationMessageLocation) private var incomingMessageLocation
    @State private var messageLocation: ConversationMessageLocation?
    @State private var failedLocation: ConversationMessageLocation?
    @State private var highlightedSequence: Int?
    @State private var expandedActivities: Set<Int> = []
    @State private var locatingMessage = false
    @State private var pendingHistoryScroll: Int?

    init(conversation: Conversation, service: ConversationService, store: ConversationStore, attachmentService: AttachmentService? = nil, uploadService: UploadService? = nil, reading: MessageReading? = nil, voiceDriver: VoiceInputDriver? = nil, attachmentSelection: (() throws -> [URL])? = nil, cameraSelection: (() -> UIImage?)? = nil, authFailure: @escaping (Error) -> Void = { _ in }) {
        _reading = StateObject(wrappedValue: reading ?? MessageReading())
        self.attachmentSelection = attachmentSelection
        self.cameraSelection = cameraSelection
        self.voiceDriver = voiceDriver
        self.searchService = service
        self.conversation = conversation
        self.attachmentService = attachmentService; self.attachmentStore = store; self.attachmentAuthFailure = authFailure
        _fork = StateObject(wrappedValue: ForkConversationModel(source: conversation, service: service as? ForkConversationService, store: store, authFailure: authFailure))
        _model = StateObject(wrappedValue: ConversationModel(sessionID: conversation.id, service: service, store: store, uploadService: uploadService, authFailure: authFailure))
    }
    var body: some View {
        ScrollViewReader { proxy in
            let gaps = model.historyGaps
            let timeline = ConversationTimeline(items: model.items, gaps: Set(gaps.keys))
            ScrollView {
                // Measured rows need stable positions; lazy height estimates can feed back into scroll preferences.
                VStack(alignment: .leading, spacing: 16) {
                    if !model.controlReady || !model.remote.online || model.showingCachedHistory { connection }
                    if let error = model.error { Text(error).foregroundStyle(.red).font(.callout) }
                    if let error = model.storageError { Text(error).foregroundStyle(.red).font(.callout) }
                    if handledNotice { Label("事项已更新", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.secondary) }
                    if model.earliest > 0 {
                        Button("加载更早记录") {
                            let anchor = model.items.first?.id
                            Task { await model.loadEarlier(); if let anchor { proxy.scrollTo(historyAnchor(anchor), anchor: .top) } }
                        }.disabled(model.loadingHistory)
                    }
                    if model.loadingHistory && model.items.isEmpty { ProgressView() }
                    if model.latest != nil && model.items.isEmpty { Text("还没有消息").foregroundStyle(.secondary) }
                    ForEach(timeline.rows) { row in
                        if gaps[row.id] != nil {
                            Button("加载中间记录") {
                                Task {
                                    if let first = await model.loadGap(before: row.id) {
                                        pendingHistoryScroll = first
                                    }
                                }
                            }.disabled(model.loadingHistory).accessibilityIdentifier("history-gap-\(row.id)")
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            if row.isActivity {
                                activityGroup(row)
                            } else if let item = row.items.first {
                                historyMessage(item)
                            }
                        }.id(row.id)
                            .background {
                                GeometryReader { geometry in
                                    Color.clear.preference(key: ConversationVisibleRows.self,
                                        value: [row.id: geometry.frame(in: .named("conversation-scroll"))])
                                }
                            }
                    }
                    ForEach(model.interactions) { interaction in
                        InteractionCard(interaction: interaction, model: model).id(interaction.id)
                    }
                    if let reply = model.liveReply {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small)
                                Text("正在回复…").font(.caption).foregroundStyle(.secondary)
                            }
                            MessageContent(text: reply.text)
                            if reply.truncated { Text("完整内容将在回复结束后显示。").font(.caption).foregroundStyle(.secondary) }
                        }.accessibilityIdentifier("live-assistant-reply")
                    }
                    if model.showingExecution && model.liveReply == nil {
                        HStack(spacing: 10) {
                            ProgressView().controlSize(.small)
                            Text(model.executionLabel).font(.callout).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityElement(children: .combine).accessibilityIdentifier("execution-status")
                    }
                    if let result = model.resultReview {
                        Button { composerFocused = false; reviewingResult = result } label: {
                            Label("审阅结果", systemImage: "doc.text.magnifyingglass")
                                .frame(maxWidth: .infinity, alignment: .leading).frame(minHeight: 44).contentShape(Rectangle())
                        }.accessibilityIdentifier("review-result")
                    }
                    if model.withdrawalPending {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("正在核对撤回结果", systemImage: "clock")
                            Text("电脑确认撤回后，内容会追加到草稿。核对完成前请勿重新发送这条内容。")
                                .font(.caption).foregroundStyle(.secondary)
                            Button("核对结果") { Task { await model.refresh() } }.disabled(model.refreshing)
                            if model.canKeepWithdrawalCopy {
                                Button("保留副本并结束核对") { confirmingWithdrawalCopy = true }
                            }
                        }.accessibilityIdentifier("queue-withdrawal-pending")
                    }
                    ForEach(model.remote.queue) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            Text("已排队").font(.caption).foregroundStyle(.secondary)
                            Text(verbatim: item.text).lineLimit(4)
                            if let edit = model.saved.queueEdits[item.id] { Text(model.queueEditLabel(edit)).font(.caption).foregroundStyle(.secondary) }
                            HStack {
                                Button("编辑") { queuedText = model.queueEditText(item); editingQueue = item }.disabled(!item.editable || !model.canOperate || model.withdrawing(item) || model.queueEditWaiting(item.id))
                                Button("撤回到草稿") { Task { await model.withdrawToDraft(item) } }
                                    .disabled(!item.editable || !model.canOperate || model.withdrawalPending || model.queueEditWaiting(item.id))
                                    .accessibilityIdentifier("withdraw-queue-" + item.id)
                                if !item.editable {
                                    Button("移除") { Task { await model.updateQueue(item, action: "remove") } }.disabled(!model.canOperate)
                                }
                                Button("立即补充") { Task { await model.updateQueue(item, action: "steer") } }.disabled(!model.canOperate || !model.remote.running || model.withdrawing(item) || model.queueEditWaiting(item.id))
                            }.font(.caption)
                        }.padding(12).background(Theme.input, in: RoundedRectangle(cornerRadius: 12))
                    }
                    QueueEditRecoveryView(model: model)
                    ForEach(model.queuedPrompts, id: \.command.id) { entry in
                        VStack(alignment: .leading, spacing: 6) {
                            Label(entry.receipt.label, systemImage: "clock").font(.caption).foregroundStyle(.secondary)
                            if case .string(let text) = entry.command.payload["text"], !text.isEmpty {
                                Text(text).font(.callout).lineLimit(3)
                            }
                            if case .array(let values) = entry.command.payload["attachments"] {
                                ForEach(values.compactMap(OutgoingAttachment.init(payload:))) { file in
                                    Label(file.name, systemImage: "doc").font(.caption).lineLimit(1)
                                }
                            }
                        }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Theme.input, in: RoundedRectangle(cornerRadius: 16))
                            .accessibilityIdentifier("queued-prompt-" + entry.command.id)
                    }
                    if !model.receipts.isEmpty {
                        DisclosureGroup("最近操作") {
                            ForEach(model.receipts.prefix(5)) { receipt in
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(receipt.label).font(.caption)
                                    if let text = model.commandText(receipt.id) { Text(text).font(.caption).lineLimit(3) }
                                    if model.draft.isEmpty, model.files.isEmpty, !model.uploading, model.pending == nil, ["rejected", "expired"].contains(receipt.status), model.commandText(receipt.id) != nil {
                                        Button("恢复这条草稿") { model.restoreDraft(receipt) }
                                    }
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
                            }
                        }.font(.callout).foregroundStyle(.secondary)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }.padding()
                    .background {
                        GeometryReader { geometry in
                            Color.clear.preference(key: ConversationScrollGeometry.self,
                                value: .init(bottom: geometry.frame(in: .named("conversation-scroll")).maxY))
                        }
                    }
            }
            .coordinateSpace(name: "conversation-scroll")
            .onPreferenceChange(ConversationVisibleRows.self) { rows in
                guard didInitialScroll, !rows.isEmpty else { return }
                if let first = rows.filter({ $0.value.maxY > 0 }).min(by: { $0.value.minY < $1.value.minY })?.key {
                    if readingPosition != first { readingPosition = first }
                }
            }
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: ConversationScrollGeometry.self, value: .init(height: geometry.size.height))
                }
            }
            .onPreferenceChange(ConversationScrollGeometry.self) { metrics in
                guard let bottom = metrics.bottom, let height = metrics.height else { return }
                // Appended content changes geometry before its scroll task runs.
                // Preserve the user's previous position until that update is handled.
                guard scrollRevision == previousScrollRevision else { return }
                let nextAtBottom = bottom <= height + 60
                if atBottom != nextAtBottom { atBottom = nextAtBottom }
                if nextAtBottom {
                    if newMessages { newMessages = false }
                    markVisibleHistoryRead()
                }
            }
            .background(Theme.surface)
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .top, spacing: 0) {
                if let interaction = model.interactions.first(where: { !model.blocked($0) }) {
                    Button {
                        composerFocused = false
                        proxy.scrollTo(interaction.id, anchor: .top)
                    } label: {
                        HStack {
                            InteractionStatusLabel(interaction: interaction)
                            Spacer()
                            Image(systemName: "chevron.down")
                        }.font(.subheadline).padding(.horizontal).padding(.vertical, 10).frame(minHeight: 44)
                    }.background(Theme.input).accessibilityIdentifier("pending-shortcut")
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if !atBottom && didInitialScroll {
                    Button { proxy.scrollTo("bottom", anchor: .bottom); newMessages = false } label: {
                        HStack(spacing: 8) {
                            if newMessages { Text("有新消息").font(.callout) }
                            Image(systemName: "arrow.down")
                        }.padding(.horizontal, newMessages ? 16 : 0).frame(minWidth: 44, minHeight: 44)
                            .background(Theme.input, in: Capsule())
                            .overlay(Capsule().stroke(Theme.accent.opacity(0.16)))
                    }.buttonStyle(.plain).padding()
                        .accessibilityLabel(newMessages ? String(localized: "有新消息") : String(localized: "回到最新"))
                        .accessibilityIdentifier("scroll-to-latest")
                }
            }
            .task(id: pendingHistoryScroll) {
                guard let target = pendingHistoryScroll else { return }
                await Task.yield()
                guard !Task.isCancelled else { return }
                proxy.scrollTo(historyAnchor(target), anchor: .top)
                pendingHistoryScroll = nil
            }
            .onChange(of: incomingMessageLocation.wrappedValue, initial: true) { _, location in
                guard let location, location.sessionID == conversation.id else { return }
                messageLocation = location
                incomingMessageLocation.wrappedValue = nil
            }
            .task(id: messageLocation) {
                guard let location = messageLocation else { return }
                locatingMessage = true; failedLocation = nil; atBottom = false
                let found = await model.loadAround(location.sequence)
                guard !Task.isCancelled else { return }
                await Task.yield()
                guard !Task.isCancelled else { return }
                previousLast = model.items.last?.id; previousScrollRevision = scrollRevision
                if found {
                    highlightedSequence = location.sequence
                    expandedActivities.insert(location.sequence)
                    didInitialScroll = true; newMessages = false
                    // Resolve the final viewport before moving to the search hit.
                    locatingMessage = false
                    await Task.yield()
                    guard !Task.isCancelled else { return }
                    // Center hits clear of the navigation bar and pinned task controls.
                    proxy.scrollTo(location.sequence, anchor: .center)
                } else {
                    failedLocation = location
                    if !didInitialScroll { proxy.scrollTo("bottom", anchor: .bottom); didInitialScroll = true }
                }
                locatingMessage = false; messageLocation = nil
            }
            .safeAreaInset(edge: .top) {
                if locatingMessage {
                    ProgressView("正在定位…").font(.callout).frame(maxWidth: .infinity).padding(10).background(Theme.input)
                } else if let failedLocation {
                    HStack {
                        Text("未能定位消息，请重试。").font(.callout)
                        Spacer()
                        Button("重试") { messageLocation = .init(sessionID: conversation.id, sequence: failedLocation.sequence) }
                        Button("取消") { self.failedLocation = nil }
                    }.padding(10).background(Theme.input)
                }
            }
            .task(id: scrollRevision) {
                guard !locatingMessage, messageLocation == nil, incomingMessageLocation.wrappedValue == nil else { return }
                let revision = scrollRevision
                guard revision.last != nil || !revision.queued.isEmpty || revision.working else { return }
                if !didInitialScroll || atBottom {
                    // Wait for the newly inserted rows to participate in layout.
                    await Task.yield()
                    guard !Task.isCancelled else { return }
                    if !didInitialScroll, let restoredPosition = (try? attachmentStore.loadHistory(conversation.id))?.readingPosition,
                       model.items.contains(where: { $0.id == restoredPosition }) {
                        atBottom = false
                        proxy.scrollTo(historyAnchor(restoredPosition), anchor: .top)
                    } else { proxy.scrollTo("bottom", anchor: .bottom) }
                    didInitialScroll = true
                } else if let previousLast, let last = revision.last, last > previousLast { newMessages = true }
                else if revision.liveText != nil && revision.liveText != previousScrollRevision?.liveText { newMessages = true }
                previousLast = revision.last
                previousScrollRevision = revision
                await Task.yield()
                if !Task.isCancelled { markVisibleHistoryRead() }
            }
            .onChange(of: model.interactions.map(\.id)) { _, current in
                let ids = Set(current)
                if !visibleInteractions.subtracting(ids).isEmpty { handledNotice = true }
                visibleInteractions = ids
            }
            .refreshable { await model.refresh() }
        }
        .navigationTitle(conversation.title).navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { composer }
        .toolbar {
            Button { composerFocused = false; showingSearch = true } label: { Image(systemName: "magnifyingglass") }
                .accessibilityLabel("搜索消息").accessibilityIdentifier("search-conversation")
            Button { composerFocused = false; showingTaskDetails = true } label: { Image(systemName: "ellipsis") }
                .accessibilityLabel("任务详情").accessibilityIdentifier("task-details")
        }
        .confirmationDialog("保留副本并结束核对？", isPresented: $confirmingWithdrawalCopy, titleVisibility: .visible) {
            Button("保留副本并结束核对") { model.keepWithdrawalCopyAfterReview() }
        } message: {
            Text("这不会撤回已发送的操作。内容可能仍在队列中或已开始处理，请核对电脑后再发送草稿。")
        }
        .sheet(item: $reviewingResult) { result in
            RemoteResultReviewView(result: result, model: model, attachmentService: attachmentService, store: attachmentStore, sessionID: conversation.id, authFailure: attachmentAuthFailure)
        }
        .sheet(item: $editingQueue) { item in
            NavigationStack {
                VStack {
                    if let error = model.error { Text(error).font(.callout).padding() }
                    if let edit = model.saved.queueEdits[item.id] {
                        Text(model.queueEditLabel(edit)).font(.callout).padding(.horizontal)
                            .accessibilityIdentifier("queue-edit-status")
                    }
                    TextEditor(text: $queuedText).scrollContentBackground(.hidden).padding()
                        .accessibilityIdentifier("queue-edit-text")
                        .disabled(model.queueEditWaiting(item.id))
                        .onChange(of: queuedText) { _, text in model.saveQueueEdit(item.id, text: text) }
                    if model.queueEditWaiting(item.id) {
                        Button("核对结果") { Task { await model.refresh() } }.disabled(model.refreshing)
                    } else if !model.remote.queue.contains(where: { $0.id == item.id }) {
                        Text("这条内容已不在队列中，编辑副本仍已保留。").font(.caption).padding()
                    }
                }.background(Theme.surface).navigationTitle("编辑排队消息")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("完成") { editingQueue = nil } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("提交修改") { Task { if await model.updateQueue(item, action: "edit", text: queuedText) { editingQueue = nil } } }.accessibilityIdentifier("submit-queue-edit")
                                .disabled(!model.canOperate || model.queueEditWaiting(item.id) || !model.remote.queue.contains(where: { $0.id == item.id }) || queuedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || queuedText.utf16.count > 16000)
                        }
                    }
            }
        }
        .sheet(isPresented: $showingTaskDetails, onDismiss: {
            if let result = resultAfterDetails { resultAfterDetails = nil; reviewingResult = result }
        }) {
            NavigationStack {
                List {
                    ReviewDraftRecoveryView(model: model) { result in
                        resultAfterDetails = result; showingTaskDetails = false
                    }
                    if let result = model.resultReview {
                        Section {
                            Button("审阅结果", systemImage: "doc.text.magnifyingglass") {
                                resultAfterDetails = result; showingTaskDetails = false
                            }.accessibilityIdentifier("review-result-details")
                        }.listRowBackground(Theme.input)
                    }
                    Section("任务详情") {
                        Text(verbatim: conversation.title).textSelection(.enabled)
                        Label(remoteHostName(conversation.device, deviceID: conversation.deviceID), systemImage: "desktopcomputer")
                        StatusIndicator(title: connectionState.title, style: connectionState.style).font(.caption)
                        if let date = model.lastSync { HStack { Text("上次同步"); Spacer(); Text(date, style: .time) }.font(.caption).foregroundStyle(.secondary) }
                    }
                    if model.controlReady, model.remote.online {
                        if let name = model.remote.selectedModel {
                            Section("实际使用模型") { Text(verbatim: name).textSelection(.enabled).accessibilityIdentifier("execution-model") }
                        }
                        if let name = model.remote.agentPreset {
                            Section("Agent 预设") { Text(verbatim: name).textSelection(.enabled) }
                        }
                        if model.remote.tokenUsage != nil || model.remote.performance != nil {
                            Section {
                                ChatUsageMenu(summary: model.remote.tokenUsage.map(ChatUsageSummary.init) ?? ChatUsageSummary(), model: model.remote.selectedModel ?? "", performance: model.remote.performance ?? ChatPerformanceSummary(), identifier: "desktop-task-usage-menu")
                            }
                        }
                    }
                }.scrollContentBackground(.hidden).background(Theme.surface)
                    .navigationTitle("任务详情").navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showingTaskDetails = false }.accessibilityIdentifier("task-details-close") } }
            }.tint(Theme.accent)
        }
        .fileImporter(isPresented: $importingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): model.addFiles(urls)
            case .failure(let error): model.uploadError = ClientError.displayMessage(for: error)
            }
        }
        .fullScreenCover(isPresented: $showingExpandedComposer) {
            ExpandedComposerView(text: Binding(get: { model.draft }, set: model.updateDraft), storageError: model.storageError, disabled: model.sending)
        }
        .sheet(isPresented: $showingFork, onDismiss: {
            if let row = forkDestination { forkDestination = nil; openConversation?(row) }
        }) {
            ForkConversationView(model: fork, sequence: forkSequence, preview: forkPreview) { row in forkDestination = row }
        }
        .quickLookPreview($draftPreview)
        .onChange(of: draftPreview) { _, url in if url == nil { attachmentStore.clearUploadPreview() } }
        .photosPicker(isPresented: $importingPhotos, selection: $photoSelection,
            maxSelectionCount: max(1, 4 - model.files.count), selectionBehavior: .ordered,
            matching: .images, preferredItemEncoding: .current)
        .onChange(of: photoSelection) { _, items in
            guard !items.isEmpty else { return }
            model.addPhotos(items)
            photoSelection = []
        }
        .fullScreenCover(isPresented: $camera.presented, onDismiss: {
            if let image = camera.takePhoto() { addCapturedPhoto(image) }
        }) {
            CameraPicker { camera.finish($0) }.ignoresSafeArea()
        }
        .alert("相机", isPresented: Binding(get: { camera.error != nil }, set: { if !$0 { camera.error = nil } })) {
            Button("取消", role: .cancel) {}
            if camera.needsSettings {
                Button("打开设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        } message: { Text(camera.error ?? "") }
        .sheet(isPresented: $showingVoiceInput) {
            VoiceInputView(driver: voiceDriver) { text in
                model.updateDraft(model.draft.isEmpty ? text : model.draft + "\n" + text)
            }
        }
        .sheet(isPresented: $showingSearch) {
            ConversationSearchView(service: searchService, attachmentService: attachmentService, store: attachmentStore,
                sessionID: conversation.id, authFailure: attachmentAuthFailure) { sequence in
                    messageLocation = .init(sessionID: conversation.id, sequence: sequence)
                }
        }
        .sheet(isPresented: $showingAttachments) {
            if let attachmentService, let http = attachmentService as? HTTPAttachmentService {
                ResultDirectoryView(service: HTTPResultService(api: http.api, sessionID: conversation.id), attachments: attachmentService,
                    store: attachmentStore, sessionID: conversation.id, authFailure: attachmentAuthFailure) { text in
                    model.updateDraft(model.draft.isEmpty ? text : model.draft + "\n\n" + text)
                }
            } else if let attachmentService {
                AttachmentView(service: attachmentService, store: attachmentStore, sessionID: conversation.id, authFailure: attachmentAuthFailure)
            }
        }
        .fullScreenCover(item: $selectedImages) { selection in
            if let attachmentService {
                MessageImageGallery(selection: selection, service: attachmentService, store: attachmentStore,
                    sessionID: conversation.id, authFailure: attachmentAuthFailure)
            }
        }
        .sheet(item: $selectedAttachment) { attachment in
            if let attachmentService {
                AttachmentView(service: attachmentService, store: attachmentStore, sessionID: conversation.id,
                    reference: attachment, authFailure: attachmentAuthFailure)
            }
        }
        .confirmationDialog("清除待确认操作？", isPresented: $showingClearConfirmation, titleVisibility: .visible) {
            Button("确认清除", role: .destructive) { model.dismissPendingAfterReview() }
        } message: { Text("清除不会撤回已发送的操作，请先确认执行结果。") }
        .task(id: conversationActive) {
            guard conversationActive else { model.deactivate(); return }
            await model.refresh()
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(model.controlReady && model.remote.running ? 1 : 2)) } catch { return }
                await model.refresh()
            }
        }
        .onChange(of: scopeNavigation?.scope) { _, scope in
            if scope == .mobile { saveReadingPosition(); reading.stop(); composerFocused = false }
        }
        .onDisappear { saveReadingPosition(); model.deactivate(); reading.stop(); if !camera.presented { camera.cancel() } }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { reading.stop() }
            if phase == .background { saveReadingPosition(); camera.cancel(); draftPreview = nil; attachmentStore.clearUploadPreview() }
        }
    }
    private var resultPreviewService: ResultService? {
        if let http = attachmentService as? HTTPAttachmentService { return HTTPResultService(api: http.api, sessionID: conversation.id) }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--ui-test-result-late-preview") { return ResultPreviewFixtureService.shared }
        #endif
        return nil
    }
    private func markVisibleHistoryRead() {
        guard conversationActive, didInitialScroll, atBottom, !locatingMessage, messageLocation == nil, incomingMessageLocation.wrappedValue == nil, let latest = model.latest else { return }
        try? attachmentStore.markRead(through: latest, for: conversation.id)
    }
    private func saveReadingPosition() {
        guard didInitialScroll else { return }
        if conversationActive, atBottom, !locatingMessage, let latest = model.latest { try? attachmentStore.markRead(through: latest, for: conversation.id) }
        try? attachmentStore.saveReadingPosition(atBottom ? nil : readingPosition, for: conversation.id)
    }
    private func openAttachment(_ file: ChatAttachment, alongside attachments: [ChatAttachment]) {
        if InlineImagePreview.isImage(file) { selectedImages = MessageImageSelection(selected: file, attachments: attachments) }
        else { selectedAttachment = file }
    }
    private func beginFork(_ item: HistoryItem) {
        fork.activate(); forkSequence = item.id; forkPreview = item.text.isEmpty ? item.attachments.map(\.name).joined(separator: "\n") : item.text; forkDestination = nil; showingFork = true
    }
    private func addCapturedPhoto(_ image: UIImage) {
        model.addAttachments([{
            let photo = try await PhotoAttachment.captured(image)
            return (photo.file, photo.data)
        }])
    }
    private var scrollRevision: ConversationScrollRevision {
        ConversationScrollRevision(last: model.items.last?.id, queued: model.queuedPrompts.map(\.command.id), working: model.showingExecution, liveText: model.liveReply?.text)
    }
    private var connectionState: RemoteConnectionState {
        if model.controlReady { return model.remote.online ? .online : .offline }
        if model.error != nil { return .failed }
        return model.refreshing ? .checking : .unknown
    }
    private var connection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(remoteHostName(conversation.device, deviceID: conversation.deviceID), systemImage: "desktopcomputer").font(.subheadline.weight(.medium))
            HStack {
                StatusIndicator(title: connectionState.title, style: connectionState.style)
                if let date = model.lastSync { Text("上次同步"); Text(date, style: .time) }
            }.font(.caption).foregroundStyle(.secondary)
            if model.showingCachedHistory {
                Label("已保存的记录", systemImage: "clock").font(.caption).foregroundStyle(.secondary)
            }
            if model.controlReady && !model.remote.online {
                Text("可查看已同步记录，电脑上线后可继续操作。").font(.caption).foregroundStyle(.secondary)
            }
            if !model.controlReady && !model.refreshing {
                Button("重试") { Task { await model.refresh() } }.font(.caption)
            }
        }
    }
    private func historyAnchor(_ sequence: Int) -> Int {
        ConversationTimeline(items: model.items, gaps: Set(model.historyGaps.keys)).anchor(for: sequence)
    }
    private func activityGroup(_ row: ConversationTimeline.Row) -> some View {
        let expanded = Binding(get: { row.items.contains { expandedActivities.contains($0.id) } }, set: { value in
            if value { expandedActivities.insert(row.id) }
            else { expandedActivities.subtract(row.items.map(\.id)) }
        })
        return VStack(alignment: .leading, spacing: 8) {
            Button { expanded.wrappedValue.toggle() } label: {
                HStack(spacing: 8) {
                    Image(systemName: "terminal")
                    Text("活动记录")
                    Text(verbatim: "\(row.items.count)").monospacedDigit()
                    if let activity = row.items.last?.activity,
                       [.completed, .stopped, .interrupted, .blocked, .failed, .limit].contains(activity.kind) {
                        StatusIndicator(title: activity.title, style: activity.statusStyle).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down")
                }.font(.caption).foregroundStyle(.secondary)
                    .frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityIdentifier("activity-group-\(row.id)")
                .accessibilityValue(expanded.wrappedValue ? String(localized: "已展开") : String(localized: "已收起"))
            if expanded.wrappedValue {
                ForEach(row.items) { item in
                    if item.id == row.id { historyMessage(item) }
                    else { historyMessage(item).id(item.id) }
                }
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    @ViewBuilder private func historyMessage(_ item: HistoryItem) -> some View {
        if highlightedSequence == item.id {
            Label("搜索结果", systemImage: "magnifyingglass").font(.caption).foregroundStyle(.secondary)
                .accessibilityIdentifier("located-message")
        }
        message(item)
    }
    @ViewBuilder private func message(_ item: HistoryItem) -> some View {
        if !item.results.isEmpty, let attachmentService {
            ForEach(item.results, id: \.identity) { saved in
                let result = model.resultVersions[saved.identity] ?? saved
                ResultFileCard(result: result, service: attachmentService,
                    resultService: resultPreviewService,
                    store: attachmentStore, sessionID: conversation.id, authFailure: attachmentAuthFailure) { text in
                        model.updateDraft(model.draft.isEmpty ? text : model.draft + "\n\n" + text)
                    }
            }
        } else if let activity = item.activity {
            VStack(alignment: .leading, spacing: 8) {
                HistoryActivityView(item: item, activity: activity)
                ForEach(item.attachments) { attachment in
                    MessageAttachmentCard(file: attachment, service: attachmentService, store: attachmentStore,
                        sessionID: conversation.id, authFailure: attachmentAuthFailure) { openAttachment(attachment, alongside: item.attachments) }
                }
            }
        } else if item.text.isEmpty && item.attachments.isEmpty && item.interrupted {
            InterruptedReplyNotice()
        } else if item.text.isEmpty && item.attachments.isEmpty {
            DisclosureGroup {
                Text(item.displayLabel).font(.caption).textSelection(.enabled)
            } label: { Label("活动记录", systemImage: "terminal").font(.caption) }
            .foregroundStyle(.secondary)
        } else {
            VStack(alignment: item.isUser ? .trailing : .leading, spacing: 8) {
                if !item.text.isEmpty {
                    ChatMessageView(text: item.text, label: item.displayLabel, isUser: item.isUser, fork: fork.supported && openConversation != nil && model.canOperate && model.remote.sessionForks ? { beginFork(item) } : nil) { reading.read(item.text, messageID: item.id) }
                }
                ForEach(item.attachments) { attachment in
                    MessageAttachmentCard(file: attachment, service: attachmentService, store: attachmentStore,
                        sessionID: conversation.id, authFailure: attachmentAuthFailure) { openAttachment(attachment, alongside: item.attachments) }
                        .accessibilityIdentifier("message-attachment-\(item.id)-\(attachment.hash)")
                        .contextMenu {
                            if fork.supported && openConversation != nil && model.canOperate && model.remote.sessionForks {
                                Button("从这里继续", systemImage: "arrow.triangle.branch") { beginFork(item) }
                            }
                        }
                }
                if !item.isUser, let result = model.reviewContaining(item.id) {
                    Button("审阅本轮", systemImage: "doc.text.magnifyingglass") { composerFocused = false; reviewingResult = result }
                        .font(.caption).frame(minHeight: 44).accessibilityIdentifier("review-round-\(item.id)")
                }
                if item.interrupted { InterruptedReplyNotice() }
            }.frame(maxWidth: .infinity, alignment: item.isUser ? .trailing : .leading)
        }
    }
    @ViewBuilder private var outgoingFiles: some View {
        if !model.files.isEmpty {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(model.files) { file in
                        HStack(spacing: 8) {
                            Button {
                                composerFocused = false
                                do { draftPreview = try attachmentStore.prepareUploadPreview(file, sessionID: conversation.id) }
                                catch {
                                    model.uploadError = String(localized: "无法预览，请重新添加附件。")
                                    if error is AuthenticationError { attachmentAuthFailure(error) }
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    OutgoingAttachmentIcon(file: file, store: attachmentStore, sessionID: conversation.id)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(file.name).lineLimit(1).frame(maxWidth: 150, alignment: .leading)
                                        Text(file.uploaded ? String(localized: "已就绪") : String(localized: "待上传"))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain).accessibilityLabel(String(localized: "预览") + " " + file.name)
                                .accessibilityIdentifier("preview-draft-" + file.hash)
                            Button { model.removeFile(file) } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }
                                .accessibilityLabel(String(localized: "移除附件") + " " + file.name).disabled(!model.canEditFiles)
                        }.font(.callout).padding(.leading, 12)
                            .background(Theme.input, in: RoundedRectangle(cornerRadius: 14))
                            .accessibilityIdentifier("outgoing-file-" + file.hash)
                    }
                }
            }
        }
        if model.uploading {
            HStack {
                if model.importingAttachments {
                    ProgressView().controlSize(.small)
                    Text("正在读取…").font(.caption)
                    Spacer()
                    Button("取消") { model.pauseUploads() }.font(.caption)
                } else {
                    ProgressView(value: model.uploadProgress).accessibilityLabel("上传进度")
                    Button("暂停上传") { model.pauseUploads() }.font(.caption)
                }
            }
        } else if model.files.contains(where: { !$0.uploaded }) {
            Button("继续上传") { model.retryUploads() }.font(.caption).disabled(!model.canEditFiles)
        }
        if let error = model.uploadError { Text(error).font(.caption).foregroundStyle(.secondary) }
    }
    private var composer: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let reason = model.sendDisabledReason {
                Text(reason).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("send-disabled-reason")
            }
            outgoingFiles
            if reading.messageID != nil {
                HStack {
                    Label(reading.paused ? String(localized: "朗读已暂停") : String(localized: "正在朗读"), systemImage: "speaker.wave.2")
                        .font(.caption)
                    Spacer()
                    Button { reading.togglePause() } label: {
                        Label(reading.paused ? String(localized: "继续朗读") : String(localized: "暂停朗读"), systemImage: reading.paused ? "play.fill" : "pause.fill")
                    }.accessibilityIdentifier("pause-reading")
                    Button { reading.stop() } label: { Label("停止朗读", systemImage: "xmark") }
                }.labelStyle(.iconOnly).buttonStyle(ComposerActionStyle())
            }
            if let error = reading.error { Text(error).font(.caption).foregroundStyle(.secondary) }
            if fork.pending != nil, openConversation != nil {
                Button("查看分支结果") { fork.activate(); forkSequence = nil; forkPreview = ""; forkDestination = nil; showingFork = true }
                    .font(.caption).accessibilityIdentifier("pending-fork")
            }
            if model.pending != nil {
                StatusIndicator(title: String(localized: "操作结果待确认"), style: .waiting).font(.caption)
                HStack {
                    Button("重试") { Task { await model.retry() } }.disabled(!model.canRetry)
                    Button("核对并清除") { showingClearConfirmation = true }.disabled(model.sending)
                }.font(.callout)
                if !model.canRetry { Text("请先查看执行结果。").font(.caption).foregroundStyle(.secondary) }
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .top, spacing: 0) {
                    TextField("说点什么…", text: Binding(get: { model.draft }, set: model.updateDraft), axis: .vertical)
                        .accessibilityIdentifier("conversation-composer").accessibilityLabel("消息")
                        .focused($composerFocused)
                        .lineLimit(1...(dynamicTypeSize.isAccessibilitySize ? 2 : 5))
                        .padding(.leading, 16).padding(.top, 14).padding(.bottom, 6)
                        .disabled(model.sending)
                    if !model.draft.isEmpty {
                        Button { composerFocused = false; showingExpandedComposer = true } label: {
                            Label("展开输入", systemImage: "arrow.up.left.and.arrow.down.right")
                        }.labelStyle(.iconOnly).buttonStyle(ComposerActionStyle())
                            .padding(.top, 4).padding(.trailing, 4)
                            .accessibilityIdentifier("expand-composer").disabled(model.sending)
                    }
                }
                if model.draft.utf16.count > 16000 {
                    Text("内容过长，请缩短后重试。")
                        .font(.caption).foregroundStyle(.red).padding(.horizontal, 16)
                }
                HStack(spacing: 6) {
                    if attachmentService != nil {
                        Menu {
                            if camera.available || cameraSelection != nil {
                                Button("拍照", systemImage: "camera") {
                                    composerFocused = false
                                    if let cameraSelection {
                                        if let image = cameraSelection() { addCapturedPhoto(image) }
                                    } else { Task { await camera.open() } }
                                }.disabled(!model.canAddFiles || camera.requesting)
                            }
                            Button("照片", systemImage: "photo") { composerFocused = false; importingPhotos = true }
                                .disabled(!model.canAddFiles)
                            Button("添加文件") {
                                composerFocused = false
                                if let attachmentSelection {
                                    do { model.addFiles(try attachmentSelection()) }
                                    catch { model.uploadError = ClientError.displayMessage(for: error) }
                                } else { importingFiles = true }
                            }.disabled(!model.canAddFiles)
                            Button("对话文件") { composerFocused = false; showingAttachments = true }
                            if model.remote.online && !model.remote.attachmentUploads { Text("请更新桌面客户端后再添加附件。") }
                        } label: { Image(systemName: "plus") }
                        .buttonStyle(ComposerActionStyle())
                            .accessibilityLabel("文件").accessibilityIdentifier("conversation-files")
                    }
                    if model.remote.running, model.remote.turn != nil {
                        Button { Task { await model.cancel() } } label: {
                            Image(systemName: "stop.fill")
                        }.buttonStyle(ComposerActionStyle())
                            .accessibilityLabel("停止").accessibilityIdentifier("stop-generation")
                            .disabled(!model.canOperate)
                    }
                    if composerFocused {
                        Button { composerFocused = false } label: {
                            Image(systemName: "keyboard.chevron.compact.down")
                        }.buttonStyle(ComposerActionStyle()).accessibilityLabel("收起键盘")
                    }
                    Spacer(minLength: 0)
                    Button { composerFocused = false; reading.stop(); showingVoiceInput = true } label: {
                        Image(systemName: "mic")
                    }.buttonStyle(ComposerActionStyle()).accessibilityLabel("语音输入")
                        .disabled(model.sending)
                    if model.remote.running, model.remote.turn != nil {
                        Menu {
                            Text("排队消息会在这轮结束后发送。")
                            Text("立即补充会指导正在进行的工作。")
                            Button("立即补充") { Task { await model.prompt(steer: true) } }
                        } label: {
                            HStack(spacing: 4) { Text("排队").lineLimit(1); Image(systemName: "chevron.down") }
                                .font(.caption).dynamicTypeSize(...DynamicTypeSize.large).fixedSize(horizontal: true, vertical: false)
                        }
                        .buttonStyle(ComposerActionStyle())
                        .accessibilityLabel("发送方式").disabled(!model.canOperate || !model.validPrompt || model.withdrawalPending)
                    }
                    Button {
                        Task { await model.prompt(); if model.draft.isEmpty && !keepKeyboard { composerFocused = false } }
                    } label: {
                        if model.sending { ProgressView().tint(Theme.onAccent) }
                        else { Image(systemName: model.remote.running ? "arrow.up.forward" : "arrow.up").fontWeight(.semibold) }
                    }.buttonStyle(ComposerActionStyle(prominent: true))
                        .accessibilityLabel(model.remote.running ? String(localized: "排队发送") : String(localized: "发送"))
                        .accessibilityIdentifier("send-message")
                        .disabled(!model.canOperate || !model.validPrompt || model.withdrawalPending)
                }.padding(.horizontal, 8).padding(.bottom, 8)
            }
            .background(Theme.input, in: RoundedRectangle(cornerRadius: 26))
            .overlay(RoundedRectangle(cornerRadius: 26).stroke(Theme.accent.opacity(0.12)))
        }.padding(.horizontal, 12).padding(.vertical, 8).background(Theme.surface)
    }
}

private struct ComposerActionStyle: ButtonStyle {
    var prominent = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.body).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .frame(width: 44, height: 44)
            .foregroundStyle(prominent ? Theme.onAccent : Theme.accent)
            .background(prominent ? Theme.accent : Color.clear, in: Circle())
            .opacity(enabled ? (configuration.isPressed ? 0.65 : 1) : 0.35)
    }
}

private struct InteractionStatusLabel: View {
    let interaction: Interaction
    var body: some View {
        Label {
            Text(interaction.kind == .approval ? String(localized: "需要你的批准") : String(localized: "需要你的回答"))
                .foregroundStyle(Color.primary)
        } icon: {
            Image(systemName: interaction.kind == .approval ? "hand.raised" : "questionmark.bubble")
                .foregroundStyle(Theme.warningStatus).accessibilityHidden(true)
        }
    }
}

private struct InteractionCard: View {
    let interaction: Interaction
    @ObservedObject var model: ConversationModel
    private var enabled: Bool { model.canOperate && !model.blocked(interaction) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            InteractionStatusLabel(interaction: interaction).font(.headline)
            if interaction.kind == .approval {
                Text(interaction.title).font(.subheadline.weight(.semibold))
                if !interaction.reason.isEmpty { Text(interaction.reason) }
                DisclosureGroup("操作详情") { Text(interaction.detail).font(.system(.caption, design: .monospaced)).textSelection(.enabled) }
                HStack {
                    Button("拒绝") { Task { await model.decide(interaction, allow: false) } }.buttonStyle(.bordered)
                        .accessibilityIdentifier("approval-deny-\(interaction.correlation)")
                    Spacer()
                    Button("本次允许") { Task { await model.decide(interaction, allow: true) } }.buttonStyle(PrimaryActionStyle())
                        .accessibilityIdentifier("approval-allow-\(interaction.correlation)")
                }.disabled(!enabled)
            } else {
                ForEach(interaction.questions) { question in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(question.title).font(.subheadline.weight(.medium))
                        if !question.detail.isEmpty { Text(question.detail).font(.caption).foregroundStyle(.secondary) }
                        ForEach(question.options) { option in
                            Button {
                                var answer = model.answer(interaction, question)
                                if answer.selected.contains(option.label) { answer.selected.removeAll { $0 == option.label } }
                                else if question.multiple { answer.selected.append(option.label) }
                                else { answer.selected = [option.label] }
                                model.updateAnswer(answer, interaction: interaction, question: question)
                            } label: {
                                HStack(alignment: .top) {
                                    Image(systemName: model.answer(interaction, question).selected.contains(option.label) ? "checkmark.circle.fill" : "circle")
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(option.label)
                                        if !option.detail.isEmpty { Text(option.detail).font(.caption).foregroundStyle(.secondary) }
                                    }
                                    Spacer(minLength: 0)
                                }.padding(.vertical, 4).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                                .accessibilityAddTraits(model.answer(interaction, question).selected.contains(option.label) ? [.isSelected] : [])
                        }
                        TextField("补充回答", text: Binding(get: { model.answer(interaction, question).custom }, set: {
                            var answer = model.answer(interaction, question); answer.custom = $0
                            model.updateAnswer(answer, interaction: interaction, question: question)
                        }), prompt: Text("补充回答").foregroundStyle(.secondary), axis: .vertical)
                            .lineLimit(2...6).padding(12).background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(.primary.opacity(0.15)))
                    }.disabled(!enabled)
                }
                Button("提交回答") { Task { await model.reply(interaction) } }
                    .buttonStyle(PrimaryActionStyle()).disabled(!enabled || !model.answersValid(interaction))
            }
            if model.blocked(interaction) { Text("已提交").font(.caption).foregroundStyle(.secondary) }
        }.padding().background(Theme.input, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.15)))
    }
}

private struct ConversationScrollRevision: Hashable {
    let last: Int?
    let queued: [String]
    let working: Bool
    let liveText: String?
}

private struct ConversationVisibleRows: PreferenceKey {
    static let defaultValue: [Int: CGRect] = [:]
    static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, next in next })
    }
}

private struct ConversationScrollMetrics: Equatable {
    var bottom: CGFloat?
    var height: CGFloat?
}
private struct ConversationScrollGeometry: PreferenceKey {
    static let defaultValue = ConversationScrollMetrics()
    static func reduce(value: inout ConversationScrollMetrics, nextValue: () -> ConversationScrollMetrics) {
        let next = nextValue()
        if let bottom = next.bottom { value.bottom = bottom }
        if let height = next.height { value.height = height }
    }
}
