import SwiftUI
import UIKit
import iosMath

@MainActor enum MessageMathRenderer {
    final class Rendered: NSObject {
        let image: UIImage
        let descent: CGFloat
        init(image: UIImage, descent: CGFloat) { self.image = image; self.descent = descent }
    }
    private static let cache: NSCache<NSString, Rendered> = {
        let cache = NSCache<NSString, Rendered>(); cache.countLimit = 128; cache.totalCostLimit = 8 * 1024 * 1024; return cache
    }()
    static func render(_ formula: MessageFormula, size: CGFloat, dark: Bool) -> Rendered? {
        guard formula.latex.utf8.count <= 4096, size.isFinite, size > 0, size <= 80 else { return nil }
        var depth = 0, commands = 0, previous: Character?
        for character in formula.latex {
            if character == "\\" { commands += 1 }
            if previous != "\\" {
                if character == "{" { depth += 1 }
                if character == "}" { depth -= 1 }
            }
            guard depth >= 0, depth <= 24, commands <= 256 else { return nil }
            previous = character
        }
        guard depth == 0 else { return nil }
        let key = "\(size):\(dark):\(formula.display):" + formula.latex
        if let value = cache.object(forKey: key as NSString) { return value }
        let label = MTMathUILabel()
        label.displayErrorInline = false
        label.fontSize = size; label.mode = formula.display ? .display : .text
        label.textColor = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
        label.latex = formula.latex
        guard label.error == nil, label.mathList != nil else { return nil }
        let measured = label.intrinsicContentSize
        let bounds = CGSize(width: ceil(measured.width) + 4, height: ceil(measured.height) + 4)
        guard bounds.width.isFinite, bounds.height.isFinite, bounds.width > 4, bounds.height > 4,
              bounds.width <= 4096, bounds.height <= 512, bounds.width * bounds.height * 9 <= 4_000_000 else { return nil }
        label.frame = CGRect(origin: .zero, size: bounds); label.backgroundColor = .clear; label.layoutIfNeeded()
        let format = UIGraphicsImageRendererFormat(); format.opaque = false
        guard let display = label.displayList else { return nil }
        let image = UIGraphicsImageRenderer(size: bounds, format: format).image { context in
            // iosMath draws in CoreText coordinates; UIImage uses UIKit coordinates.
            context.cgContext.translateBy(x: 0, y: bounds.height)
            context.cgContext.scaleBy(x: 1, y: -1)
            display.draw(context.cgContext)
        }
        let result = Rendered(image: image, descent: (label.displayList?.descent ?? 0) + 2)
        cache.setObject(result, forKey: key as NSString, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        return result
    }
}

struct MessageInlineView: View {
    let text: String
    @Environment(\.colorScheme) private var colorScheme
    @ScaledMetric(relativeTo: .body) private var fontSize = 17
    var body: some View {
        if text.contains("\u{E000}") { rendered.accessibilityLabel(readingLabel) }
        else { inline(text) }
    }
    private var readingLabel: String {
        MessageMathSource.segments(text).map { $0.formula?.latex ?? String((try? AttributedString(markdown: $0.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))?.characters ?? AttributedString($0.text).characters) }.joined()
    }
    private var rendered: Text {
        let segments = MessageMathSource.segments(text)
        guard segments.contains(where: { $0.formula != nil }) else { return inline(text) }
        let prefix = "FWMATH" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        var marked = "", formulas: [String: MessageFormula] = [:]
        for part in segments {
            if let formula = part.formula {
                let token = prefix + "N" + String(formulas.count) + "Z"; formulas[token] = formula; marked += token
            } else { marked += part.text }
        }
        let attributed = (try? AttributedString(markdown: marked, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(marked)
        var result = Text("")
        for run in attributed.runs {
            var remaining = String(attributed[run.range].characters)
            while let match = formulas.compactMap({ token, formula in remaining.range(of: token).map { ($0, formula) } }).min(by: { $0.0.lowerBound < $1.0.lowerBound }) {
                result = result + Text(AttributedString(String(remaining[..<match.0.lowerBound]), attributes: run.attributes))
                if let image = MessageMathRenderer.render(match.1, size: fontSize, dark: colorScheme == .dark) {
                    result = result + Text(Image(uiImage: image.image)).baselineOffset(-image.descent)
                } else { result = result + Text(verbatim: match.1.source) }
                remaining = String(remaining[match.0.upperBound...])
            }
            result = result + Text(AttributedString(remaining, attributes: run.attributes))
        }
        return result
    }
    private func inline(_ text: String) -> Text { Text((try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(text)) }
}

struct MessageFormulaView: View {
    let formula: MessageFormula
    @Environment(\.colorScheme) private var colorScheme
    @ScaledMetric(relativeTo: .body) private var fontSize = 20
    var body: some View {
        ScrollView(.horizontal) {
            if let result = MessageMathRenderer.render(formula, size: fontSize, dark: colorScheme == .dark) {
                Image(uiImage: result.image).accessibilityLabel(String(localized: "公式") + ": " + formula.latex)
                    .accessibilityIdentifier("rendered-formula").padding(.vertical, 6)
            } else { Text(verbatim: formula.source).textSelection(.enabled).accessibilityIdentifier("formula-fallback") }
        }.accessibilityIdentifier("message-formula")
            .contextMenu { Button { UIPasteboard.general.string = formula.latex } label: { Label("复制", systemImage: "doc.on.doc") } }
    }
}
