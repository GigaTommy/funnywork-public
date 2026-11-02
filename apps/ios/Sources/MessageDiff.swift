import SwiftUI

struct MessageDiffLine: Equatable {
    enum Kind: Equatable { case context, added, removed, header }
    let text: String
    let kind: Kind
    let oldLine: Int?
    let newLine: Int?

    static func parse(_ text: String) -> [Self] {
        let header = try! NSRegularExpression(pattern: #"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@"#)
        var old = 0, new = 0, oldRemaining = 0, newRemaining = 0
        return text.components(separatedBy: "\n").map { line in
            if let match = header.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) {
                func number(_ index: Int, fallback: Int = 1) -> Int? {
                    guard let range = Range(match.range(at: index), in: line) else { return fallback }
                    guard let value = Int(line[range]), value <= 1_000_000_000 else { return nil }
                    return value
                }
                if let a = number(1), let b = number(2), let c = number(3), let d = number(4) {
                    old = a; new = c; oldRemaining = b; newRemaining = d
                } else { oldRemaining = 0; newRemaining = 0 }
                return Self(text: line, kind: .header, oldLine: nil, newLine: nil)
            }
            let inHunk = oldRemaining > 0 || newRemaining > 0
            if line.hasPrefix("\\") { return Self(text: line, kind: .header, oldLine: nil, newLine: nil) }
            if line.hasPrefix("+"), inHunk || !line.hasPrefix("+++") {
                let number = newRemaining > 0 ? new : nil
                if newRemaining > 0 { new += 1; newRemaining -= 1 }
                return Self(text: line, kind: .added, oldLine: nil, newLine: number)
            }
            if line.hasPrefix("-"), inHunk || !line.hasPrefix("---") {
                let number = oldRemaining > 0 ? old : nil
                if oldRemaining > 0 { old += 1; oldRemaining -= 1 }
                return Self(text: line, kind: .removed, oldLine: number, newLine: nil)
            }
            if line.hasPrefix(" "), inHunk {
                let before = oldRemaining > 0 ? old : nil
                let after = newRemaining > 0 ? new : nil
                if oldRemaining > 0 { old += 1; oldRemaining -= 1 }
                if newRemaining > 0 { new += 1; newRemaining -= 1 }
                return Self(text: line, kind: .context, oldLine: before, newLine: after)
            }
            oldRemaining = 0; newRemaining = 0
            let metadata = line.hasPrefix("diff ") || line.hasPrefix("index ") || line.hasPrefix("---") || line.hasPrefix("+++") || line.hasPrefix("@@")
            return Self(text: line, kind: metadata ? .header : .context, oldLine: nil, newLine: nil)
        }
    }
}

struct MessageDiffView: View {
    let text: String
    @ScaledMetric(relativeTo: .caption) private var gutterWidth = 44
    var body: some View {
        let lines = MessageDiffLine.parse(text)
        let numbered = lines.contains { $0.oldLine != nil || $0.newLine != nil }
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        if numbered {
                            Text(line.oldLine.map(String.init) ?? "").frame(width: gutterWidth, alignment: .trailing)
                            Text(line.newLine.map(String.init) ?? "").frame(width: gutterWidth, alignment: .trailing)
                        }
                        Text(verbatim: line.text.isEmpty ? " " : line.text)
                            .foregroundStyle(.primary).textSelection(.enabled)
                    }
                    .font(.system(.callout, design: .monospaced)).foregroundStyle(.secondary)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(line.kind == .added ? Theme.accent.opacity(0.10) : line.kind == .removed ? Theme.accent.opacity(0.04) : Color.clear)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(label(line))
                }
            }.fixedSize(horizontal: true, vertical: false)
        }.accessibilityIdentifier("message-diff")
    }
    private func label(_ line: MessageDiffLine) -> String {
        switch line.kind {
        case .added: return String(localized: "新增") + ": " + line.text
        case .removed: return String(localized: "删除") + ": " + line.text
        case .header, .context: return line.text
        }
    }
}
