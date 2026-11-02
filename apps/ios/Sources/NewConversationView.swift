import SwiftUI

struct NewConversationView: View {
    @StateObject private var model: NewConversationModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmingClear = false
    @State private var advanced = false
    @State private var expandedTask = false
    @State private var submittedPreview = false
    @FocusState private var taskFocused: Bool
    @State private var actionTask: Task<Void, Never>?
    let onCreated: (Conversation) -> Void
    private let initialDraft: String?
    @State private var appliedInitialDraft = false
    init(service: NewConversationService, store: ConversationStore, preferredDeviceID: String? = nil, initialDraft: String? = nil, sourceChatID: String? = nil, authFailure: @escaping (Error) -> Void = { _ in }, onCreated: @escaping (Conversation) -> Void) {
        _model = StateObject(wrappedValue: NewConversationModel(service: service, store: store, preferredDeviceID: preferredDeviceID, sourceChatID: sourceChatID, authFailure: authFailure))
        self.onCreated = onCreated
        self.initialDraft = initialDraft
    }
    var body: some View {
        NavigationStack {
            Form {
                if let pending = model.pending {
                    Section {
                        StatusIndicator(title: statusText, style: model.creationStatusStyle).font(.callout)
                            .accessibilityIdentifier("creation-receipt-status")
                        Text(pending.deviceName).foregroundStyle(.secondary)
                        if !model.submittedDraft.isEmpty {
                            Text(verbatim: model.submittedDraft).lineLimit(3)
                            Button("查看提交内容") { submittedPreview = true }.accessibilityIdentifier("preview-submitted-task")
                                .fullScreenCover(isPresented: $submittedPreview) {
                                    TaskContentPreviewView(title: "提交内容", context: String(localized: "这是本次提交的任务内容。"), text: model.submittedDraft)
                                }
                        }
                        Button("核对结果") { perform { await model.refresh() } }.disabled(model.loading)
                        if model.canRetry { Button("重试") { perform { await model.retry() } } }
                        if model.canClear { Button("核对并清除") { confirmingClear = true } }
                    }.listRowBackground(Theme.input)
                } else {
                    Section("任务") {
                        if taskFocused { targetSummary }
                        TextField("想让电脑做什么？", text: Binding(get: { model.draft }, set: { model.updateDraft($0) }), axis: .vertical)
                            .lineLimit(3...8).focused($taskFocused).accessibilityIdentifier("creation-draft")
                        Button { taskFocused = false; expandedTask = true } label: {
                            Label("展开编辑", systemImage: "arrow.up.left.and.arrow.down.right").frame(minHeight: 44)
                        }.accessibilityIdentifier("expand-creation-draft")
                        if model.draft.utf16.count > 16000 { Text("内容过长，请缩短后继续。").font(.callout) }
                        Text("创建后会带入草稿，确认后发送。").font(.caption).foregroundStyle(.secondary)
                        if taskFocused, let reason = model.createDisabledReason {
                            Text(reason).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("creation-disabled-reason")
                        }
                    }.listRowBackground(Theme.input).disabled(model.sending)
                    Section("执行位置") {
                        Picker("设备", selection: Binding(get: { model.deviceID }, set: model.chooseDevice)) {
                            Text("选择设备").tag("")
                            if !model.deviceID.isEmpty, model.selected == nil { Text("所选设备暂不可用").tag(model.deviceID) }
                            ForEach(model.devices) { device in
                                Text(device.online ? device.name : device.name + " · " + String(localized: "离线"))
                                    .tag(device.id).disabled(!device.online)
                            }
                        }.accessibilityIdentifier("creation-device")
                        if let device = model.selected {
                            let state = RemoteConnectionState.device(device, fresh: model.presenceFresh, checking: model.loading, failed: model.error != nil)
                            StatusIndicator(title: state.title, style: state.style).font(.caption)
                        }
                        Picker("工作区", selection: Binding(get: { model.workspaceID }, set: model.chooseWorkspace)) {
                            Text("选择工作区").tag("")
                            if !model.workspaceID.isEmpty, model.selected?.workspaces.contains(where: { $0.id == model.workspaceID }) != true { Text("所选工作区暂不可用").tag(model.workspaceID) }
                            ForEach(model.selected?.workspaces ?? []) { workspace in Text(workspace.title).tag(workspace.id) }
                        }.disabled(model.selected?.online != true).accessibilityIdentifier("creation-workspace")
                    }.listRowBackground(Theme.input).disabled(model.sending)
                    if !model.loading && model.ready {
                        if model.devices.isEmpty || !model.devices.contains(where: \.online) {
                            Text("请打开桌面客户端后重试。").font(.callout).foregroundStyle(.secondary).listRowBackground(Color.clear)
                        } else if model.selected?.online == false {
                            Text("设备已离线，请选择其他设备或稍后重试。").font(.callout).foregroundStyle(.secondary).listRowBackground(Color.clear)
                        } else if let selected = model.selected, selected.online && selected.workspaces.isEmpty {
                            Text("没有可用工作区，请在桌面检查工作区与客户端版本。").font(.callout).foregroundStyle(.secondary).listRowBackground(Color.clear)
                        }
                    }
                    if model.choices != nil || !model.validChoices {
                        Section {
                            if !model.modelID.isEmpty || !model.presetID.isEmpty {
                                Text(verbatim: optionsSummary).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("creation-options-summary")
                            }
                            DisclosureGroup("更多选项", isExpanded: $advanced) {
                                Picker("模型", selection: Binding(get: { model.modelID }, set: model.chooseModel)) {
                                    Text("跟随桌面默认").tag("")
                                    if !model.modelID.isEmpty, model.selectedModel == nil { Text("所选模型暂不可用").tag(model.modelID) }
                                    ForEach(model.choices?.models ?? []) { choice in Text(verbatim: choice.name).tag(choice.id) }
                                }.accessibilityIdentifier("creation-model")
                                Picker("Agent 预设", selection: Binding(get: { model.presetID }, set: model.choosePreset)) {
                                    Text("跟随桌面默认").tag("")
                                    if !model.presetID.isEmpty, model.choices?.presets.contains(where: { $0.id == model.presetID }) != true { Text("所选预设暂不可用").tag(model.presetID) }
                                    ForEach(model.choices?.presets ?? []) { choice in Text(verbatim: choice.name).tag(choice.id) }
                                }.accessibilityIdentifier("creation-preset")
                                if !model.validChoices { Text("所选模型或预设已不可用，请重新选择。").font(.callout) }
                            }
                        }.listRowBackground(Theme.input).disabled(model.sending)
                    }

                }
                if let error = model.error {
                    Section {
                        Text(error).font(.callout)
                        Button("重试") { perform { await model.refresh() } }.disabled(model.loading || model.sending)
                    }.listRowBackground(Theme.input)
                }
                if model.loading { ProgressView().listRowBackground(Color.clear) }
            }
            .scrollContentBackground(.hidden).background(Theme.surface)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if model.pending == nil && !taskFocused {
                    VStack(spacing: 8) {
                        targetSummary
                        if let reason = model.createDisabledReason {
                            Text(reason).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("creation-disabled-reason")
                        }
                        Button { taskFocused = false; perform { await model.create() } } label: {
                            HStack {
                                Spacer(); Text("创建对话")
                                if model.sending { ProgressView() }
                                Spacer()
                            }
                        }.buttonStyle(PrimaryActionStyle()).disabled(!model.canCreate).accessibilityIdentifier("create-conversation")
                    }.padding().background(Theme.surface).overlay(alignment: .top) { Divider() }
                }
            }
            .navigationTitle("新对话").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } }
                ToolbarItemGroup(placement: .keyboard) {
                    if model.pending == nil {
                        Button("创建对话") { taskFocused = false; perform { await model.create() } }
                            .disabled(!model.canCreate).accessibilityIdentifier("create-conversation")
                    }
                    Spacer(); Button("收起键盘") { taskFocused = false }
                }
            }
            .fullScreenCover(isPresented: $expandedTask) {
                ExpandedComposerView(text: Binding(get: { model.draft }, set: model.updateDraft), storageError: model.error, disabled: model.sending || model.pending != nil,
                    title: "编辑任务", editorLabel: "任务", editorIdentifier: "expanded-task-text", closeIdentifier: "close-expanded-task",
                    context: String(localized: "完成后返回，内容不会自动发送。"))
            }
            .onChange(of: model.validChoices) { _, valid in if !valid { advanced = true } }

            .onChange(of: model.created?.id) { _, id in if id != nil, let row = model.created { onCreated(row); dismiss() } }
            .confirmationDialog("清除创建记录？", isPresented: $confirmingClear, titleVisibility: .visible) {
                Button("确认清除", role: .destructive) { model.clearAfterReview() }
            } message: { Text("清除不会删除已创建的对话，请先核对对话列表。") }
            .task(id: scenePhase) {
                guard scenePhase == .active else { actionTask?.cancel(); model.deactivate(); return }
                repeat {
                    await model.refresh()
                    do { try await Task.sleep(for: .seconds(5)) } catch { return }
                } while !Task.isCancelled
            }
        }.onDisappear { actionTask?.cancel(); model.deactivate() }
        .onAppear {
            guard !appliedInitialDraft else { return }; appliedInitialDraft = true
            if let initialDraft, model.pending == nil { model.updateDraft(ConversationModel.appendingDraft(initialDraft, to: model.draft)) }
        }
    }
    @ViewBuilder private var targetSummary: some View {
        if let device = model.selected, let workspace = device.workspaces.first(where: { $0.id == model.workspaceID }) {
            VStack(spacing: 4) {
                Text("本次执行位置").font(.caption).foregroundStyle(.secondary)
                Text(verbatim: device.name + " · " + workspace.title).font(.callout)
                let state = RemoteConnectionState.device(device, fresh: model.presenceFresh, checking: model.loading, failed: model.error != nil)
                StatusIndicator(title: state.title, style: state.style).font(.caption)
            }.accessibilityElement(children: .combine).accessibilityIdentifier("creation-target-summary")
        }
    }
    private var optionsSummary: String {
        let selectedPreset = model.choices?.presets.first { $0.id == model.presetID }
        return String(localized: "模型") + ": " + (model.modelID.isEmpty ? String(localized: "跟随桌面默认") : model.selectedModel?.name ?? String(localized: "所选模型暂不可用")) + "\n" + String(localized: "Agent 预设") + ": " +
            (model.presetID.isEmpty ? String(localized: "跟随桌面默认") : selectedPreset?.name ?? String(localized: "所选预设暂不可用"))
    }
    private func perform(_ operation: @escaping @MainActor () async -> Void) {
        actionTask?.cancel()
        actionTask = Task {
            guard !Task.isCancelled else { return }
            await operation()
        }
    }
    private var statusText: String {
        switch model.status {
        case "queued", "claimed": return String(localized: "正在创建，请稍候。")
        case "rejected": return String(localized: "未能创建，请检查所选工作区。")
        case "expired": return String(localized: "请求已过期，请重新选择。")
        default: return String(localized: "请先核对结果，避免重复创建。")
        }
    }
}

// Presentation stays separate from the receipt and task submission protocol.
extension NewConversationModel {
    var creationStatusStyle: StatusStyle {
        switch status {
        case "queued", "claimed": return .running
        case "rejected", "expired": return .failed
        default: return .waiting
        }
    }
}
