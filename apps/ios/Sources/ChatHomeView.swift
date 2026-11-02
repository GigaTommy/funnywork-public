import SwiftUI

struct ChatModelOption: Identifiable, Equatable {
    let id: String
    let name: String
    var model: String? = nil
    var providerName: String? = nil
    var reasoningEfforts: [String] = []
    var defaultReasoningEffort = "off"
    var label: String { name.isEmpty ? (model ?? id) : name }
    var provider: String {
        if let value = providerName?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty { return value }
        // Older mobile servers omit provider metadata. Classify the model identifier,
        // never the user-editable deployment label or its routing endpoint.
        let identifier = (model ?? "").lowercased().split(separator: "/").last.map(String.init) ?? ""
        if identifier.hasPrefix("gpt-") || identifier.hasPrefix("chatgpt-") || identifier.range(of: "^o[1-9](-.*)?$", options: .regularExpression) != nil { return "OpenAI" }
        if identifier.hasPrefix("deepseek") { return "DeepSeek" }
        if identifier.hasPrefix("claude") { return "Anthropic" }
        if identifier.hasPrefix("gemini") { return "Google" }
        if identifier.hasPrefix("qwen") { return "Alibaba" }
        if identifier.hasPrefix("kimi") || identifier.hasPrefix("moonshot") { return "Moonshot" }
        if identifier.hasPrefix("grok") { return "xAI" }
        return "Custom"
    }
    static func grouped(_ options: [Self]) -> [(provider: String, models: [Self])] {
        var groups: [(provider: String, models: [Self])] = []
        var indices: [String: Int] = [:]
        for option in options {
            let provider = option.provider
            if let index = indices[provider] { groups[index].models.append(option) }
            else { indices[provider] = groups.count; groups.append((provider, [option])) }
        }
        return groups
    }
}

