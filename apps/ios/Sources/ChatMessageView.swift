import SwiftUI
import UIKit

struct InterruptedReplyNotice: View {
    var body: some View {
        Label("回复已中断", systemImage: "stop.circle")
            .font(.caption).foregroundStyle(.secondary)
            .accessibilityIdentifier("interrupted-reply")
    }
}

struct ChatMessageView: View {
    let text: String
    let label: String
    let isUser: Bool
    var fork: (() -> Void)? = nil
    let readAloud: () -> Void
    var usageDetails: ChatUsageDetails? = nil
    var selectionChanged: ((Bool) -> Void)? = nil
    @State private var selectingText = false
    @State private var copied = false
    @State private var showingUsage = false

    var body: some View {
        VStack(alignment: isUser ? .trailing : .leading, spacing: 6) {
            if isUser {
                HStack(alignment: .top) {
                    Spacer(minLength: 36)
                    UserMessageBubble(text: text, copy: copy, select: { selectingText = true }, fork: fork)
                }
            } else {
                Text(label).font(.caption).foregroundStyle(.secondary)
                MessageContent(text: text).frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 0) {
                    Button(action: copy) {
                        Label(copied ? String(localized: "已复制") : String(localized: "复制"), systemImage: copied ? "checkmark" : "doc.on.doc")
                    }.accessibilityIdentifier("copy-message")
                    Button { selectingText = true } label: {
                        Label("选择文本", systemImage: "text.cursor")
                    }.accessibilityIdentifier("select-message-text")
                    Button(action: readAloud) { Label("朗读", systemImage: "speaker.wave.2") }.accessibilityIdentifier("read-message")
                    ShareLink(item: text) { Label("分享", systemImage: "square.and.arrow.up") }
                    if usageDetails != nil {
                        Menu { Button("本轮用量") { showingUsage = true } } label: { Label("更多", systemImage: "ellipsis") }
                            .accessibilityIdentifier("message-more")
                    }
                }.labelStyle(.iconOnly).buttonStyle(MessageActionStyle())
            }
        }
        .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
        .modifier(MessageContextMenu(enabled: !isUser) {
            if let fork { Button(action: fork) { Label("从这里继续", systemImage: "arrow.triangle.branch") } }
            Button(action: copy) { Label("复制", systemImage: "doc.on.doc") }
            Button { selectingText = true } label: { Label("选择文本", systemImage: "text.cursor") }
            Button(action: readAloud) { Label("朗读", systemImage: "speaker.wave.2") }
            ShareLink(item: text) { Label("分享", systemImage: "square.and.arrow.up") }
            if usageDetails != nil { Button("本轮用量") { showingUsage = true } }
        })
        .sheet(isPresented: $selectingText) { MessageSelectionView(text: text) }
        .sheet(isPresented: $showingUsage) { usageDetails }
        .onChange(of: selectingText) { _, value in selectionChanged?(value) }
        .task(id: copied) {
            guard copied else { return }
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            copied = false
        }
    }
    private func copy() {
        UIPasteboard.general.string = text
        copied = true
    }
}

private struct MessageContextMenu<Actions: View>: ViewModifier {
    let enabled: Bool
    @ViewBuilder let actions: () -> Actions
    @ViewBuilder func body(content: Content) -> some View {
        if enabled { content.contextMenu(menuItems: actions) }
        else { content }
    }
}

private struct UserMessageBubble: View {
    let text: String
    let copy: () -> Void
    let select: () -> Void
    let fork: (() -> Void)?
    @State private var expanded = false
    private var collapsible: Bool { text.count > 320 || text.filter { $0 == "\n" }.count >= 6 }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: text).textSelection(.enabled)
                .lineLimit(collapsible && !expanded ? 6 : nil)
                .accessibilityIdentifier("user-message-text")
                .contextMenu {
                    if let fork { Button(action: fork) { Label("从这里继续", systemImage: "arrow.triangle.branch") } }
                    Button(action: copy) { Label("复制", systemImage: "doc.on.doc") }
                    Button(action: select) { Label("选择文本", systemImage: "text.cursor") }
                    ShareLink(item: text) { Label("分享", systemImage: "square.and.arrow.up") }
                }
            if collapsible {
                Button {
                    expanded.toggle()
                } label: {
                    Label(expanded ? String(localized: "收起") : String(localized: "展开"), systemImage: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundStyle(.secondary)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }.buttonStyle(.plain)
                    .accessibilityIdentifier(expanded ? "collapse-user-message" : "expand-user-message")
                    .accessibilityValue(expanded ? String(localized: "已展开") : String(localized: "已收起"))
            }
        }.padding(.horizontal, 16).padding(.vertical, 12)
            .background(Theme.input, in: RoundedRectangle(cornerRadius: 22))
            .onChange(of: text) { _, _ in expanded = false }
    }
}

private struct MessageActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.subheadline).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .frame(minWidth: 44, minHeight: 44)
            .foregroundStyle(.secondary)
            .background(configuration.isPressed ? Theme.input : Color.clear, in: Circle())
    }
}

private struct MessageSelectionView: View {
    let text: String
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    var body: some View {
        NavigationStack {
            SelectableMessageText(text: text)
                .background(Theme.surface)
                .navigationTitle("选择文本").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("完成") { dismiss() }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            UIPasteboard.general.string = text; copied = true
                        } label: {
                            Label(copied ? String(localized: "已复制") : String(localized: "全部复制"), systemImage: copied ? "checkmark" : "doc.on.doc").labelStyle(.iconOnly)
                        }.accessibilityIdentifier("copy-entire-message")
                    }
                }
        }
    }
}

/// A single native text surface lets selection span paragraphs and code blocks.
private struct SelectableMessageText: UIViewRepresentable {
    let text: String
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.backgroundColor = .clear
        view.font = .preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        view.textColor = .label
        view.textContainerInset = UIEdgeInsets(top: 20, left: 16, bottom: 24, right: 16)
        view.accessibilityIdentifier = "selectable-message-text"
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        if view.text != text { view.text = text }
    }
}
