import Foundation

struct MobileResultLink: Codable, Identifiable {
    var id: String { conversation.id }
    let chatID: String
    let conversation: Conversation
    var confirmed: Bool
}


@MainActor protocol MobileResultLinkService {
    func associate(chatID: String, sessionID: String) async throws
}
@MainActor struct HTTPMobileResultLinkService: MobileResultLinkService {
    let api: API
    func associate(chatID: String, sessionID: String) async throws {
        guard CreationDevice.validID(chatID), CreationDevice.validID(sessionID) else { throw ClientError.invalidResponse }
        _ = try await api.request("chats/\(chatID)/desktop-tasks", body: ["sessionId": sessionID])
    }
}
/// A stored association survives any creation entry, interrupted acknowledgement and offline recovery.
@MainActor enum MobileResultLinkSync {
    static func flush(store: ConversationStore, service: MobileResultLinkService) async throws {
        var processed: Set<String> = []
        while processed.count < 100 {
            let pending = try store.loadResultLinks().first { !$0.confirmed && !processed.contains($0.chatID + ":" + $0.id) }
            guard let pending else { return }
            try await service.associate(chatID: pending.chatID, sessionID: pending.id); try Task.checkCancellation()
            var latest = try store.loadResultLinks()
            if let index = latest.firstIndex(where: { $0.chatID == pending.chatID && $0.id == pending.id }) { latest[index].confirmed = true }
            try store.saveResultLinks(latest); processed.insert(pending.chatID + ":" + pending.id)
        }
    }
}
