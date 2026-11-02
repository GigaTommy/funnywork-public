import SwiftUI

/// References immutable synced messages, never an inferred working-tree revision.
struct RemoteResultReview: Identifiable, Codable, Equatable {
    let id: Int
    let status: HistoryActivity?
    let messages: [HistoryItem]
    let changes: [ResultChange]
    init?(items: [HistoryItem]) {
        let sorted = items.sorted { $0.id < $1.id }
        let end = sorted.last { $0.eventType == "turn/end" }
        let limit = end?.id ?? sorted.last?.id ?? -1
        let boundary = sorted.last { $0.id < limit && ($0.eventType == "turn/start" || $0.eventType == "turn/end") }?.id ?? -1
        var gapBoundary = 0
        for index in sorted.indices.dropFirst() {
            if sorted[index].id <= limit && sorted[index].id > sorted[index - 1].id + 1 { gapBoundary = sorted[index].id }
        }
        let start = max(boundary, gapBoundary - 1)
        let replies = sorted.filter { $0.id > start && $0.id <= limit && $0.eventType == "assistant/message" && (!$0.text.isEmpty || !$0.attachments.isEmpty) }
        guard end != nil || !replies.isEmpty else { return nil }
        id = end?.id ?? replies.last!.id; status = end?.activity; messages = replies
        changes = replies.flatMap { message in ResultChange.parse(message: message) }
    }
    var key: String { String(id) }
    var sourceDescription: String {
        messages.isEmpty ? String(localized: "本轮没有已同步回复") : String(localized: "对应已同步消息") + " " + messages.map { "#\($0.id)" }.joined(separator: ", ")
    }
    /// A literal excerpt from the latest reply, never a generated outcome summary.
    var replyExcerpt: String? {
        guard let latest = messages.last else { return nil }
        let prose = MessageBlock.parse(latest.text).first { block in
            if case .paragraph = block.kind { return !block.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            return false
        }
        return prose.map { String($0.text.prefix(600)) }
    }
    func feedback(_ comment: String) -> String {
        String(localized: "请根据以下审阅意见继续修改：") + "\n" +
        String(localized: "对应已同步消息") + " " + (messages.isEmpty ? ["#\(id)"] : messages.map { "#\($0.id)" }).joined(separator: ", ") + "\n" + comment
    }
}

struct ResultChange: Identifiable, Codable, Equatable {
    let id: String
    let sequence: Int
    let path: String
    let text: String
    var added: Int { MessageDiffLine.parse(text).filter { $0.kind == .added }.count }
    var removed: Int { MessageDiffLine.parse(text).filter { $0.kind == .removed }.count }
    static func parse(message: HistoryItem) -> [Self] {
        func diffs(_ blocks: [MessageBlock]) -> [String] {
            blocks.flatMap { block -> [String] in
                var values = diffs(block.children)
                if case .code(let language) = block.kind,
                   ["diff", "patch"].contains(language.lowercased().split(separator: " ").first.map(String.init) ?? "") {
                    values.insert(block.text, at: 0)
                }
                return values
            }
        }
        return diffs(MessageBlock.parse(message.text)).enumerated().flatMap { index, diff -> [Self] in
            // Separate multi-file patches at the standard git boundary. Preserve raw source.
            var segments: [String] = []; var current: [String] = []
            let parsed = MessageDiffLine.parse(diff)
            for (offset, line) in parsed.enumerated() {
                let nextIsFileHeader = offset + 1 < parsed.count && parsed[offset + 1].kind == .header && parsed[offset + 1].text.hasPrefix("+++ ")
                let fileBoundary = line.kind == .header && line.text.hasPrefix("--- ") && nextIsFileHeader && current.contains { $0.hasPrefix("+++ ") }
                if (line.text.hasPrefix("diff --git ") || fileBoundary), !current.isEmpty {
                    segments.append(current.joined(separator: "\n")); current = []
                }
                current.append(line.text)
            }
            if !current.isEmpty { segments.append(current.joined(separator: "\n")) }
            return segments.enumerated().map { part, text in
                let lines = text.components(separatedBy: "\n")
                let header = lines.first { $0.hasPrefix("+++ ") && $0 != "+++ /dev/null" }
                    ?? lines.first { $0.hasPrefix("--- ") && $0 != "--- /dev/null" }
                var path = header.map { String($0.dropFirst(4)).components(separatedBy: "\t")[0] } ?? String(localized: "变更片段")
                if path.hasPrefix("a/") || path.hasPrefix("b/") { path = String(path.dropFirst(2)) }
                return Self(id: "\(message.id)-\(index)-\(part)", sequence: message.id, path: path, text: text)
            }
        }
    }
    func reference(_ line: MessageDiffLine) -> String {
        let side = line.newLine != nil ? String(localized: "新行") : String(localized: "旧行")
        let number = line.newLine ?? line.oldLine
        return "#\(sequence) · \(path)" + (number.map { " · \(side) \($0)" } ?? "") + "\n> " + line.text + "\n"
    }
}

struct RemoteResultReviewView: View {
    let result: RemoteResultReview
    @ObservedObject var model: ConversationModel
    let attachmentService: AttachmentService?
    let store: ConversationStore
    let sessionID: String
    let authFailure: (Error) -> Void
    @State private var selectedAttachment: ChatAttachment?
    @Environment(\.dismiss) private var dismiss
    @State private var selected: ResultChange?
    @State private var repliesExpanded = false
    @State private var expandedFeedback = false
    @State private var mergePreview = false
    @FocusState private var feedbackFocused: Bool
    private var comment: String { model.saved.reviewDrafts[result.key] ?? "" }
    private var hasFeedback: Bool { !comment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !model.pendingReviewNotes(result.key).isEmpty }
    private var feedbackText: String { result.feedback(ConversationModel.appendingDraft(model.pendingReviewNotes(result.key), to: comment)) }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let status = result.status {
                        Label(status.title, systemImage: status.symbol).font(.headline)
                        if let detail = status.detail, !detail.isEmpty { Text(verbatim: detail).font(.callout).textSelection(.enabled) }
                    } else {
                        Label("已同步回复", systemImage: "text.bubble").font(.headline)
                    }
                    Text(verbatim: result.sourceDescription)
                        .font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("review-source")
                    Text("这里展示已同步回复中的变更片段，电脑上的文件可能已有后续修改。")
                        .font(.caption).foregroundStyle(.secondary)
                    if !result.changes.isEmpty {
                        Text("变更").font(.headline)
                        ForEach(result.changes) { change in
                            Button { selected = change; feedbackFocused = false } label: {
                                HStack {
                                    Label(change.path, systemImage: "doc.text").lineLimit(2)
                                    Spacer()
                                    Text(verbatim: "+\(change.added) −\(change.removed)").monospacedDigit()
                                    Image(systemName: "chevron.right")
                                }.frame(minHeight: 44).contentShape(Rectangle())
                            }.accessibilityIdentifier("review-change-" + change.id)
                        }
                    }
                    if let excerpt = result.replyExcerpt {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("回复摘录").font(.caption).foregroundStyle(.secondary)
                            Text(verbatim: excerpt).lineLimit(3).accessibilityIdentifier("review-reply-excerpt")
                        }
                    }
                    ForEach(result.messages.filter { !$0.attachments.isEmpty }) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(verbatim: "#\(item.id)").font(.caption).foregroundStyle(.secondary)
                            ForEach(item.attachments) { file in
                                MessageAttachmentCard(file: file, service: attachmentService, store: store, sessionID: sessionID, authFailure: authFailure) { selectedAttachment = file }
                            }
                        }
                    }
                    HStack {
                        Text("审阅意见").font(.headline)
                        Spacer()
                        Button { feedbackFocused = false; expandedFeedback = true } label: {
                            Label("展开编辑", systemImage: "arrow.up.left.and.arrow.down.right").labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                        }.accessibilityIdentifier("expand-review-comment")
                            .fullScreenCover(isPresented: $expandedFeedback) {
                                ExpandedComposerView(text: Binding(get: { comment }, set: { model.updateReviewDraft($0, key: result.key, result: result) }), storageError: model.storageError, disabled: false,
                                    title: "编辑审阅意见", editorLabel: "审阅意见", editorIdentifier: "expanded-review-text", closeIdentifier: "close-expanded-review",
                                    context: result.sourceDescription + "\n" + String(localized: "完成后返回，内容不会自动发送。"), maximumUTF16Count: nil)
                            }
                    }
                    TextField("写下需要调整的地方…", text: Binding(get: { comment }, set: { model.updateReviewDraft($0, key: result.key, result: result) }), axis: .vertical)
                        .lineLimit(3...10).focused($feedbackFocused).padding(12).background(Theme.input, in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityIdentifier("review-comment")
                    if !model.pendingReviewNotes(result.key).isEmpty {
                        Text("待加入的补充意见").font(.caption).foregroundStyle(.secondary)
                        Text(verbatim: model.pendingReviewNotes(result.key)).font(.callout).textSelection(.enabled)
                    }
                    if comment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { Text("先写下审阅意见。").font(.caption).foregroundStyle(.secondary) }
                    if let error = model.storageError { Text(error).font(.callout) }
                    if !result.messages.isEmpty {
                        DisclosureGroup("完整回复", isExpanded: $repliesExpanded) {
                            VStack(alignment: .leading, spacing: 20) {
                                ForEach(result.messages) { item in
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(verbatim: "#\(item.id)").font(.caption).foregroundStyle(.secondary)
                                        MessageContent(text: item.text)
                                    }
                                }
                            }.padding(.top, 12)
                        }.accessibilityIdentifier("review-full-replies")
                    }
                }.padding()
            }.accessibilityIdentifier("review-content").background(Theme.surface)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if !feedbackFocused {
                        VStack(spacing: 8) {
                            Text("意见会追加到现有草稿，确认后发送。").font(.caption).foregroundStyle(.secondary)
                            Button { mergePreview = true } label: {
                                Label("预览合并", systemImage: "eye").frame(minHeight: 44)
                            }.disabled(!hasFeedback).accessibilityIdentifier("preview-review-merge")
                                .fullScreenCover(isPresented: $mergePreview) {
                                    TaskContentPreviewView(title: "合并预览", context: String(localized: "预览不会修改草稿，返回后可选择加入草稿。"), text: ConversationModel.appendingDraft(feedbackText, to: model.draft))
                                }
                            Button {
                                stageFeedback()
                            } label: { Text("加入草稿").frame(maxWidth: .infinity) }
                                .buttonStyle(PrimaryActionStyle())
                                .disabled(!hasFeedback)
                                .accessibilityIdentifier("stage-review-feedback")
                        }.padding().background(Theme.surface).overlay(alignment: .top) { Divider() }
                    }
                }
                .navigationTitle("审阅结果").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } }
                    ToolbarItemGroup(placement: .keyboard) {
                        Button("加入草稿") { stageFeedback() }
                            .disabled(!hasFeedback)
                            .accessibilityIdentifier("stage-review-feedback")
                        Spacer(); Button("收起键盘") { feedbackFocused = false }
                    }
                }
                .sheet(item: $selectedAttachment) { file in
                    if let attachmentService {
                        AttachmentView(service: attachmentService, store: store, sessionID: sessionID, reference: file, authFailure: authFailure)
                    }
                }
                .sheet(item: $selected) { change in
                    ReviewFileView(change: change, result: result, model: model, switchFile: { selected = $0 }) { selected = nil }.id(change.id)
                }
        }
    }
    private func stageFeedback() {
        feedbackFocused = false
        if model.stageReviewFeedback(feedbackText, key: result.key) { dismiss() }
    }

}
