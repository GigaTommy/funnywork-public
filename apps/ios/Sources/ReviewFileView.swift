import SwiftUI

private final class ReviewFileContent: ObservableObject {
    let lines: [MessageDiffLine]
    init(_ text: String) { lines = MessageDiffLine.parse(text) }
}

struct ReviewFileView: View {
    let change: ResultChange
    let result: RemoteResultReview
    @ObservedObject var model: ConversationModel
    let done: () -> Void
    let switchFile: (ResultChange) -> Void
    @StateObject private var content: ReviewFileContent
    @State private var selectedLine: Int?
    @FocusState private var focused: Bool
    private var comment: String { model.saved.reviewDrafts[result.key] ?? "" }
    private var note: String { model.saved.reviewFileNotes[result.key]?[change.id] ?? "" }
    init(change: ResultChange, result: RemoteResultReview, model: ConversationModel, switchFile: @escaping (ResultChange) -> Void = { _ in }, done: @escaping () -> Void) {
        self.change = change; self.result = result; self.model = model; self.switchFile = switchFile; self.done = done
        _content = StateObject(wrappedValue: ReviewFileContent(change.text))
    }
    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView([.vertical, .horizontal]) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(content.lines.enumerated()), id: \.offset) { index, line in
                            Button {
                                selectedLine = index
                                let reference = change.reference(line)
                                if !comment.contains(reference) {
                                    model.updateReviewDraft(ConversationModel.appendingDraft(reference, to: comment), key: result.key, result: result)
                                }
                            } label: {
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Text(line.oldLine.map(String.init) ?? "").frame(minWidth: 44, alignment: .trailing).foregroundStyle(.secondary)
                                    Text(line.newLine.map(String.init) ?? "").frame(minWidth: 44, alignment: .trailing).foregroundStyle(.secondary)
                                    Text(verbatim: line.text.isEmpty ? " " : line.text)
                                }.font(.system(.callout, design: .monospaced))
                                    .padding(.horizontal, 12).frame(minHeight: 44, alignment: .leading).contentShape(Rectangle())
                                    .background(selectedLine == index ? Theme.line : line.kind == .added ? Theme.accent.opacity(0.10) : Color.clear)
                            }.buttonStyle(.plain).disabled(line.oldLine == nil && line.newLine == nil)
                                .accessibilityLabel(change.reference(line))
                        }
                    }.fixedSize(horizontal: true, vertical: false)
                        .frame(minWidth: geometry.size.width, minHeight: geometry.size.height, alignment: .topLeading)
                }.accessibilityIdentifier("review-file-diff")
            }.background(Theme.surface)
                .navigationTitle(change.path).navigationBarTitleDisplayMode(.inline)
                .safeAreaInset(edge: .bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(verbatim: "#\(change.sequence)")
                            Spacer()
                            if let index = result.changes.firstIndex(where: { $0.id == change.id }), result.changes.count > 1 {
                                Text(verbatim: "\(index + 1) / \(result.changes.count)")
                            }
                        }.font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("review-file-source")
                        Text("可连续点选其他行，并在这里补充意见。").font(.caption).foregroundStyle(.secondary)
                        TextField("写下需要调整的地方…", text: Binding(get: { note }, set: { model.updateReviewFileNote($0, change: change, result: result) }), axis: .vertical)
                            .lineLimit(2...5).focused($focused).padding(12).background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier("review-file-comment")
                        if !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Button("添加意见") { _ = model.commitReviewFileNote(change, result: result) }
                        }
                        if let error = model.storageError { Text(error).font(.caption) }
                    }.padding().background(Theme.input)
                }
                .toolbar {
                    if result.changes.count > 1 {
                        ToolbarItem(placement: .topBarLeading) {
                            HStack(spacing: 0) {
                                Button { moveFile(-1) } label: { Label("上一个文件", systemImage: "chevron.left").labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44) }
                                    .disabled(adjacentFile(-1) == nil).accessibilityIdentifier("review-file-previous")
                                Button { moveFile(1) } label: { Label("下一个文件", systemImage: "chevron.right").labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44) }
                                    .disabled(adjacentFile(1) == nil).accessibilityIdentifier("review-file-next")
                            }
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) { Button { if model.commitReviewFileNote(change, result: result) { focused = false; done() } } label: { Label("查看意见", systemImage: "text.bubble").labelStyle(.iconOnly) }.accessibilityIdentifier("review-file-done") }
                    ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("收起键盘") { focused = false } }
                }
        }
    }
    private func adjacentFile(_ step: Int) -> ResultChange? {
        guard let index = result.changes.firstIndex(where: { $0.id == change.id }), result.changes.indices.contains(index + step) else { return nil }
        return result.changes[index + step]
    }
    private func moveFile(_ step: Int) {
        guard let next = adjacentFile(step), model.commitReviewFileNote(change, result: result) else { return }
        focused = false
        switchFile(next)
    }

}
