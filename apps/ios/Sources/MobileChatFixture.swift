#if DEBUG
import SwiftUI

@MainActor final class MobileChatFixtureService: MobileChatService {
    var modelOptions: [ChatModelOption]?
    var request: MobileChatSend?
    var state = ""
    var reply = ""
    var replyState: String?
    var executions = 0
    var listedChats: [MobileChatSummary]?
    var historyTurns: [[String: Any]] = []
    var managedChats: [MobileChatSummary]?
    var historyReceipts: [String: (MobileChatHistoryUpdate, MobileChatSummary)] = [:]
    var waiting = ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-wait")
    var snapshotDelay: Duration = .zero
    var failPrepare = false
    var networkFailure = ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-network")
    var noModels = ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-no-models")
    func models() async throws -> [ChatModelOption] {
        if networkFailure { throw URLError(.secureConnectionFailed) }
        if let modelOptions { return modelOptions }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-thinking") {
            return [ChatModelOption(id: "flash", name: "DeepSeek Flash 正式环境长上下文工具调用与思考模型", model: "deepseek-flash", reasoningEfforts: ["off", "low", "high", "max"]),
                    ChatModelOption(id: "pro", name: "DeepSeek Pro", model: "deepseek-v4-pro", reasoningEfforts: ["off", "low", "high", "max"])]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-provider-groups") {
            return [ChatModelOption(id: "gpt-sol", name: "GPT-6.1 Sol", model: "gpt-6.1-sol"),
                    ChatModelOption(id: "deepseek", name: "DeepSeek Flash", model: "deepseek-chat"),
                    ChatModelOption(id: "gpt-luna", name: "GPT-6 Luna", model: "gpt-6-luna")]
        }
        let name = ProcessInfo.processInfo.arguments.contains("--ui-test-long-model") ? "企业 DeepSeek Flash 正式环境长上下文工具调用与思考模式专用模型" : "企业千问"
        return noModels ? [] : [ChatModelOption(id: "qwen", name: name, model: "qwen"), ChatModelOption(id: "deepseek", name: "DeepSeek")] }
    func list() async throws -> [MobileChatSummary] {
        if let managedChats { return Array(managedChats.filter { $0.archived != true }.prefix(100)) }
        if let listedChats { return listedChats }
        guard let request else { return [] }
        return [try MobileChatSummary(["id": request.chatID, "title": "手机聊天", "modelId": request.modelID, "revision": 1])]
    }
    func snapshot(_ id: String) async throws -> MobileChatSnapshot? {
        if snapshotDelay != .zero { try await Task.sleep(for: snapshotDelay) }
        return try fixtureSnapshot(id)
    }
    func fixtureSnapshot(_ id: String) throws -> MobileChatSnapshot? {
        if let chat = managedChats?.first(where: { $0.id == id }) {
            var turns: [[String: Any]] = [["id": "turn-" + id, "user_text": "保留的问题", "reply_text": id == "managed-54" ? "消息里的搜索词 needle" : "已经保存的回复", "state": "SUCCEEDED", "ticket_state": "SUCCEEDED"]]
            if id == "managed-54" {
                turns = (0..<4).map { ["id": "before-\($0)", "user_text": "之前的问题 \($0)", "reply_text": "之前的回复", "state": "SUCCEEDED", "ticket_state": "SUCCEEDED"] } + turns +
                    (0..<20).map { ["id": "after-\($0)", "user_text": "后续问题 \($0)", "reply_text": "这是后续的回复，用于检查搜索定位不会跳到最新内容。", "state": "SUCCEEDED", "ticket_state": "SUCCEEDED"] }
            }
            return try MobileChatSnapshot(["chat": ["id": chat.id, "title": chat.title, "modelId": chat.modelID, "revision": chat.revision, "archived": chat.archived ?? false, "history_revision": chat.historyRevision ?? 0, "updated_at": (chat.updatedAt?.timeIntervalSince1970 ?? 0) * 1000],
                "turns": turns, "ticket": ["state": "SUCCEEDED"]])
        }
        guard let request, request.chatID == id else { return nil }
        return try MobileChatSnapshot(["chat": ["id": id, "title": "手机聊天", "modelId": request.modelID, "revision": 1],
            "turns": historyTurns + [["id": request.turnID, "user_text": request.text, "reply_text": reply,
                "state": replyState ?? (state == "SUCCEEDED" ? "SUCCEEDED" : "PREPARED"), "ticket_state": state, "model_name": "企业千问", "model": "qwen", "performance": state == "SUCCEEDED" ? ["queue_ms": 100, "dispatch_ms": 50, "request_ms": 500, "total_ms": 650, "first_output_ms": 100, "decode_ms": 400, "decode_tokens": 5] : [:], "input_tokens": 10, "output_tokens": 5, "cached_tokens": 3, "usage_quality": state == "SUCCEEDED" ? "COMPLETE" : "UNKNOWN", "started_at": state == "SUCCEEDED" ? 1 : NSNull()]], "ticket": ["state": state]])
    }
    func prepare(_ send: MobileChatSend) async throws {
        if failPrepare { throw ClientError.invalidResponse }
        if let request {
            if request.turnID == send.turnID { return }
            guard state == "SUCCEEDED" else { throw ClientError.invalidResponse }
            if let row = try fixtureSnapshot(request.chatID)?.turns.last {
                historyTurns.append(["id": row.id, "user_text": row.text, "reply_text": row.reply, "state": "SUCCEEDED", "ticket_state": "SUCCEEDED"])
            }
            reply = ""
        }
        request = send; state = waiting ? "WAITING" : "GRANTED"
    }
    func execute(_ send: MobileChatSend, delta: @escaping (String) -> Void) async throws {
        guard state == "GRANTED" else { throw ClientError.invalidResponse }
        executions += 1; state = "RUNNING"
        delta("可以直接在手机上聊天。"); try await Task.sleep(for: ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-slow-reply") ? .seconds(8) : .milliseconds(100))
        delta("电脑任务仍在电脑上运行。")
        reply = "可以直接在手机上聊天。电脑任务仍在电脑上运行。"; state = "SUCCEEDED"
    }
    func cancel(_ send: MobileChatSend) async throws { if state == "WAITING" || state == "GRANTED" { state = "CANCELLED" } }
    func history(archived: Bool, query: String, after: String) async throws -> MobileChatHistoryPage {
        guard let managedChats else {
            let rows = try await list()
            return MobileChatHistoryPage(items: rows.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }, next: "", supported: false)
        }
        let rows = try managedChats.filter { $0.archived == archived && (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || $0.id == "managed-54" && query == "needle") }.map { chat -> MobileChatSummary in
            var value = chat
            if !query.isEmpty {
                value.searchMatch = try MobileChatSearchMatch(["field": chat.id == "managed-54" && query == "needle" ? "reply" : "title",
                    "excerpt": chat.id == "managed-54" && query == "needle" ? "消息里的搜索词 needle" : chat.title,
                    "created_at": (chat.updatedAt?.timeIntervalSince1970 ?? 0) * 1000,
                    "turnId": chat.id == "managed-54" && query == "needle" ? "turn-" + chat.id : NSNull()])
            }
            return value
        }
        let start = Int(after) ?? 0, end = min(start + 25, rows.count)
        return MobileChatHistoryPage(items: Array(rows[start..<end]), next: end < rows.count ? String(end) : "", supported: true)
    }
    func updateHistory(_ request: MobileChatHistoryUpdate) async throws -> MobileChatSummary {
        if let (old, result) = historyReceipts[request.id] {
            guard old == request else { throw MobileChatHistoryRejected(message: "Conflicting request") }; return result
        }
        guard let index = managedChats?.firstIndex(where: { $0.id == request.chatID }), var chat = managedChats?[index], chat.historyRevision == request.expectedHistoryRevision else { throw MobileChatHistoryRejected(message: "Stale history") }
        if let title = request.title { chat.title = title }
        if let archived = request.archived { chat.archived = archived }
        chat.historyRevision = request.expectedHistoryRevision + 1
        managedChats?[index] = chat; historyReceipts[request.id] = (request, chat)
        return chat
    }
}
@MainActor private final class MobileChatFixtureContext: ObservableObject {
    let service = MobileChatFixtureService()
    let store: ConversationStore
    init() {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("MobileChatUITests")
        if ProcessInfo.processInfo.arguments.contains("--ui-test-reset") { try? FileManager.default.removeItem(at: root) }
        store = ConversationStore(key: "mobile-fixture", root: root)
        if ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-management") {
            service.managedChats = (0..<55).compactMap { index in
                let title = ProcessInfo.processInfo.arguments.contains("--ui-test-long-chat-titles")
                    ? (index.isMultiple(of: 2) ? "每日工作规划示例：请用简短中文列出三个步骤，包含完整的工作说明 \(index)" : "Help me understand this concept and prepare a complete explanation \(index)")
                    : "保存的聊天 \(index)"
                return try? MobileChatSummary(["id": "managed-\(index)", "title": title, "modelId": "qwen", "revision": 1, "history_revision": 0, "archived": false, "updated_at": Date().timeIntervalSince1970 * 1000 - Double(index) * 1000])
            }
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-history") {
            service.request = MobileChatSend(chatID: "history", turnID: "latest", text: "最新问题", modelID: "qwen", revision: 0)
            service.state = "SUCCEEDED"; service.reply = "最新回复"
            service.historyTurns = (0..<25).map { index in
                ["id": "history-\(index)", "user_text": "问题 \(index)", "reply_text": "回复 \(index)：" + String(repeating: "这是一段用于检查阅读位置的内容。", count: 8), "state": "SUCCEEDED", "ticket_state": "SUCCEEDED"]
            }
            var saved = (try? store.loadMobileChat()) ?? SavedMobileChat()
            saved.selected = "history"; saved.modelID = "qwen"; saved.cached = try? service.fixtureSnapshot("history")
            try? store.saveMobileChat(saved)
        }
    }
}
struct MobileChatFixtureScreen: View {
    @StateObject private var context = MobileChatFixtureContext()
    @State private var remote = CreationFixtureService()
    var body: some View {
        MobileChatRootView(service: context.service, store: context.store, name: "Tommy",
            remoteService: ProcessInfo.processInfo.arguments.contains("--ui-test-mobile-transfer") ? remote : nil, logout: {}) {
            DirectoryFixtureScreen()
        }
    }
}
#endif
