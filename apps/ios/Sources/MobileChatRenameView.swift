import SwiftUI

struct MobileChatRenameView: View {
    @ObservedObject var history: MobileChatHistoryModel
    @State private var chat: MobileChatSummary
    @State private var title: String
    @State private var checking = false
    @Environment(\.dismiss) private var dismiss
    init(chat: MobileChatSummary, history: MobileChatHistoryModel) {
        self.history = history; _chat = State(initialValue: chat); _title = State(initialValue: chat.title)
    }
    var body: some View {
        NavigationStack {
            Form {
                TextField("对话名称", text: $title).accessibilityIdentifier("mobile-history-title")
                if title.utf16.count > 160 { Text("名称过长，请缩短后重试。").font(.caption).foregroundStyle(.secondary) }
                if let error = history.operationError { Text(error).font(.callout).foregroundStyle(.secondary) }
                if history.pending != nil {
                    Text("历史操作结果待确认")
                    Button("核对结果") { Task { if await history.reconcile() { dismiss() } } }.disabled(history.updating)
                } else {
                    Button("重新核对") {
                        Task { checking = true; defer { checking = false }; if let latest = await history.current(chat.id) { chat = latest } }
                    }.disabled(checking || history.updating)
                }
            }.scrollContentBackground(.hidden).background(Theme.surface).navigationTitle("重命名").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("保存") { Task { if await history.change(chat, title: title) { dismiss() } } }
                            .disabled(!history.canUpdate || checking || title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || title.utf16.count > 160)
                    }
                }
        }.interactiveDismissDisabled(history.updating)
    }
}
