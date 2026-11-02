import Foundation
import Markdown

/// Converts the standard Markdown tree into native message blocks; HTML stays literal.
struct MessageMarkdown {
    private let math: MessageMathSource
    static func parse(_ source: String) -> [MessageBlock] {
        let math = MessageMathSource.prepare(source)
        return Self(math: math).children(Document(parsing: math.text, options: [.disableSmartOpts]), depth: 0)
    }
    private func escaped(_ text: String) -> String {
        text.map { $0 == "\u{E000}" ? "&#xE000;" : "\\`*_{}[]<>!|~&".contains($0) ? "\\" + String($0) : String($0) }.joined()
    }
    private func inline(_ node: Markup) -> String {
        node.children.map { child in
            switch child {
            case let text as Markdown.Text:
                return escaped(text.string)
            case is SoftBreak, is LineBreak: return "\n"
            case let code as InlineCode:
                let longest = code.code.split(whereSeparator: { $0 != "`" }).map(\.count).max() ?? 0
                let fence = String(repeating: "`", count: longest + 1)
                let padding = code.code.allSatisfy { $0 == " " } ? "" : " "
                return fence + padding + code.code + padding + fence
            case is Strong: return "**" + inline(child) + "**"
            case is Emphasis: return "*" + inline(child) + "*"
            case is Strikethrough: return "~~" + inline(child) + "~~"
            case let link as Link:
                let destination = (link.destination ?? "").replacingOccurrences(of: "<", with: "%3C")
                    .replacingOccurrences(of: ">", with: "%3E").replacingOccurrences(of: "\\", with: "%5C")
                return "[" + inline(child) + "](<" + destination + ">)"
            case is Image: return inline(child)
            case let html as InlineHTML:
                return escaped(html.rawHTML)
            default: return inline(child)
            }
        }.joined().replacingMath(using: math)
    }
    private func children(_ node: Markup, depth: Int) -> [MessageBlock] {
        node.children.flatMap { convert($0, depth: depth) }
    }
    private func container(_ node: Markup, kind: MessageBlock.Kind, depth: Int) -> MessageBlock {
        var contents = children(node, depth: depth + 1)
        let text: String
        if contents.first?.kind == .paragraph { text = contents.removeFirst().text }
        else { text = "" }
        return MessageBlock(kind: kind, text: text, children: contents)
    }
    private func convert(_ node: Markup, depth: Int) -> [MessageBlock] {
        guard depth < 6 else { return [.init(kind: .literal, text: MessageMathSource.segments(math.restore(node.detachedFromParent.format())).map { $0.formula?.source ?? $0.text }.joined())] }
        switch node {
        case let node as Paragraph:
            let value = inline(node)
            let parts = MessageMathSource.segments(value)
            guard parts.contains(where: { $0.formula?.display == true }) else { return [.init(kind: .paragraph, text: value)] }
            var result: [MessageBlock] = [], paragraph = ""
            for part in parts {
                if let formula = part.formula, formula.display {
                    if !paragraph.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { result.append(.init(kind: .paragraph, text: paragraph)); paragraph = "" }
                    result.append(.init(kind: .math, text: formula.latex))
                } else { paragraph += part.text }
            }
            if !paragraph.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { result.append(.init(kind: .paragraph, text: paragraph)) }
            return result
        case let node as Heading: return [.init(kind: .heading(node.level), text: inline(node))]
        case let node as CodeBlock:
            let code = node.code.hasSuffix("\n") ? String(node.code.dropLast()) : node.code
            return [.init(kind: .code(node.language ?? ""), text: code)]
        case is ThematicBreak: return [.init(kind: .divider, text: "")]
        case let node as HTMLBlock: return [.init(kind: .literal, text: node.rawHTML)]
        case let node as BlockQuote: return [container(node, kind: .quote, depth: depth)]
        case let node as UnorderedList:
            return node.listItems.map { item in
                container(item, kind: item.checkbox.map { .task($0 == .checked) } ?? .list("•"), depth: depth)
            }
        case let node as OrderedList:
            return node.listItems.enumerated().map { offset, item in
                container(item, kind: item.checkbox.map { .task($0 == .checked) } ?? .list("\(node.startIndex + UInt(offset))."), depth: depth)
            }
        case let node as Table:
            let headers = Array(node.head.cells.map(inline))
            let rows = Array(node.body.rows.map { row in
                Array((Array(row.cells.map(inline)) + Array(repeating: "", count: headers.count)).prefix(headers.count))
            })
            let alignments: [MessageTable.Alignment] = node.columnAlignments.map {
                switch $0 { case .center: return .center; case .right: return .trailing; default: return .leading }
            }
            return [.init(kind: .table(MessageTable(headers: headers, alignments: alignments, rows: rows)), text: MessageMathSource.segments(math.restore(node.detachedFromParent.format())).map { $0.formula?.source ?? $0.text }.joined())]
        default: return [.init(kind: .literal, text: MessageMathSource.segments(math.restore(node.detachedFromParent.format())).map { $0.formula?.source ?? $0.text }.joined())]
        }
    }
}

private extension String {
    func replacingMath(using source: MessageMathSource) -> String { source.restore(self) }
}
