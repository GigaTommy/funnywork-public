import Foundation
import Markdown

struct MessageFormula: Equatable {
    let latex: String
    let display: Bool
    var token: String { "\u{E000}" + (display ? "d:" : "i:") + Data(latex.utf8).base64EncodedString() + "\u{E001}" }
    var source: String { display ? "\\[" + latex + "\\]" : "\\(" + latex + "\\)" }
}

/// Protect code and links before CommonMark can consume TeX delimiters and backslashes.
struct MessageMathSource {
    let text: String
    let formulas: [String: MessageFormula]
    var literals: [String: String] = [:]
    func restore(_ value: String) -> String {
        var result = value
        for (token, formula) in formulas { result = result.replacingOccurrences(of: token, with: formula.token) }
        for (token, literal) in literals {
            let escaped = literal.map { "\\`*_{}[]<>!|~&".contains($0) ? "\\" + String($0) : String($0) }.joined()
            result = result.replacingOccurrences(of: token, with: escaped)
        }
        return result
    }
    static func prepare(_ source: String) -> Self {
        guard source.contains("$") || source.contains("\\(") || source.contains("\\[") else { return Self(text: source, formulas: [:]) }
        let bytes = Array(source.utf8)
        var lines = [0]
        for (i, byte) in bytes.enumerated() where byte == 10 { lines.append(i + 1) }
        func offset(_ location: SourceLocation) -> Int {
            guard location.line > 0, location.line <= lines.count else { return bytes.count }
            return min(bytes.count, lines[location.line - 1] + max(0, location.column - 1))
        }
        var protected: [Range<Int>] = []
        func visit(_ node: Markup) {
            if node is CodeBlock || node is InlineCode || node is Link || node is Image || node is HTMLBlock || node is InlineHTML {
                if let range = node.range { protected.append(offset(range.lowerBound)..<offset(range.upperBound)) }
                return
            }
            for child in node.children { visit(child) }
        }
        visit(Document(parsing: source, options: [.disableSmartOpts]))
        protected.sort { $0.lowerBound < $1.lowerBound }
        let prefix = "FWMATH" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        var formulas: [String: MessageFormula] = [:], literals: [String: String] = [:], output: [UInt8] = []
        var index = 0, protectedIndex = 0
        func escaped(_ position: Int) -> Bool {
            var at = position, count = 0
            while at > 0 && bytes[at - 1] == 92 { count += 1; at -= 1 }
            return !count.isMultiple(of: 2)
        }
        while index < bytes.count {
            while protectedIndex < protected.count && protected[protectedIndex].upperBound <= index { protectedIndex += 1 }
            if protectedIndex < protected.count, protected[protectedIndex].contains(index) {
                let end = protected[protectedIndex].upperBound
                output.append(contentsOf: bytes[index..<end]); index = end; continue
            }
            let boundary = protectedIndex < protected.count ? protected[protectedIndex].lowerBound : bytes.count
            var delimiter = 0, close: [UInt8] = [], display = false
            if !escaped(index), index + 1 < boundary, bytes[index] == 92, [40, 91].contains(bytes[index + 1]) {
                delimiter = 2; display = bytes[index + 1] == 91; close = [92, display ? 93 : 41]
            } else if bytes[index] == 36, !escaped(index) {
                if index + 1 < boundary, bytes[index + 1] == 36 { delimiter = 2; display = true; close = [36, 36] }
                else if index + 1 < boundary, ![9, 10, 13, 32, 36].contains(bytes[index + 1]), index == 0 || !((48...57).contains(bytes[index - 1]) || (65...90).contains(bytes[index - 1]) || (97...122).contains(bytes[index - 1])) {
                    delimiter = 1; close = [36]
                }
            }
            guard delimiter > 0, formulas.count < 128 else { output.append(bytes[index]); index += 1; continue }
            let limit = delimiter == 2 ? bytes.count : boundary
            var end = index + delimiter, found = false
            while end + close.count <= limit {
                if delimiter == 1 && bytes[end] == 10 { break }
                if !escaped(end), Array(bytes[end..<(end + close.count)]) == close { found = true; break }
                end += 1
            }
            if found {
                let latex = String(decoding: bytes[(index + delimiter)..<end], as: UTF8.self)
                let trimmed = latex.trimmingCharacters(in: .whitespacesAndNewlines)
                let currency = delimiter == 1 && (latex.last?.isWhitespace == true || (end + 1 < bytes.count && (48...57).contains(bytes[end + 1])))
                if !trimmed.isEmpty && !currency {
                    if latex.utf8.count > 4096 {
                        let token = prefix + "L" + String(literals.count) + "Z"
                        literals[token] = String(decoding: bytes[index..<(end + close.count)], as: UTF8.self)
                        output.append(contentsOf: token.utf8); index = end + close.count; continue
                    }
                    let token = prefix + "N" + String(formulas.count) + "Z"
                    formulas[token] = MessageFormula(latex: latex, display: display)
                    output.append(contentsOf: token.utf8); index = end + close.count; continue
                }
            }
            if !found && delimiter == 2 {
                let token = prefix + "L" + String(literals.count) + "Z"
                literals[token] = String(decoding: bytes[index..<limit], as: UTF8.self)
                output.append(contentsOf: token.utf8); index = limit; continue
            }
            output.append(bytes[index]); index += 1
        }
        return Self(text: String(decoding: output, as: UTF8.self), formulas: formulas, literals: literals)
    }
    /// Read only the formula markers restored by the Markdown adapter; code remains literal.
    static func segments(_ source: String) -> [(text: String, formula: MessageFormula?)] {
        let bytes = Array(source.utf8)
        var result: [(String, MessageFormula?)] = [], index = 0, start = 0
        while index + 1 < bytes.count {
            if bytes[index] == 96 {
                var width = 1
                while index + width < bytes.count && bytes[index + width] == 96 { width += 1 }
                var end = index + width
                while end < bytes.count {
                    if bytes[end] == 96 {
                        var count = 1
                        while end + count < bytes.count && bytes[end + count] == 96 { count += 1 }
                        if count == width { index = end + count; break }
                        end += count
                    } else { end += 1 }
                }
                if end == bytes.count { index += width }
                continue
            }
            let opening: [UInt8] = [0xEE, 0x80, 0x80], closing: [UInt8] = [0xEE, 0x80, 0x81]
            guard index + 3 <= bytes.count, Array(bytes[index..<(index + 3)]) == opening else { index += 1; continue }
            var end = index + 3
            while end + 3 <= bytes.count && end - index <= 5500 && Array(bytes[end..<(end + 3)]) != closing { end += 1 }
            guard end + 3 <= bytes.count, Array(bytes[end..<(end + 3)]) == closing else { index += 3; continue }
            let payload = String(decoding: bytes[(index + 3)..<end], as: UTF8.self)
            guard payload.hasPrefix("i:") || payload.hasPrefix("d:"), let data = Data(base64Encoded: String(payload.dropFirst(2))), let latex = String(data: data, encoding: .utf8) else { index = end + 3; continue }
            if start < index { result.append((String(decoding: bytes[start..<index], as: UTF8.self), nil)) }
            let formula = MessageFormula(latex: latex, display: payload.hasPrefix("d:"))
            result.append((formula.token, formula)); index = end + 3; start = index
        }
        if start < bytes.count { result.append((String(decoding: bytes[start...], as: UTF8.self), nil)) }
        return result
    }
}
