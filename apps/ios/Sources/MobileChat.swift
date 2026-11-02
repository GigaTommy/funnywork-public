import Foundation
import OSLog
import Combine
import CoreFoundation

struct MobileChatSummary: Identifiable, Codable, Equatable {
    let id: String
    var title: String
    let modelID: String
    let revision: Int64
    var updatedAt: Date? = nil
    var archived: Bool? = nil
    var historyRevision: Int64? = nil
    var searchMatch: MobileChatSearchMatch? = nil
    init(_ row: [String: Any]) throws {
        guard let id = row["id"] as? String, CreationDevice.validID(id), let title = row["title"] as? String,
              let model = (row["modelId"] ?? row["model_id"]) as? String, !model.isEmpty,
              let revision = row["revision"] as? NSNumber, CFGetTypeID(revision) != CFBooleanGetTypeID(),
              revision.doubleValue >= 0, revision.doubleValue <= 9007199254740991, revision.doubleValue == Double(revision.int64Value) else { throw ClientError.invalidResponse }
        self.id = id; self.title = title; modelID = model; self.revision = revision.int64Value
        if let raw = row["search_match"], !(raw is NSNull) {
            guard let match = raw as? [String: Any] else { throw ClientError.invalidResponse }
            searchMatch = try MobileChatSearchMatch(match)
        }
        if let value = row["history_revision"] as? NSNumber {
            guard CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue >= 0,
                  value.doubleValue <= 9007199254740991, value.doubleValue == Double(value.int64Value) else { throw ClientError.invalidResponse }
            historyRevision = value.int64Value
        }
        if let value = row["archived"] as? NSNumber {
            guard CFGetTypeID(value) == CFBooleanGetTypeID() else { throw ClientError.invalidResponse }
            archived = value.boolValue
        }
        if let timestamp = (row["updatedAt"] ?? row["updated_at"]) as? NSNumber,
           CFGetTypeID(timestamp) != CFBooleanGetTypeID(), timestamp.doubleValue.isFinite,
           timestamp.doubleValue >= 0, timestamp.doubleValue <= 253402300799000 {
            updatedAt = Date(timeIntervalSince1970: timestamp.doubleValue / 1000)
        }
    }
}
struct MobileChatHistoryGroup: Identifiable {
    enum Period: CaseIterable { case attention, running, today, yesterday, earlier }
    let id: Period
    let chats: [MobileChatSummary]
    var title: String {
        switch id {
        case .attention: return String(localized: "需要处理")
        case .running: return String(localized: "进行中")
        case .today: return String(localized: "今天")
        case .yesterday: return String(localized: "昨天")
        case .earlier: return String(localized: "更早")
        }
    }
}
struct MobileChatUsage: Codable, Equatable {
    let input: Int64
    let output: Int64
    let cached: Int64?
    var total: Int64 { input + output }
    init?(_ row: [String: Any]) {
        func count(_ key: String) -> Int64? {
            guard let value = row[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
                  value.doubleValue >= 0, value.doubleValue <= 9007199254740991,
                  value.doubleValue == Double(value.int64Value) else { return nil }
            return value.int64Value
        }
        guard row["usage_quality"] as? String == "COMPLETE", let input = count("input_tokens"),
              let output = count("output_tokens"), input + output <= 9007199254740991 else { return nil }
        let cached = count("cached_tokens")
        if let value = row["cached_tokens"], !(value is NSNull), cached == nil { return nil }
        if let cached, cached > input { return nil }
        self.input = input; self.output = output; self.cached = cached
    }
}
struct MobileChatTurn: Identifiable, Codable, Equatable {
    let id: String
    let text: String
    let reply: String
    let state: String
    let ticketState: String
    let modelName: String?
    let modelIdentifier: String?
    var modelLabel: String { [modelName, modelIdentifier].compactMap { $0 }.reduce("") { $0.isEmpty ? $1 : ($0 == $1 ? $0 : $0 + " · " + $1) } }
    let usage: MobileChatUsage?
    let attempted: Bool?
    let performance: ChatPerformanceReading?
    init(_ row: [String: Any]) throws {
        guard let id = row["id"] as? String, CreationDevice.validID(id), let text = row["user_text"] as? String,
              let reply = row["reply_text"] as? String, let state = row["state"] as? String,
              let ticketState = row["ticket_state"] as? String else { throw ClientError.invalidResponse }
        self.id = id; self.text = text; self.reply = reply; self.state = state; self.ticketState = ticketState
        modelName = row["model_name"] as? String; modelIdentifier = row["model"] as? String; usage = MobileChatUsage(row)
        attempted = row["started_at"] is NSNumber
        performance = (row["performance"] as? [String: Any]).flatMap(ChatPerformanceReading.init)
    }
}
struct MobileChatSnapshot: Codable, Equatable {
    var chat: MobileChatSummary
    let turns: [MobileChatTurn]
    let ticketState: String
    var confirmedTokens: Int64 { turns.compactMap(\.usage).reduce(0) { $0 + $1.total } }
    var usageSummary: ChatUsageSummary {
        var result = ChatUsageSummary()
        for turn in turns {
            if let usage = turn.usage { result.add(usage) }
            else if turn.attempted == true { result.unconfirmedCalls += 1 }
        }
        return result
    }
    var performanceSummary: ChatPerformanceSummary {
        var result = ChatPerformanceSummary()
        for turn in turns {
            if let reading = turn.performance { result.add(reading) }
            else if turn.attempted == true { result.unmeasuredCalls += 1 }
        }
        return result
    }
    var hasUnconfirmedUsage: Bool { turns.contains { $0.attempted == true && $0.usage == nil } }
    var hasConfirmedUsage: Bool { turns.contains { $0.usage != nil } }
    init(_ row: [String: Any]) throws {
        guard let chat = row["chat"] as? [String: Any], let turns = row["turns"] as? [[String: Any]], turns.count <= 100 else { throw ClientError.invalidResponse }
        self.chat = try MobileChatSummary(chat); self.turns = try turns.map(MobileChatTurn.init)
        ticketState = (row["ticket"] as? [String: Any])?["state"] as? String ?? ""
    }
}
struct MobileChatSend: Codable, Equatable {
    let chatID: String
    let turnID: String
    let text: String
    let modelID: String
    let revision: Int64
    var executionRequested = false
    var ownsDraft = true
    var reasoningEffort: String? = nil
    var maxOutputTokens: Int? = nil
    var body: [String: Any] {
        var value: [String: Any] = ["id": turnID, "text": text, "modelId": modelID, "expectedRevision": revision]
        if let reasoningEffort { value["reasoningEffort"] = reasoningEffort }
        if let maxOutputTokens { value["maxOutputTokens"] = maxOutputTokens }
        return value
    }
}
struct SavedMobileChat: Codable {
    var selected: String?
    var drafts: [String: String] = [:]
    var modelID = ""
    var reasoningEfforts: [String: String] = [:]
    var pendingChats: [String: MobileChatSend] = [:]
    var cached: MobileChatSnapshot?
    var readingPositions: [String: String] = [:]
    var pending: MobileChatSend? {
        get { selected.flatMap { pendingChats[$0] } ?? pendingChats.keys.sorted().first.flatMap { pendingChats[$0] } }
        set {
            if let newValue { pendingChats[newValue.chatID] = newValue }
            else if let current = pending { pendingChats.removeValue(forKey: current.chatID) }
        }
    }
    init() {}
    private enum CodingKeys: String, CodingKey { case selected, drafts, modelID, reasoningEfforts, pending, pendingChats, cached, readingPositions }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        selected = try values.decodeIfPresent(String.self, forKey: .selected)
        drafts = try values.decodeIfPresent([String: String].self, forKey: .drafts) ?? [:]
        modelID = try values.decodeIfPresent(String.self, forKey: .modelID) ?? ""
        reasoningEfforts = try values.decodeIfPresent([String: String].self, forKey: .reasoningEfforts) ?? [:]
        cached = try values.decodeIfPresent(MobileChatSnapshot.self, forKey: .cached)
        readingPositions = try values.decodeIfPresent([String: String].self, forKey: .readingPositions) ?? [:]
        pendingChats = try values.decodeIfPresent([String: MobileChatSend].self, forKey: .pendingChats) ?? [:]
        if let legacy = try values.decodeIfPresent(MobileChatSend.self, forKey: .pending), pendingChats[legacy.chatID] == nil { pendingChats[legacy.chatID] = legacy }
        guard pendingChats.count <= 32, pendingChats.allSatisfy({ $0.key == $0.value.chatID }) else { throw ClientError.invalidResponse }
    }
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encodeIfPresent(selected, forKey: .selected); try values.encode(drafts, forKey: .drafts)
        try values.encode(modelID, forKey: .modelID); try values.encode(reasoningEfforts, forKey: .reasoningEfforts); try values.encodeIfPresent(cached, forKey: .cached)
        try values.encode(pendingChats, forKey: .pendingChats)
        try values.encode(readingPositions, forKey: .readingPositions)
    }
}
@MainActor protocol MobileChatService {
    func models() async throws -> [ChatModelOption]
    func list() async throws -> [MobileChatSummary]
    func snapshot(_ id: String) async throws -> MobileChatSnapshot?
    func prepare(_ send: MobileChatSend) async throws
    func execute(_ send: MobileChatSend, delta: @escaping (String) -> Void) async throws
    func cancel(_ send: MobileChatSend) async throws
    func history(archived: Bool, query: String, after: String) async throws -> MobileChatHistoryPage
    func updateHistory(_ update: MobileChatHistoryUpdate) async throws -> MobileChatSummary
    func historyMetadata(_ id: String) async throws -> MobileChatSummary?
}
extension MobileChatService {
    func historyMetadata(_ id: String) async throws -> MobileChatSummary? { try await snapshot(id)?.chat }
    func history(archived: Bool, query: String, after: String) async throws -> MobileChatHistoryPage {
        let rows = try await list()
        return MobileChatHistoryPage(items: archived ? [] : rows.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }, next: "", supported: false)
    }
    func updateHistory(_ update: MobileChatHistoryUpdate) async throws -> MobileChatSummary { throw ClientError(message: String(localized: "请更新服务端后使用历史管理。")) }
}
@MainActor struct HTTPMobileChatService: MobileChatService {
    let api: API
    func models() async throws -> [ChatModelOption] {
        guard let row = try await api.request("chats/models") as? [String: Any], let items = row["items"] as? [[String: Any]] else { throw ClientError.invalidResponse }
        return try items.filter { $0["available"] as? Bool == true }.map {
            guard let id = $0["id"] as? String, CreationDevice.validID(id), let name = $0["name"] as? String else { throw ClientError.invalidResponse }
            return ChatModelOption(id: id, name: name, model: $0["model"] as? String, providerName: $0["provider_name"] as? String, reasoningEfforts: ($0["reasoningEfforts"] as? [String] ?? []).filter { ["off", "low", "high", "max"].contains($0) }, defaultReasoningEffort: $0["defaultReasoningEffort"] as? String ?? "off")
        }
    }
    func list() async throws -> [MobileChatSummary] {
        guard let row = try await api.request("chats") as? [String: Any], let items = row["items"] as? [[String: Any]], items.count <= 100 else { throw ClientError.invalidResponse }
        return try items.map(MobileChatSummary.init)
    }
    func history(archived: Bool, query: String, after: String) async throws -> MobileChatHistoryPage {
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "history", value: "true"), URLQueryItem(name: "archived", value: archived ? "true" : "false"), URLQueryItem(name: "query", value: query), URLQueryItem(name: "after", value: after)]
        guard let row = try await api.request("chats?" + (components.percentEncodedQuery ?? "")) as? [String: Any],
              let items = row["items"] as? [[String: Any]], items.count <= 100 else { throw ClientError.invalidResponse }
        let supported = row["historyManagement"] as? Bool == true
        let next = row["next"] as? String ?? ""
        guard next.utf8.count <= 2048, !supported || items.count <= 50 else { throw ClientError.invalidResponse }
        let summaries = try items.map(MobileChatSummary.init)
        guard !supported || summaries.allSatisfy({ $0.historyRevision != nil && $0.archived == archived }) else { throw ClientError.invalidResponse }
        return MobileChatHistoryPage(items: supported ? summaries : (archived ? [] : summaries.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }), next: supported ? next : "", supported: supported)
    }
    func updateHistory(_ update: MobileChatHistoryUpdate) async throws -> MobileChatSummary {
        guard CreationDevice.validID(update.chatID), let row = try await api.request("chats/\(update.chatID)/history", body: update.body) as? [String: Any] else { throw ClientError.invalidResponse }
        let chat = try MobileChatSummary(row)
        guard chat.id == update.chatID, chat.historyRevision == update.expectedHistoryRevision + 1,
              update.archived == nil || chat.archived == update.archived else { throw ClientError.invalidResponse }
        return chat
    }
    func historyMetadata(_ id: String) async throws -> MobileChatSummary? {
        guard CreationDevice.validID(id), let row = try await api.request("chats/\(id)/history") as? [String: Any] else { throw ClientError.invalidResponse }
        let value = try MobileChatSummary(row); guard value.id == id else { throw ClientError.invalidResponse }; return value
    }
    func snapshot(_ id: String) async throws -> MobileChatSnapshot? {
        guard CreationDevice.validID(id) else { throw ClientError.invalidResponse }
        let value = try await api.request("chats/\(id)", allowMissing: true)
        if value is NSNull { return nil }
        guard let row = value as? [String: Any] else { throw ClientError.invalidResponse }
        return try MobileChatSnapshot(row)
    }
    func prepare(_ send: MobileChatSend) async throws {
        guard let row = try await api.request("chats/\(send.chatID)/turns", body: send.body) as? [String: Any], row["turnId"] as? String == send.turnID else { throw ClientError.invalidResponse }
    }
    func cancel(_ send: MobileChatSend) async throws { _ = try await api.request("chats/\(send.chatID)/turns/\(send.turnID)/cancel", body: [:]) }
    func execute(_ send: MobileChatSend, delta: @escaping (String) -> Void) async throws {
        let request = try await api.routedRequest("chats/\(send.chatID)/turns/\(send.turnID)/execute", body: [:])
        var streaming = request; streaming.timeoutInterval = 650
        let configuration = URLSessionConfiguration.ephemeral; configuration.timeoutIntervalForResource = 650
        let session = URLSession(configuration: configuration, delegate: api, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: streaming)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              http.value(forHTTPHeaderField: "Content-Type")?.lowercased().hasPrefix("text/event-stream") == true else {
            _ = try await api.request("session")
            var errorData = Data()
            for try await byte in bytes { if errorData.count >= 32768 { break }; errorData.append(byte) }
            if let failure = ModelAllowanceFailure.error(errorData) { throw failure }
            throw ClientError(message: String(localized: "暂时无法获得回复，请核对结果后重试。"))
        }
        var line = Data(); var received = 0; var total = 0
        for try await byte in bytes {
            try Task.checkCancellation(); received += 1
            guard received <= 8388608 else { throw ClientError.invalidResponse }
            if byte == 10 {
                if let text = String(data: line, encoding: .utf8), text.hasPrefix("data:") {
                    let raw = text.dropFirst(5).trimmingCharacters(in: .whitespacesAndNewlines)
                    if raw == "[DONE]" { return }
                    if let value = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] {
                        if value["type"] as? String == "message_stop" { return }
                        let choices = value["choices"] as? [[String: Any]]
                        let chat = choices?.first?["delta"] as? [String: Any]
                        let block = value["delta"] as? [String: Any]
                        let text = chat?["content"] as? String ?? (value["type"] as? String == "content_block_delta" && block?["type"] as? String == "text_delta" ? block?["text"] as? String : nil)
                        if let text { total += text.utf16.count; guard total <= 262144 else { throw ClientError.invalidResponse }; delta(text) }
                    }
                }
                line.removeAll(keepingCapacity: true)
            } else {
                guard line.count < 65536 else { throw ClientError.invalidResponse }; line.append(byte)
            }
        }
        throw ClientError(message: String(localized: "连接已中断，正在核对回复结果。"))
    }
}

