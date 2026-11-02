import SwiftUI

struct ExpandedComposerView: View {
    @Binding var text: String
    let storageError: String?
    let disabled: Bool
    var title: LocalizedStringKey = "编辑消息"
    var editorLabel: LocalizedStringKey = "消息"
    var editorIdentifier = "expanded-composer-text"
    var closeIdentifier = "close-expanded-composer"
    var context: String? = nil
    var maximumUTF16Count: Int? = 16000
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let context {
                    Text(verbatim: context).font(.caption).foregroundStyle(.secondary).lineLimit(3).frame(maxWidth: .infinity, alignment: .leading).padding(12)
                }
                TextEditor(text: $text)
                    .font(.body).scrollContentBackground(.hidden)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .focused($focused).disabled(disabled)
                    .accessibilityLabel(Text(editorLabel)).accessibilityIdentifier(editorIdentifier)
                if let storageError { Text(storageError).font(.caption).foregroundStyle(.red).padding(12) }
                if let maximumUTF16Count, text.utf16.count > maximumUTF16Count {
                    Text("内容过长，请缩短后重试。").font(.caption).foregroundStyle(.red).padding(12)
                }
            }.background(Theme.surface)
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("完成") { focused = false; dismiss() }.accessibilityIdentifier(closeIdentifier)
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        if focused {
                            Button { focused = false } label: { Label("收起键盘", systemImage: "keyboard.chevron.compact.down").labelStyle(.iconOnly) }
                                .accessibilityIdentifier("expanded-composer-hide-keyboard")
                        }
                    }
                }
        }.onAppear { focused = true }
    }
}
