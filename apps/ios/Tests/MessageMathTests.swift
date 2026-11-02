import XCTest
@testable import FunnyWork

final class MessageMathTests: XCTestCase {
    func testDisplayAndInlineDelimitersPreserveTeXBeforeMarkdown() throws {
        let source = #"A \(x_1^2\) and $y^2$ then"# + "\n\n" + #"\[\frac{a}{b}\]"# + "\n\n" + "$$\ns = \\sum_{i=1}^{n} i\n$$"
        let blocks = MessageBlock.parse(source)
        XCTAssertEqual(blocks.count, 3)
        XCTAssertEqual(MessageMathSource.segments(blocks[0].text).compactMap(\.formula).map(\.latex), ["x_1^2", "y^2"])
        XCTAssertEqual(blocks[1], .init(kind: .math, text: #"\frac{a}{b}"#))
        XCTAssertEqual(blocks[2], .init(kind: .math, text: "\ns = \\sum_{i=1}^{n} i\n"))
    }
    func testIndentedDisplayNumericInlineAndOversizedInput() {
        let source = "\\[\n    x_1 + y_1\n\\]"
        XCTAssertEqual(MessageBlock.parse(source), [.init(kind: .math, text: "\n    x_1 + y_1\n")])
        XCTAssertEqual(MessageMathSource.prepare("Value $2$.").formulas.values.first?.latex, "2")
        let huge = "\\[" + String(repeating: "x", count: 5000) + "\\]"
        let block = MessageBlock.parse(huge)[0]
        let attributed = try? AttributedString(markdown: block.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
        XCTAssertEqual(attributed.map { String($0.characters) }, huge)
    }
    func testCodeLinksEscapesAndCurrencyRemainLiteral() throws {
        let source = #"Pay $5 and $10. `\(x\)` and [link](https://example.com/$x$). Escaped \$z\$."# + "\n\n```tex\n\\[a+b\\]\n```"
        let prepared = MessageMathSource.prepare(source)
        XCTAssertTrue(prepared.formulas.isEmpty)
        let blocks = MessageBlock.parse(source)
        XCTAssertTrue(MessageMathSource.segments(blocks[0].text).compactMap(\.formula).isEmpty)
        XCTAssertEqual(blocks.last, .init(kind: .code("tex"), text: #"\[a+b\]"#))
        let unicode = MessageMathSource.prepare(#"你好 `\(code\)` \(x+1\)"#)
        XCTAssertEqual(unicode.formulas.count, 1)
        XCTAssertEqual(unicode.formulas.values.first?.latex, "x+1")
    }
    func testStreamingIncompleteFormulaKeepsDelimitersAndBackslashes() {
        for source in [#"\[\frac{a}{"#, "$$\nx_1 +", #"Before \(x_1"#] {
            let prepared = MessageMathSource.prepare(source)
            XCTAssertTrue(prepared.formulas.isEmpty); XCTAssertEqual(prepared.literals.count, 1)
            let block = MessageBlock.parse(source)[0]
            let attributed = try? AttributedString(markdown: block.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
            XCTAssertEqual(attributed.map { String($0.characters) }, source)
        }
    }
    func testDisplayFormulaWithinListsAndParagraphsKeepsOrder() {
        let blocks = MessageBlock.parse("Before\n$$x^2$$\nAfter")
        XCTAssertEqual(blocks.map(\.kind), [.paragraph, .math, .paragraph])
        let list = MessageBlock.parse("- Equation\n\n  \\[x^2\\]")[0]
        XCTAssertEqual(list.kind, .list("•")); XCTAssertEqual(list.text, "Equation")
        XCTAssertEqual(list.children, [.init(kind: .math, text: "x^2")])
    }
    @MainActor func testNativeRendererHandlesStandardFormulasAndRejectsInvalidOrExcessiveInput() {
        for latex in [#"\frac{a}{b}"#, #"\sqrt{x^2+y^2}"#, #"\sum_{i=1}^{n} i"#, #"\begin{pmatrix}1&2\\3&4\end{pmatrix}"#] {
            for dark in [false, true] {
                let result = MessageMathRenderer.render(.init(latex: latex, display: true), size: 20, dark: dark)
                XCTAssertNotNil(result, latex); XCTAssertGreaterThan(result?.image.size.width ?? 0, 0)
            }
        }
        for latex in [#"\unsupported{a}"#, #"\frac{"#, String(repeating: "{", count: 25) + "x" + String(repeating: "}", count: 25), String(repeating: "x", count: 5000)] {
            XCTAssertNil(MessageMathRenderer.render(.init(latex: latex, display: true), size: 20, dark: false))
        }
    }
}