/// The mobile chat start page keeps composing visible before a conversation exists.
struct ChatHomeView: View {
    let name: String
    @Binding var draft: String
    @Binding var modelID: String
    let models: [ChatModelOption]
    var reasoningEffort: Binding<String> = .constant("off")
    let busy: Bool
    let canSend: Bool
    let status: String?
    let error: String?
    let send: () -> Void
    let refresh: () -> Void
    var edit: (() -> Void)? = nil
    var connectionIssue: String? = nil
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var showingConnection = false
    @State private var connectionVisible = false
    @State private var pendingSuggestion: String?
    @AppStorage(ChatInputPreferences.keepKeyboardKey) private var keepKeyboard = false
    @FocusState private var focused: Bool
    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 8) {
                        Text(name.isEmpty ? String(localized: "HI，你好") : "HI，\(name)")
                            .font(.footnote).foregroundStyle(.secondary)
                        Text("今天想聊点什么？").font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                            .accessibilityIdentifier("home-chat-heading")
                    }
                    if !focused {
                        VStack(spacing: 8) {
                            suggestion("解释一个概念", prompt: "帮我解释一个概念：")
                            suggestion("帮我润色文字", prompt: "帮我润色下面这段文字：")
                            suggestion("一起想个点子", prompt: "一起想想这个问题：")
                        }.frame(maxWidth: 340)
                    }
                    if let status { Text(status).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center) }
                    if let error {
                        VStack(spacing: 8) {
                            Text(error).font(.callout).multilineTextAlignment(.center)
                            Button("重试", action: refresh)
                            if let edit { Button("编辑消息", action: edit).accessibilityIdentifier("home-chat-edit") }
                        }.accessibilityIdentifier("home-chat-error")
                    }
                }.padding(.horizontal, 24)
                    // Anchor above the visual centre; scrolling keeps errors and large text reachable.
                    .padding(.top, focused ? 24 : min(112, max(24, geometry.size.height * 0.16)))
                    .padding(.bottom, 24).frame(maxWidth: .infinity)
            }.scrollBounceBehavior(focused ? .always : .basedOnSize)
            .accessibilityIdentifier("home-chat-content")
            .scrollDismissesKeyboard(.interactively)
            .simultaneousGesture(DragGesture(minimumDistance: 20).onChanged { value in
                if focused, value.translation.height > 20,
                   value.translation.height > abs(value.translation.width) { focused = false }
            })
            .safeAreaInset(edge: .bottom) {
                ChatComposer(text: $draft, focused: $focused, storageError: error, identifier: "home-chat-message") {
                    if !models.isEmpty {
                        ChatModelMenu(modelID: $modelID, options: models, busy: busy, identifier: "home-chat-model", reasoningEffort: reasoningEffort)
                    }
                    Spacer()
                    Button { send(); if !keepKeyboard { focused = false } } label: {
                        Group { if busy { ProgressView() } else { Image(systemName: "arrow.up").fontWeight(.semibold) } }
                            .dynamicTypeSize(...DynamicTypeSize.xxxLarge).frame(width: 44, height: 44)
                            .background(canSend ? Theme.accent : Theme.line, in: Circle())
                            .foregroundStyle(canSend ? Theme.onAccent : Theme.accent)
                    }.disabled(!canSend).accessibilityLabel("发送").accessibilityIdentifier("home-chat-send")
                }
            }
        }.background(Theme.surface)
            .confirmationDialog("保留当前草稿？", isPresented: Binding(get: { pendingSuggestion != nil }, set: { if !$0 { pendingSuggestion = nil } }), titleVisibility: .visible) {
                if let suggestion = pendingSuggestion {
                    Button("追加到草稿") { draft += "\n" + suggestion; focused = true; pendingSuggestion = nil }
                    Button("替换草稿", role: .destructive) { draft = suggestion; focused = true; pendingSuggestion = nil }
                }
                Button("取消", role: .cancel) { pendingSuggestion = nil }
            }
            .navigationTitle("FunnyWork").navigationBarTitleDisplayMode(.inline)
            .task(id: connectionIssue) {
                connectionVisible = false
                guard connectionIssue != nil else { return }
                do { try await Task.sleep(for: .seconds(2)); connectionVisible = true } catch { }
            }
            .toolbar {
                if connectionVisible {
                    if #available(iOS 26.0, *) {
                        ToolbarItem(placement: .topBarTrailing) { connectionButton }.sharedBackgroundVisibility(.hidden)
                    } else {
                        ToolbarItem(placement: .topBarTrailing) { connectionButton }
                    }
                }
            }
            .sheet(isPresented: $showingConnection) {
                NavigationStack {
                    ScrollView {
                        VStack(spacing: 16) {
                            Text("暂时未连接").font(.headline).fixedSize(horizontal: false, vertical: true)
                            if let connectionIssue {
                                Text(connectionIssue).font(.callout).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Button("重试") { showingConnection = false; refresh() }.frame(minHeight: 44)
                        }.padding(24).frame(maxWidth: .infinity)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.surface)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showingConnection = false } } }
                }.presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
                    .presentationDragIndicator(.visible)
            }
    }
    private var connectionButton: some View {
        Button { showingConnection = true } label: {
            Label("暂时未连接", systemImage: "wifi.exclamationmark").font(.caption)
                .frame(minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel("连接状态").accessibilityValue(Text("暂时未连接")).accessibilityIdentifier("home-connection-status")
    }
    private func suggestion(_ label: LocalizedStringKey, prompt: String) -> some View {
        Button {
            let suggestion = String(localized: String.LocalizationValue(prompt))
            if draft.isEmpty { draft = suggestion; focused = true } else { pendingSuggestion = suggestion }
        } label: {
            HStack(spacing: 12) {
                Text(label).font(.subheadline)
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.left").font(.footnote).foregroundStyle(.secondary)
            }.padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 44).background(Theme.input, in: RoundedRectangle(cornerRadius: 14))
                .overlay { RoundedRectangle(cornerRadius: 14).stroke(Theme.line, lineWidth: 1) }
        }.buttonStyle(.plain).disabled(busy)
    }
}
