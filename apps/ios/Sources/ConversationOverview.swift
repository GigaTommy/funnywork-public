import SwiftUI

enum ConversationTaskState: String, Codable {
    case approval, question, waiting, running, idle
    var title: String? {
        switch self {
        case .approval: return String(localized: "需要批准")
        case .question: return String(localized: "需要回答")
        case .waiting: return String(localized: "等待模型容量")
        case .running: return String(localized: "进行中")
        case .idle: return nil
        }
    }
}
extension Conversation {
    var preview: String? {
        if let text = previewText?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty { return text }
        return previewAttachment == true ? String(localized: "附件") : nil
    }
    func status(at date: Date, showingCache: Bool, active: Bool = true) -> String {
        if showingCache || !active { return String(localized: "未连接") }
        if let expiry = taskStateExpiresAt, expiry > date, let title = taskState?.title { return title }
        return online ? String(localized: "在线") : String(localized: "离线")
    }
}
extension Conversation {
    func statusStyle(at date: Date, showingCache: Bool, active: Bool = true) -> StatusStyle {
        if showingCache || !active { return .unlinked }
        if let expiry = taskStateExpiresAt, expiry > date {
            switch taskState {
            case .approval, .question, .waiting: return .waiting
            case .running: return .running
            default: break
            }
        }
        return online ? .online : .offline
    }
}
struct ConversationStatusLabel: View {
    let conversation: Conversation
    let showingCache: Bool
    let active: Bool
    var body: some View {
        TimelineView(.periodic(from: .now, by: 5)) { context in
            StatusIndicator(title: conversation.status(at: context.date, showingCache: showingCache, active: active),
                style: conversation.statusStyle(at: context.date, showingCache: showingCache, active: active),
                identifier: "conversation-status-\(conversation.id)")
        }
    }
}
