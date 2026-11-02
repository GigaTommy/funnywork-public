import SwiftUI

struct ForkConversationView: View {
    @ObservedObject var model: ForkConversationModel
    let sequence: Int?
    let preview: String
    let opened: (Conversation) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @State private var showingClear = false
    private var statusLabel: String {
        if ["queued", "claimed"].contains(model.status) { return String(localized: "正在创建对话…") }
        if ["rejected", "expired"].contains(model.status) { return String(localized: "未创建对话") }
        return String(localized: "创建结果待确认")
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "arrow.triangle.branch").font(.largeTitle).foregroundStyle(.secondary)
                if model.pending == nil || model.pending?.sequence == sequence {
                    Text(preview).lineLimit(5).frame(maxWidth: .infinity, alignment: .leading)
                }
                if let error = model.error { Text(error).font(.callout).foregroundStyle(.secondary) }
                if model.busy { ProgressView() }
                if model.pending != nil {
                    Text(statusLabel)
                        .font(.callout).accessibilityIdentifier("fork-status")
                    Button("刷新") { Task { await model.refresh() } }.disabled(model.busy)
                    if model.canRetry { Button("重试") { Task { await model.retry() } }.accessibilityIdentifier("retry-fork") }
                    Button("核对并清除") { showingClear = true }.disabled(model.busy)
                } else if let sequence {
                    Button("创建对话") { Task { await model.create(at: sequence) } }
                        .buttonStyle(PrimaryActionStyle()).disabled(model.busy)
                        .accessibilityIdentifier("create-fork")
                }
                Spacer(minLength: 0)
            }.padding(24).background(Theme.surface)
                .navigationTitle("从这里继续").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarLeading) { Button("完成") { dismiss() } } }
                .confirmationDialog("清除待确认操作？", isPresented: $showingClear, titleVisibility: .visible) {
                    Button("确认清除", role: .destructive) { model.clearAfterReview() }
                } message: { Text("清除不会撤回已发送的操作，请先确认执行结果。") }
                .task(id: ForkPollingKey(active: phase == .active, requestID: model.pending?.id)) {
                    guard phase == .active else { model.deactivate(); return }
                    await model.refresh()
                    while !Task.isCancelled && model.pending != nil {
                        do { try await Task.sleep(for: .seconds(2)) } catch { return }
                        await model.refresh()
                    }
                }
                .onChange(of: model.created?.id) { _, _ in
                    guard phase == .active, let row = model.created else { return }
                    opened(row); dismiss()
                }
        }.onDisappear { model.deactivate() }
    }
}

private struct ForkPollingKey: Equatable { let active: Bool; let requestID: String? }
