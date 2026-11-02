import SwiftUI

/// Shared phone-chat composer; execution controls remain owned by the source.
struct ChatComposer<Controls: View>: View {
    @Binding var text: String
    let focused: FocusState<Bool>.Binding
    let storageError: String?
    let identifier: String
    @ViewBuilder let controls: () -> Controls
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 0) {
                TextField("说点什么…", text: $text, axis: .vertical)
                    .lineLimit(1...(dynamicTypeSize.isAccessibilitySize ? 2 : 7)).focused(focused)
                    .accessibilityLabel("消息").accessibilityIdentifier(identifier)
                if !text.isEmpty {
                    Button { focused.wrappedValue = false; expanded = true } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").dynamicTypeSize(...DynamicTypeSize.xxxLarge).frame(width: 44, height: 44)
                    }.buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel("展开输入")
                        .accessibilityIdentifier(identifier + "-expand")
                }
            }
            if text.utf16.count > 16000 { Text("内容过长，请缩短后重试。").font(.caption) }
            HStack(spacing: 8) {
                if focused.wrappedValue {
                    Button { focused.wrappedValue = false } label: {
                        Image(systemName: "keyboard.chevron.compact.down").frame(width: 44, height: 44)
                    }.buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel("收起键盘")
                }
                controls()
            }.dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        }.padding(12).background(Theme.input, in: RoundedRectangle(cornerRadius: 24))
            .overlay { RoundedRectangle(cornerRadius: 24).stroke(Theme.line, lineWidth: 1) }
            .padding(.horizontal, 16).padding(.bottom, 8).frame(maxWidth: 760)
            .frame(maxWidth: .infinity).background(Theme.surface)
            .sheet(isPresented: $expanded) { ExpandedComposerView(text: $text, storageError: storageError, disabled: false) }
    }
}

enum ChatInputPreferences {
    static let keepKeyboardKey = "keep-chat-keyboard"
}
