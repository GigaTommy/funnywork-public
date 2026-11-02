import XCTest
@testable import FunnyWork

final class MessageContentTests: XCTestCase {
    func testMessageBrowserAcceptsOnlyWebLinksWithHosts() throws {
        for target in ["https://example.com/path?q=hello#section", "http://example.com"] {
            let url = try XCTUnwrap(URL(string: target))
            XCTAssertEqual(MessageWebLink(url)?.url, url)
        }
        for target in ["file:///tmp/test.txt", "mailto:test@example.com", "javascript:alert(1)", "funnywork://action", "https:relative", "/relative"] {
            XCTAssertNil(MessageWebLink(try XCTUnwrap(URL(string: target))))
        }
    }
    func testNestedListsKeepIndentationContinuationAndTaskState() {
        let source = "1. Prepare\n   - [x] Read **notes**\n     Keep the original\n   - [ ] Review\n\n     2) Nested\n2. Finish\n\nAfter"
        XCTAssertEqual(MessageBlock.parse(source), [
            .init(kind: .list("1."), text: "Prepare", children: [
                .init(kind: .task(true), text: "Read **notes**\nKeep the original"),
                .init(kind: .task(false), text: "Review", children: [.init(kind: .list("2."), text: "Nested")])
            ]),
            .init(kind: .list("2."), text: "Finish"), .init(kind: .paragraph, text: "After")
        ])
        XCTAssertEqual(MessageBlock.parse("- Parent\n\t- [X] Child").first?.children,
                       [.init(kind: .task(true), text: "Child")])
    }
    func testDividersAndLiteralMarkersDoNotBecomeTasksOrLists() {
        let source = "---\n* * *\n___\n- [maybe] literal\n\n1234567890. literal\n3.14 decimal"
        XCTAssertEqual(MessageBlock.parse(source), [
            .init(kind: .divider, text: ""), .init(kind: .divider, text: ""),
            .init(kind: .divider, text: ""), .init(kind: .list("•"), text: "\\[maybe\\] literal"),
            .init(kind: .paragraph, text: "1234567890. literal\n3.14 decimal")
        ])
        XCTAssertEqual(MessageBlock.parse("```md\n- [x] code\n---\n```"),
                       [.init(kind: .code("md"), text: "- [x] code\n---")])
    }
    func testTableEndsBeforeOrderedListWithPipe() {
        let blocks = MessageBlock.parse("A | B\n--- | ---\n1 | 2\n1) Choose A | B")
        XCTAssertEqual(blocks.count, 2)
        XCTAssertEqual(blocks.last, .init(kind: .list("1."), text: "Choose A \\| B"))
    }
    func testTableAlignmentEscapesAndCodePipes() throws {
        let source = "| Name | Value | Notes |\n| :--- | ---: | :---: |\n| A\\|B | `x\\|y` | **Ready** |\n| C | 2 |"
        let block = try XCTUnwrap(MessageBlock.parse(source).first)
        guard case .table(let table) = block.kind else { return XCTFail("Expected table") }
        XCTAssertEqual(table.headers, ["Name", "Value", "Notes"])
        XCTAssertEqual(table.alignments, [.leading, .trailing, .center])
        XCTAssertEqual(table.rows, [["A\\|B", "` x|y `", "**Ready**"], ["C", "2", ""]])
    }
    func testTableWithoutOuterPipesAndSurroundingParagraphs() {
        let blocks = MessageBlock.parse("Before\n\nName | Count\n--- | ---\nOne | 1\n\nAfter")
        XCTAssertEqual(blocks.count, 3)
        XCTAssertEqual(blocks.first?.text, "Before")
        XCTAssertEqual(blocks.last?.text, "After")
        guard case .table(let table) = blocks[1].kind else { return XCTFail("Expected table") }
        XCTAssertEqual(table.rows, [["One", "1"]])
    }
    func testIncompleteTableAndFencedTableRemainText() {
        let incomplete = "A | B\n--- |"
        XCTAssertEqual(MessageBlock.parse(incomplete), [.init(kind: .paragraph, text: "A \\| B\n--- \\|")])
        let fenced = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        XCTAssertEqual(MessageBlock.parse("```md\n" + fenced + "\n```"), [.init(kind: .code("md"), text: fenced)])
        let adjacent = MessageBlock.parse("| A | B |\n| --- | --- |\n```a|b\nraw\n```\n# Next | topic")
        XCTAssertEqual(adjacent.count, 3)
        XCTAssertEqual(adjacent[1], .init(kind: .code("a|b"), text: "raw"))
        XCTAssertEqual(adjacent[2], .init(kind: .heading(1), text: "Next \\| topic"))
    }
    func testMixedMarkdownPreservesCodeAndListOrder() {
        let blocks = MessageBlock.parse("# Summary\n\n**Ready**\n- First\n2. Next\n> Note\n```swift\n  let x = 1\n\nprint(x)\n```\nDone")
        XCTAssertEqual(blocks, [
            .init(kind: .heading(1), text: "Summary"), .init(kind: .paragraph, text: "**Ready**"),
            .init(kind: .list("•"), text: "First"), .init(kind: .list("2."), text: "Next"),
            .init(kind: .quote, text: "Note"), .init(kind: .code("swift"), text: "  let x = 1\n\nprint(x)"),
            .init(kind: .paragraph, text: "Done")
        ])
    }
    func testUnfinishedAndNestedFencesDoNotLoseText() {
        XCTAssertEqual(MessageBlock.parse("````md\n```swift\nlet x = 1\n```\n````"), [.init(kind: .code("md"), text: "```swift\nlet x = 1\n```")])
        XCTAssertEqual(MessageBlock.parse("~~~diff\n-old\n+new"), [.init(kind: .code("diff"), text: "-old\n+new")])
        XCTAssertEqual(MessageBlock.parse("3.14 is a number\n#tag"), [.init(kind: .paragraph, text: "3.14 is a number\n#tag")])
    }
}

