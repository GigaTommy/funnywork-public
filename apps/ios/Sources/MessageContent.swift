import SwiftUI

/// Block parsing keeps unfinished streamed fences readable and code whitespace intact.
struct MessageBlock: Equatable {
    enum Kind: Equatable { case literal, math, paragraph, heading(Int), quote, list(String), task(Bool), divider, code(String), table(MessageTable) }
    let kind: Kind
    let text: String
    var children: [MessageBlock] = []

    static func parse(_ source: String) -> [MessageBlock] { MessageMarkdown.parse(source) }
}

struct MessageContent: View {
    let text: String
    @State private var selectedLink: MessageWebLink?
    var body: some View {
        MessageBlockList(blocks: MessageBlock.parse(text))
        .textSelection(.enabled)
        .environment(\.openURL, OpenURLAction { url in
            guard let link = MessageWebLink(url) else { return .discarded }
            selectedLink = link
            return .handled
        })
        .sheet(item: $selectedLink) { link in MessageBrowser(link: link).ignoresSafeArea() }
    }
}

private struct MessageBlockList: View {
    let blocks: [MessageBlock]
    var depth = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block.kind {
                case .literal: Text(verbatim: block.text)
                case .math: MessageFormulaView(formula: MessageFormula(latex: block.text, display: true))
                case .paragraph: inline(block.text)
                case .heading(let level): inline(block.text).font(level <= 2 ? .title3.bold() : .headline)
                case .table(let table): MessageTableView(table: table)
                case .code(let language): CodeBlock(text: block.text, language: language)
                case .divider: Divider().accessibilityHidden(true)
                case .quote:
                    HStack(alignment: .top, spacing: 10) {
                        Rectangle().fill(.secondary.opacity(0.3)).frame(width: depth < 6 ? 3 : 0)
                        content(block)
                    }.fixedSize(horizontal: false, vertical: true).foregroundStyle(.secondary)
                case .list(let marker):
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(marker).frame(minWidth: 16, alignment: .trailing)
                        content(block)
                    }
                case .task(let checked):
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: checked ? "checkmark.square" : "square").foregroundStyle(.secondary)
                            .accessibilityLabel(checked ? String(localized: "已完成") : String(localized: "未完成"))
                        content(block)
                    }
                }
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func content(_ block: MessageBlock) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if !block.text.isEmpty { inline(block.text) }
            if !block.children.isEmpty { AnyView(MessageBlockList(blocks: block.children, depth: depth + 1)) }
        }
    }
    private func inline(_ text: String) -> some View { MessageInlineView(text: text) }
}

private struct CodeBlock: View {
    let text: String
    let language: String
    @State private var copied = false
    @State private var expanded = false
    private var isDiff: Bool { ["diff", "patch"].contains(language.lowercased().split(separator: " ").first.map(String.init) ?? "") }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(language.isEmpty ? String(localized: "代码") : language).lineLimit(1)
                Spacer()
                Button { expanded = true } label: { Label("全屏查看", systemImage: "arrow.up.left.and.arrow.down.right") }
                    .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                    .buttonStyle(.plain).accessibilityIdentifier("expand-code")
                Button {
                    UIPasteboard.general.string = text
                    copied = true
                } label: { Label(copied ? String(localized: "已复制") : String(localized: "复制"), systemImage: copied ? "checkmark" : "doc.on.doc") }
                    .frame(minHeight: 44).buttonStyle(.plain).accessibilityIdentifier("copy-code")
            }.font(.caption).foregroundStyle(.secondary)
            if isDiff { MessageDiffView(text: text) }
            else { ScrollView(.horizontal) {
                Text(verbatim: text).font(.system(.callout, design: .monospaced))
                    .fixedSize(horizontal: true, vertical: false).textSelection(.enabled)
            } }
        }.padding(12).background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
            .fullScreenCover(isPresented: $expanded) { CodeReadingView(text: text, language: language) }
            .task(id: copied) {
                guard copied else { return }
                do { try await Task.sleep(for: .seconds(2)); copied = false } catch { }
            }
    }
}
