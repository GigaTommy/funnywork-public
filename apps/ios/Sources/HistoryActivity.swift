import SwiftUI

struct HistoryActivity: Codable, Equatable {
    enum Kind: String, Codable { case toolCall, toolResult, toolError, started, completed, stopped, interrupted, blocked, failed, limit, context, other }
    let kind: Kind
    var name: String? = nil
    var detail: String? = nil
    var title: String {
        switch kind {
        case .toolCall: return String(localized: "工具调用")
        case .toolResult: return String(localized: "工具返回")
        case .toolError: return String(localized: "工具执行失败")
        case .started: return String(localized: "开始处理")
        case .completed: return String(localized: "本轮已完成")
        case .stopped: return String(localized: "本轮已停止")
        case .interrupted: return String(localized: "本轮已中断")
        case .blocked: return String(localized: "本轮受阻")
        case .failed: return String(localized: "本轮执行失败")
        case .limit: return String(localized: "已达到输出上限")
        case .context: return String(localized: "上下文更新")
        case .other: return String(localized: "活动记录")
        }
    }
    var statusStyle: StatusStyle {
        switch kind {
        case .toolError, .failed: return .failed
        case .blocked, .limit: return .waiting
        case .completed: return .completed
        case .started: return .running
        case .stopped, .interrupted: return .stopped
        default: return .neutral
        }
    }
    var symbol: String {
        switch kind {
        case .toolCall, .toolResult: return "terminal"
        case .toolError, .failed, .blocked, .limit: return "exclamationmark.circle"
        case .completed: return "checkmark.circle"
        case .stopped, .interrupted: return "stop.circle"
        case .started: return "play.circle"
        case .context, .other: return "text.alignleft"
        }
    }
    static func parse(type: String, data: [String: Any], source: String?) -> Self? {
        let message = data["message"] as? [String: Any] ?? data
        switch type {
        case "user/message": return source == nil || source == "user" ? nil : Self(kind: .context)
        case "assistant/message": return nil
        case "tool/call": return Self(kind: .toolCall, name: data["name"] as? String, detail: data["arguments"] as? String)
        case "tool/result":
            let error = data["error"] as? [String: Any]
            return Self(kind: message["isError"] as? Bool == true ? .toolError : .toolResult, detail: error?["reason"] as? String)
        case "turn/start": return Self(kind: .started)
        case "turn/end":
            let reason = data["reason"] as? [String: Any] ?? [:]
            let kind: Kind
            switch reason["kind"] as? String {
            case "completed": kind = .completed
            case "aborted": kind = .stopped
            case "interrupted", "forked": kind = .interrupted
            case "blocked": kind = .blocked
            case "error": kind = .failed
            case "max-tokens": kind = .limit
            default: kind = .other
            }
            return Self(kind: kind, detail: (reason["error"] as? [String: Any])?["message"] as? String)
        case "system/message", "developer/message": return Self(kind: .context)
        case "": return nil // Legacy fixture/cache records may have no event type.
        default: return Self(kind: .other, detail: type)
        }
    }
}

struct HistoryActivityView: View {
    let item: HistoryItem
    let activity: HistoryActivity
    var body: some View {
        Group {
            if item.activityText.isEmpty { activityLabel }
            else {
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(verbatim: item.activityText).font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        Button { UIPasteboard.general.string = item.activityText } label: { Label("复制", systemImage: "doc.on.doc") }
                            .font(.caption).accessibilityIdentifier("copy-activity-\(item.id)")
                    }.padding(.vertical, 8)
                } label: { activityLabel }
            }
        }.foregroundStyle(.secondary)
    }
    private var activityLabel: some View {
        Label {
            Text(activity.title).foregroundStyle(activity.statusStyle == .neutral ? Color.secondary : Color.primary)
            if let name = activity.name, !name.isEmpty { Text(verbatim: name).lineLimit(1) }
        } icon: { Image(systemName: activity.symbol).foregroundStyle(activity.statusStyle.color) }
        .font(.caption).accessibilityIdentifier("activity-\(item.id)")
    }
}