extension MessageContentTests {
    func testListPreservesParagraphCodeTableAndNestedQuote() throws {
        let blocks = MessageBlock.parse("- Start\n\n  Second paragraph\n\n  ```swift\n  let x = 1\n  ```\n\n  A | B\n  --- | ---\n  1 | 2\n\n  > Outer\n  >\n  > > Inner\n\n- End")
        XCTAssertEqual(blocks.count, 2)
        XCTAssertEqual(blocks.first?.text, "Start")
        let children = try XCTUnwrap(blocks.first?.children)
        XCTAssertEqual(children.count, 4)
        XCTAssertEqual(children[0], .init(kind: .paragraph, text: "Second paragraph"))
        XCTAssertEqual(children[1], .init(kind: .code("swift"), text: "let x = 1"))
        guard case .table(let table) = children[2].kind else { return XCTFail("Expected nested table") }
        XCTAssertEqual(table.rows, [["1", "2"]])
        XCTAssertEqual(children[3], .init(kind: .quote, text: "Outer", children: [.init(kind: .quote, text: "Inner")]))
        XCTAssertEqual(blocks.last?.text, "End")
    }
    func testInlineTextPreservesEscapesBreaksCodeAndReferenceLinks() throws {
        let source = "Literal \\*stars\\* and **bold**\n`a*b` [Guide][docs] <span>safe</span>\n\n[docs]: https://example.com/path"
        let block = try XCTUnwrap(MessageBlock.parse(source).first)
        let value = try AttributedString(markdown: block.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
        XCTAssertEqual(String(value.characters), "Literal *stars* and bold\na*b Guide <span>safe</span>")
        XCTAssertTrue(value.runs.contains { $0.link == URL(string: "https://example.com/path") })
        XCTAssertEqual(MessageBlock.parse("plain -- --- \"quotes\"").first?.text, "plain -- --- \"quotes\"")
    }
    func testInlineCodeAndEntitiesKeepLiteralContent() throws {
        let block = try XCTUnwrap(MessageBlock.parse("`   ` and ``a`b`` &amp;amp;").first)
        let value = try AttributedString(markdown: block.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
        XCTAssertEqual(String(value.characters), "    and a`b &amp;")
    }
    func testDeepNestingFallsBackWithoutDroppingText() {
        let blocks = MessageBlock.parse(String(repeating: "> ", count: 20) + "deep content")
        var current = blocks[0]
        var count = 0
        while let next = current.children.first { current = next; count += 1 }
        XCTAssertLessThanOrEqual(count, 6)
        XCTAssertEqual(current.kind, .literal)
        XCTAssertTrue(current.text.contains("deep content"))
    }
    func testDiffTracksBothLineNumbersAndDoesNotConfuseContentWithFileHeaders() {
        let raw = "--- a/file\n+++ b/file\n@@ -10,3 +20,3 @@\n same\n---removed\n+++added\n last\n\\ No newline at end of file"
        let rows = MessageDiffLine.parse(raw)
        XCTAssertEqual(rows.map(\.kind), [.header, .header, .header, .context, .removed, .added, .context, .header])
        XCTAssertEqual(rows.map(\.oldLine), [nil, nil, nil, 10, 11, nil, 12, nil])
        XCTAssertEqual(rows.map(\.newLine), [nil, nil, nil, 20, nil, 21, 22, nil])
        XCTAssertEqual(rows.map(\.text).joined(separator: "\n"), raw)
    }
    func testDiffSupportsZeroRangesMultipleHunksAndIncompleteSnippets() {
        let rows = MessageDiffLine.parse("@@ -0,0 +1,2 @@\n+one\n+two\n@@ -8 +9,0 @@\n-old\n\\ No newline at end of file\n@@ -999999999999999999999 +1 @@\n+partial")
        XCTAssertEqual(rows[1].newLine, 1)
        XCTAssertEqual(rows[2].newLine, 2)
        XCTAssertEqual(rows[4].oldLine, 8)
        XCTAssertNil(rows[4].newLine)
        XCTAssertNil(rows[7].newLine)
        XCTAssertEqual(rows[7].kind, .added)
        XCTAssertEqual(MessageDiffLine.parse("-old\n+new\n").map(\.kind), [.removed, .added, .context])
    }
}
