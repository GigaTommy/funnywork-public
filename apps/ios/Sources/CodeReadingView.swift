import SwiftUI
import UIKit

struct CodeReadingView: View {
    let text: String
    let language: String
    @Environment(\.dismiss) private var dismiss
    @State private var wraps = true
    @State private var copied = false
    private var isDiff: Bool { ["diff", "patch"].contains(language.lowercased().split(separator: " ").first.map(String.init) ?? "") }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    if !isDiff {
                        Toggle("自动换行", isOn: $wraps).toggleStyle(.switch).tint(Theme.switchAccent)
                            .accessibilityIdentifier("code-wrap")
                    }
                    Spacer(minLength: 12)
                    Button {
                        UIPasteboard.general.string = text; copied = true
                    } label: {
                        Label(copied ? String(localized: "已复制") : String(localized: "复制"), systemImage: copied ? "checkmark" : "doc.on.doc").labelStyle(.iconOnly)
                    }.frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("copy-expanded-code")
                    ShareLink(item: text) { Label("分享", systemImage: "square.and.arrow.up") }
                        .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                        .accessibilityIdentifier("share-expanded-code")
                }.font(.subheadline).padding(.horizontal, 16).padding(.vertical, 8).background(Theme.input)
                Divider()
                ScrollView(.vertical) {
                    if isDiff { MessageDiffView(text: text).padding(12) }
                    else if wraps {
                        source.fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                    } else {
                        ScrollView(.horizontal) {
                            source.fixedSize(horizontal: true, vertical: false).padding(16)
                        }.accessibilityIdentifier("expanded-code-horizontal")
                    }
                }.accessibilityIdentifier("expanded-code-scroll")
            }.background(Theme.surface)
                .navigationTitle(language.isEmpty ? String(localized: "代码") : language)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarLeading) { Button("完成") { dismiss() } } }
                .task(id: copied) {
                    guard copied else { return }
                    do { try await Task.sleep(for: .seconds(2)); copied = false } catch { }
                }
        }
    }
    private var source: some View {
        Text(verbatim: text).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
            .accessibilityIdentifier("expanded-code-text")
    }
}
