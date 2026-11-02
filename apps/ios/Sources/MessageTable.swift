import SwiftUI

struct MessageTable: Equatable {
    enum Alignment: Equatable { case leading, center, trailing }
    let headers: [String]
    let alignments: [Alignment]
    let rows: [[String]]

}

struct MessageTableView: View {
    let table: MessageTable
    @ScaledMetric(relativeTo: .body) private var columnWidth = 160
    var body: some View {
        ScrollView(.horizontal) {
            VStack(spacing: 0) {
                row(table.headers, header: true)
                ForEach(Array(table.rows.enumerated()), id: \.offset) { index, cells in
                    row(cells, header: false)
                        .background(index.isMultiple(of: 2) ? Color.clear : Theme.input)
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.accent.opacity(0.15)))
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }.accessibilityIdentifier("message-table")
    }
    private func row(_ cells: [String], header: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, cell in
                MessageInlineView(text: cell)
                    .fontWeight(header ? .semibold : .regular)
                    .multilineTextAlignment(alignment(index) == .trailing ? .trailing : alignment(index) == .center ? .center : .leading)
                    .frame(width: columnWidth, alignment: alignment(index))
                    .padding(12)
                    .textSelection(.enabled)
                    .accessibilityLabel(header ? cell : table.headers[index] + ": " + cell)
            }
        }
        .background(header ? Theme.input : Color.clear)
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.accent.opacity(0.1)).frame(height: 1) }
    }
    private func alignment(_ index: Int) -> Alignment {
        switch table.alignments[index] {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }
}
