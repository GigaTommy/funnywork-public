import Foundation

struct ConversationTimeline {
    struct Row: Identifiable {
        let id: Int
        var items: [HistoryItem]
        let isActivity: Bool
    }
    let rows: [Row]
    private let anchors: [Int: Int]

    init(items: [HistoryItem], gaps: Set<Int>) {
        var rows: [Row] = [], anchors: [Int: Int] = [:]
        var resultAnchors: [String: Int] = [:]
        for item in items {
            if !item.results.isEmpty {
                let fresh = item.results.filter { resultAnchors[$0.identity] == nil }
                if fresh.isEmpty { anchors[item.id] = resultAnchors[item.results[0].identity]; continue }
                for result in fresh { resultAnchors[result.identity] = item.id }
            }
            let activity = item.results.isEmpty && (item.activity != nil || (item.text.isEmpty && item.attachments.isEmpty && !item.interrupted))
            if activity, !gaps.contains(item.id), let index = rows.indices.last, rows[index].isActivity {
                anchors[item.id] = rows[index].id
                rows[index].items.append(item)
            } else {
                rows.append(Row(id: item.id, items: [item], isActivity: activity))
                anchors[item.id] = item.id
            }
        }
        self.rows = rows; self.anchors = anchors
    }

    func anchor(for sequence: Int) -> Int { anchors[sequence] ?? sequence }
}