@MainActor final class MobileChatModel: ObservableObject {
    @Published private(set) var saved = SavedMobileChat()
    @Published private(set) var options: [ChatModelOption] = []
    @Published private(set) var chats: [MobileChatSummary] = []
    @Published private(set) var snapshot: MobileChatSnapshot?
    @Published private(set) var connectionIssue: String?
    @Published private(set) var loading = false
    @Published private var workStatuses: [String: String] = [:]
    private var workStatus: String? {
        get { saved.selected.flatMap { workStatuses[$0] } }
        set { if let id = saved.selected { workStatuses[id] = newValue } }
    }
    var status: String? { saved.pending != nil && !activeBusy ? nil : workStatus }
    var activeBusy: Bool { saved.selected.flatMap { saved.pendingChats[$0] } != nil }
    @Published private(set) var error: String?
    @Published private var liveReplies: [String: String] = [:]
    var liveReply: String {
        get { saved.selected.flatMap { liveReplies[$0] } ?? "" }
        set { if let id = saved.selected { liveReplies[id] = newValue } }
    }
    private let service: MobileChatService
    private let store: ConversationStore
    private let authFailure: (Error) -> Void
    private var workers: [String: Task<Void, Never>] = [:]
    private var opening = UUID()
    private var ready = false
    private var snapshotFresh = false
    private var snapshots: [String: MobileChatSnapshot] = [:]
    private var workGenerations: [String: UUID] = [:]
    var busy: Bool { !saved.pendingChats.isEmpty }
    var navigationChats: [MobileChatSummary] {
        var rows = Dictionary(uniqueKeysWithValues: chats.map { ($0.id, $0) })
        for id in saved.pendingChats.keys where rows[id] == nil { if let chat = snapshots[id]?.chat { rows[id] = chat } }
        return rows.values.filter { $0.archived != true }.sorted {
            let left = saved.pendingChats[$0.id] != nil; let right = saved.pendingChats[$1.id] != nil
            if left != right { return left }
            if $0.updatedAt != $1.updatedAt { return ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
            return $0.id < $1.id
        }
    }
    var draft: String { saved.drafts[saved.selected ?? "home"] ?? "" }
    func navigationNeedsReview(_ chat: MobileChatSummary) -> Bool {
        workStatuses[chat.id] == String(localized: "结果待确认") || snapshots[chat.id]?.ticketState == "UNKNOWN" ||
            (saved.cached?.chat.id == chat.id && saved.cached?.ticketState == "UNKNOWN")
    }
    func navigationGroups(rows: [MobileChatSummary]? = nil, now: Date = Date(), calendar: Calendar = .current) -> [MobileChatHistoryGroup] {
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let grouped = Dictionary(grouping: rows ?? navigationChats) { chat -> MobileChatHistoryGroup.Period in
            if navigationNeedsReview(chat) { return .attention }
            if saved.pendingChats[chat.id] != nil { return .running }
            guard let date = chat.updatedAt else { return .earlier }
            if date >= today { return .today }
            if date >= yesterday { return .yesterday }
            return .earlier
        }
        return MobileChatHistoryGroup.Period.allCases.compactMap { period in grouped[period].map { MobileChatHistoryGroup(id: period, chats: $0) } }
    }
    func saveReadingPosition(_ turnID: String?, for chatID: String) {
        guard saved.readingPositions[chatID] != turnID else { return }
        var next = saved; next.readingPositions[chatID] = turnID
        while next.readingPositions.count > 100 {
            guard let key = next.readingPositions.keys.sorted().first(where: { $0 != chatID }) else { break }
            next.readingPositions.removeValue(forKey: key)
        }
        _ = persist(next)
    }
    var modelID: String { saved.modelID }
    var reasoningEffort: String {
        guard let option = options.first(where: { $0.id == modelID }) else { return "off" }
        let value = saved.reasoningEfforts[modelID] ?? option.defaultReasoningEffort
        return option.reasoningEfforts.contains(value) ? value : option.defaultReasoningEffort
    }
    func selectReasoningEffort(_ value: String) {
        guard !activeBusy, options.first(where: { $0.id == modelID })?.reasoningEfforts.contains(value) == true else { return }
        var next = saved; next.reasoningEfforts[modelID] = value; _ = persist(next)
    }
    var canSend: Bool { ready && (snapshot == nil || snapshotFresh) && snapshot?.chat.archived != true && !activeBusy && saved.pendingChats.count < 32 && !["WAITING", "GRANTED", "RUNNING", "STOPPING", "UNKNOWN"].contains(snapshot?.ticketState ?? "") && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && draft.utf16.count <= 16000 && options.contains { $0.id == modelID } }
    func applyHistoryUpdate(_ chat: MobileChatSummary) {
        if let old = chats.first(where: { $0.id == chat.id }), (old.historyRevision ?? -1) > (chat.historyRevision ?? -1) { return }
        var listing = chat
        if listing.updatedAt == nil { listing.updatedAt = chats.first(where: { $0.id == chat.id })?.updatedAt }
        if let old = chats.first(where: { $0.id == chat.id }), old.revision > chat.revision {
            listing = old; listing.title = chat.title; listing.archived = chat.archived; listing.historyRevision = chat.historyRevision
        }
        chats.removeAll { $0.id == chat.id }; chats.append(listing)
        if var value = snapshots[chat.id], (value.chat.historyRevision ?? -1) <= (chat.historyRevision ?? -1) {
            value.chat.title = chat.title; value.chat.archived = chat.archived; value.chat.historyRevision = chat.historyRevision; remember(value)
        }
        if var value = snapshot, value.chat.id == chat.id {
            // Metadata receipt may carry an older inference revision; keep live execution state intact.
            guard (value.chat.historyRevision ?? -1) <= (chat.historyRevision ?? -1) else { return }
            if chat.revision > value.chat.revision { snapshotFresh = false }
            value.chat.title = chat.title; value.chat.archived = chat.archived; value.chat.historyRevision = chat.historyRevision
            var next = saved; next.cached = value
            if persist(next) { snapshot = value }
        }
    }
    init(service: MobileChatService, store: ConversationStore, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.store = store; self.authFailure = authFailure
        do { saved = try store.loadMobileChat() ?? SavedMobileChat(); snapshot = saved.cached; if saved.selected == nil, saved.drafts["home"] == nil, let pending = saved.pending { saved.selected = pending.chatID } }
        catch { self.error = ClientError.displayMessage(for: error) }
    }
    private func remember(_ value: MobileChatSnapshot) {
        // A bounded memory cache accelerates navigation without expanding durable data.
        guard let bytes = try? JSONEncoder().encode(value).count, bytes <= 512 * 1024 else { return }
        snapshots[value.chat.id] = value
        while snapshots.count > 8 {
            if let id = snapshots.keys.sorted().first(where: { $0 != saved.selected && saved.pendingChats[$0] == nil }) { snapshots.removeValue(forKey: id) }
            else { break }
        }
    }
    private func preservingHistory(_ incoming: MobileChatSummary) -> MobileChatSummary {
        let candidates = [chats.first(where: { $0.id == incoming.id }), snapshot?.chat.id == incoming.id ? snapshot?.chat : nil, snapshots[incoming.id]?.chat].compactMap { $0 }
        guard let known = candidates.max(by: { ($0.historyRevision ?? -1) < ($1.historyRevision ?? -1) }),
              (known.historyRevision ?? -1) > (incoming.historyRevision ?? -1) else { return incoming }
        var value = incoming; value.title = known.title; value.archived = known.archived; value.historyRevision = known.historyRevision
        return value
    }
    private func preservingHistory(_ incoming: MobileChatSnapshot) -> MobileChatSnapshot {
        var value = incoming; value.chat = preservingHistory(value.chat); return value
    }
    private func persist(_ next: SavedMobileChat) -> Bool {
        do { try store.saveMobileChat(next); saved = next; if let value = next.cached { remember(value) }; return true }
        catch { report(error); return false }
    }
    func updateDraft(_ text: String) { guard text.utf8.count <= 65536 else { error = String(localized: "内容过长，请缩短后重试。"); return }; var next = saved; next.drafts[saved.selected ?? "home"] = text
        while next.drafts.count > 32 { if let key = next.drafts.keys.first(where: { $0 != "home" && $0 != saved.selected }) { next.drafts.removeValue(forKey: key) } else { break } }
        _ = persist(next) }
    func selectModel(_ id: String) { guard !activeBusy else { return }; var next = saved; next.modelID = id; _ = persist(next) }
    func newChat() { snapshotFresh = true; opening = UUID(); var next = saved; next.selected = nil; next.cached = nil; next.drafts["home"] = next.drafts["home"] ?? ""; if persist(next) { snapshot = nil; liveReply = ""; error = nil; workStatus = nil } }
    func open(_ chat: MobileChatSummary) async {
        let generation = UUID(); opening = generation; snapshotFresh = false
        if let cached = snapshots[chat.id] ?? (saved.cached?.chat.id == chat.id ? saved.cached : nil) {
            var next = saved; next.selected = chat.id; next.modelID = chat.modelID; next.cached = cached
            if persist(next) { snapshot = cached; if !activeBusy { liveReply = "" }; error = nil }
        }
        do { let fetched = try await service.snapshot(chat.id); try Task.checkCancellation(); try store.requireActive(); guard opening == generation else { return }
            let value = fetched.map { preservingHistory($0) }
            var next = saved; next.selected = chat.id; next.modelID = chat.modelID; next.cached = value
            if persist(next) { snapshot = value; snapshotFresh = value != nil; if !activeBusy { liveReply = "" }; if !busy { workStatus = nil }; error = nil; attachActiveTurn() }
        } catch { if opening == generation { report(error) } }
    }
    func openRunningChat() async {
        guard let pending = saved.pending else { return }
        if let chat = snapshots[pending.chatID]?.chat ?? chats.first(where: { $0.id == pending.chatID }) { await open(chat); return }
        do {
            if let value = try await service.snapshot(pending.chatID) { await open(value.chat) }
        } catch { report(error) }
    }
    func refresh() async {
        guard !loading else { return }; loading = true
        let started = ContinuousClock.now
        defer {
            loading = false
            Logger(subsystem: "com.funnyworker.ios", category: "startup").info("chat_refresh duration=\(String(describing: started.duration(to: .now)), privacy: .public) ready=\(self.ready)")
        }
        do {
            async let requestedOptions = service.models()
            async let requestedChats = service.list()
            let (options, chats) = try await (requestedOptions, requestedChats)
            try store.requireActive()
            self.options = options; self.chats = chats.map { preservingHistory($0) }; ready = true; connectionIssue = nil
            if !options.contains(where: { $0.id == saved.modelID }), let first = options.first { var next = saved; next.modelID = first.id; _ = persist(next) }
            if let id = saved.selected {
                let generation = opening
                if let fetched = try await service.snapshot(id), opening == generation, saved.selected == id { let value = preservingHistory(fetched); snapshot = value; snapshotFresh = true; var next = saved; next.cached = value; _ = persist(next) }
            }
            attachActiveTurn()
            if busy { recover() }
            else if options.isEmpty { error = String(localized: "暂时没有可用模型。") }
            else { error = nil }
        } catch {
            ready = false
            if let network = error as? URLError, network.code != .cancelled {
                connectionIssue = ClientError.displayMessage(for: network)
                if busy { report(error) } else { self.error = nil }
            } else { report(error) }
        }
    }
    private func attachActiveTurn() {
        guard !activeBusy, let snapshot, let turn = snapshot.turns.last,
              ["WAITING", "GRANTED", "RUNNING", "STOPPING", "UNKNOWN"].contains(snapshot.ticketState) else { return }
        var next = saved
        next.pending = MobileChatSend(chatID: snapshot.chat.id, turnID: turn.id, text: turn.text,
            modelID: snapshot.chat.modelID, revision: max(0, snapshot.chat.revision - 1), executionRequested: true, ownsDraft: false)
        if persist(next) { recover() }
    }
    func send() {
        guard canSend else { return }
        let pending = MobileChatSend(chatID: saved.selected ?? UUID().uuidString, turnID: UUID().uuidString,
            text: draft, modelID: modelID, revision: snapshot?.chat.revision ?? 0,
            reasoningEffort: options.first(where: { $0.id == modelID })?.reasoningEfforts.isEmpty == false ? reasoningEffort : nil)
        var next = saved; next.pendingChats[pending.chatID] = pending; next.drafts[pending.chatID] = pending.text; if saved.selected == nil { next.drafts["home"] = "" }; next.selected = pending.chatID
        guard persist(next) else { return }
        launch(pending, preparing: true)
    }
    func editUnsent() async {
        guard let id = saved.selected, let pending = saved.pendingChats[id], !pending.executionRequested else { return }
        workGenerations[id] = UUID(); workers.removeValue(forKey: id)?.cancel()
        do { try await service.cancel(pending); try store.requireActive() }
        catch { report(error); recover(); return }
        var next = saved; next.pendingChats.removeValue(forKey: id)
        if next.drafts[id, default: ""].isEmpty { next.drafts[id] = pending.text }
        if persist(next) { workStatus = nil; error = nil }
    }
    func recover() {
        for pending in saved.pendingChats.values where workers[pending.chatID] == nil { launch(pending, preparing: false) }
    }
    func retryPreparation() {
        guard let id = saved.selected, workers[id] == nil, let pending = saved.pendingChats[id], !pending.executionRequested else { recover(); return }
        launch(pending, preparing: true)
    }
    private func launch(_ pending: MobileChatSend, preparing: Bool) {
        guard workers[pending.chatID] == nil else { return }
        let generation = UUID(); workGenerations[pending.chatID] = generation
        workers[pending.chatID] = Task { await run(pending, preparing: preparing, generation: generation) }
    }
    private func run(_ original: MobileChatSend, preparing: Bool, generation: UUID) async {
        defer { if workGenerations[original.chatID] == generation { workers.removeValue(forKey: original.chatID) } }
        do {
            if preparing { workStatuses[original.chatID] = String(localized: "正在发送…"); try await service.prepare(original); try Task.checkCancellation() }
            repeat {
                try Task.checkCancellation(); try store.requireActive()
                guard let fetched = try await service.snapshot(original.chatID), fetched.turns.contains(where: { $0.id == original.turnID }) else {
                    if saved.selected == original.chatID { error = String(localized: "发送结果尚未确认，请重试发送。") }; return
                }
                try Task.checkCancellation(); try store.requireActive()
                guard workGenerations[original.chatID] == generation, saved.pendingChats[original.chatID]?.turnID == original.turnID else { return }
                let current = preservingHistory(fetched)
                remember(current)
                var next = saved; if next.selected == original.chatID { next.cached = current }
                if original.ownsDraft {
                    if next.drafts[original.chatID] == original.text { next.drafts[original.chatID] = "" }
                }
                guard persist(next) else { return }; if saved.selected == original.chatID { snapshot = current; snapshotFresh = true }
                let state = current.ticketState
                if state == "WAITING" { workStatuses[original.chatID] = String(localized: "正在等待模型容量…") }
                else if state == "GRANTED", saved.pendingChats[original.chatID]?.executionRequested == false {
                    next = saved; next.pendingChats[original.chatID]?.executionRequested = true; guard persist(next) else { return }
                    workStatuses[original.chatID] = String(localized: "正在回复…"); liveReplies[original.chatID] = ""
                    do { try await service.execute(original) { [weak self] delta in
                        guard let self, self.workGenerations[original.chatID] == generation, self.saved.pendingChats[original.chatID]?.turnID == original.turnID else { return }
                        self.liveReplies[original.chatID, default: ""] += delta
                    } }
                    catch { if saved.selected == original.chatID || error is AuthenticationError { report(error) } }
                } else if ["RUNNING", "STOPPING"].contains(state) { workStatuses[original.chatID] = state == "STOPPING" ? String(localized: "正在停止…") : String(localized: "正在回复…") }
                else if state == "UNKNOWN" { workStatuses[original.chatID] = String(localized: "结果待确认"); if saved.selected == original.chatID { error = String(localized: "回复结果尚未确认，请稍后核对或联系管理员。") }; return }
                else if ["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"].contains(state) {
                    if state == "SUCCEEDED", current.turns.last?.state == "PREPARED" { workStatuses[original.chatID] = String(localized: "正在保存回复…") }
                    else {
                        next = saved; next.pendingChats.removeValue(forKey: original.chatID)
                        let complete = state == "SUCCEEDED" && current.turns.last?.state == "SUCCEEDED"
                        if !complete, original.ownsDraft, next.drafts[original.chatID, default: ""].isEmpty { next.drafts[original.chatID] = original.text }
                        guard persist(next) else { return }; liveReplies[original.chatID] = ""; workStatuses[original.chatID] = nil
                        if saved.selected == original.chatID { error = state == "SUCCEEDED" && current.turns.last?.state == "SUCCEEDED" ? nil : String(localized: "这次回复未完成，内容已保留。") }
                        let updatedChats = try await service.list(); try Task.checkCancellation(); try store.requireActive(); chats = updatedChats.map { preservingHistory($0) }; return
                    }
                }
                try await Task.sleep(for: .seconds(1))
            } while !Task.isCancelled
        } catch { if workGenerations[original.chatID] == generation, saved.selected == original.chatID || error is AuthenticationError { report(error) } }
    }
    func stop() async {
        guard let id = saved.selected, let pending = saved.pendingChats[id] else { return }
        do {
            try await service.cancel(pending); try store.requireActive()
            workGenerations[id] = UUID(); workers.removeValue(forKey: id)?.cancel()
            workStatus = String(localized: "正在停止…"); recover()
        } catch { report(error) }
    }
    func deactivate() {
        opening = UUID(); ready = false
        for (id, worker) in workers { workGenerations[id] = UUID(); worker.cancel() }
        workers.removeAll()
    }
    private func report(_ failure: Error) {
        if failure is CancellationError || (failure as? URLError)?.code == .cancelled { return }
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}
