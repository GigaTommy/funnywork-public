import SwiftUI

struct ReviewDraftRecoveryView: View {
    @ObservedObject var model: ConversationModel
    let open: (RemoteResultReview) -> Void
    @State private var loading: String?
    @State private var request: Task<Void, Never>?
    var body: some View {
        if !model.unsubmittedReviewKeys.isEmpty {
            Section("未提交审阅意见") {
                ForEach(model.unsubmittedReviewKeys, id: \.self) { key in
                    Button {
                        loading = key
                        request = Task {
                            let value = await model.recoverReview(key)
                            guard !Task.isCancelled else { return }
                            loading = nil
                            if let value { open(value) }
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Label("继续审阅", systemImage: "square.and.pencil")
                                Spacer()
                                if loading == key { ProgressView() }
                            }
                            Text(verbatim: ConversationModel.appendingDraft(model.pendingReviewNotes(key), to: model.saved.reviewDrafts[key] ?? "")).font(.caption).lineLimit(2).foregroundStyle(.secondary)
                        }.frame(minHeight: 44).contentShape(Rectangle())
                    }.disabled(loading != nil).accessibilityIdentifier("resume-review-" + key)
                    if model.reviewSnapshot(key) == nil {
                        Button("将保留的意见加入草稿") {
                            _ = model.stageReviewFeedback(String(localized: "对应已同步消息") + " #" + key + "\n" + ConversationModel.appendingDraft(model.pendingReviewNotes(key), to: model.saved.reviewDrafts[key] ?? ""), key: key)
                        }.font(.caption)
                    }
                }
                if let error = model.error { Text(error).font(.caption) }
            }.listRowBackground(Theme.input)
                .onDisappear { request?.cancel(); loading = nil }
        }
    }
}

struct QueueEditRecoveryView: View {
    @ObservedObject var model: ConversationModel
    var body: some View {
        let edits = model.saved.queueEdits.values.filter { edit in
            !model.remote.queue.contains(where: { $0.id == edit.itemID })
        }.sorted { $0.itemID < $1.itemID }
        if !edits.isEmpty {
            DisclosureGroup("队列编辑副本") {
                ForEach(edits) { edit in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model.queueEditLabel(edit)).font(.caption).foregroundStyle(.secondary)
                        Text(verbatim: edit.text).font(.callout).lineLimit(3)
                        Button("加入草稿") { _ = model.appendDraft(edit.text) }.disabled(model.pending != nil || model.sending)
                        if model.queueEditWaiting(edit.itemID) {
                            Button("核对结果") { Task { await model.refresh() } }.disabled(model.refreshing)
                        }
                    }.padding(.vertical, 8)
                }
            }.accessibilityIdentifier("queue-edit-copies")
        }
    }
}
