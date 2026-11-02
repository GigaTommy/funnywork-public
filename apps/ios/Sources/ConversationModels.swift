import Foundation
import CoreFoundation

indirect enum JSONValue: Codable, Equatable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null
    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let v = try? value.decode(Bool.self) { self = .bool(v) }
        else if let v = try? value.decode(String.self) { self = .string(v) }
        else if let v = try? value.decode(Double.self) { self = .number(v) }
        else if let v = try? value.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try value.decode([JSONValue].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .string(let v): try value.encode(v)
        case .number(let v): try value.encode(v)
        case .bool(let v): try value.encode(v)
        case .object(let v): try value.encode(v)
        case .array(let v): try value.encode(v)
        case .null: try value.encodeNil()
        }
    }
}

struct QuestionOption: Identifiable {
    var id: String { label }
    let label: String
    let detail: String
}
struct RemoteQuestion: Identifiable {
    let id: String
    let title: String
    let detail: String
    let multiple: Bool
    let options: [QuestionOption]
    init?(_ row: [String: Any]) {
        guard let id = row["id"] as? String, let title = row["question"] as? String else { return nil }
        self.id = id; self.title = title
        detail = row["detail"] as? String ?? ""
        multiple = row["multiSelect"] as? Bool ?? false
        var labels = Set<String>()
        options = (row["options"] as? [[String: Any]] ?? []).compactMap {
            guard let label = $0["label"] as? String, labels.insert(label).inserted else { return nil }
            return QuestionOption(label: label, detail: $0["description"] as? String ?? "")
        }
    }
}
struct QuestionDraft: Codable, Equatable {
    var selected: [String] = []
    var custom = ""
    func isValid(for question: RemoteQuestion) -> Bool {
        let available = Set(question.options.map(\.label))
        return custom.count <= 8000 && (question.multiple || selected.count <= 1)
            && selected.allSatisfy(available.contains)
            && (!selected.isEmpty || !custom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
    func payload(id: String) -> JSONValue {
        .object(["id": .string(id), "selected": .array(selected.map(JSONValue.string)), "custom": .string(custom)])
    }
}
struct Interaction: Identifiable {
    enum Kind { case approval, questions, continuedQuestions }
    let id: String
    let correlation: String
    let epoch: String
    let kind: Kind
    let title: String
    let reason: String
    let detail: String
    let questions: [RemoteQuestion]
}
struct LiveAssistantReply: Equatable {
    let attemptID: String
    let startedAfter: Int
    let turnStart: Int
    let text: String
    let truncated: Bool
    init?(_ value: [String: Any]) {
        guard let attempt = value["attemptId"] as? String, !attempt.isEmpty, attempt.utf8.count <= 256,
              let started = value["startedAfterSeq"] as? Int, started >= -1,
              let turn = value["turnStart"] as? Int, turn >= 0, started >= turn,
              let text = value["text"] as? String, !text.isEmpty, text.utf16.count <= 16000,
              let truncated = value["truncated"] as? Bool else { return nil }
        attemptID = attempt; startedAfter = started; turnStart = turn; self.text = text; self.truncated = truncated
    }
}
struct RemoteTokenUsage: Equatable {
    let input: Int64
    let output: Int64
    let cached: Int64
    let cacheRead: Int64
    let cacheWrite: Int64
    var total: Int64 { input + output + cached }
    init?(_ row: [String: Any]) {
        func count(_ key: String) -> Int64? {
            guard let number = row[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue >= 0, number.doubleValue <= 9007199254740991,
                  number.doubleValue == Double(number.int64Value) else { return nil }
            return number.int64Value
        }
        guard let input = count("uncachedInputTokens"), let output = count("outputTokens"),
              let read = count("cacheReadTokens"), let write = count("cacheWriteTokens"),
              input + output + read + write <= 9007199254740991 else { return nil }
        self.input = input; self.output = output; cached = read + write; cacheRead = read; cacheWrite = write
    }
}
struct RemoteQueueItem: Identifiable {
    let id: String
    let text: String
    let editable: Bool
}
struct RemoteSnapshot {
    var online = false
    var epoch = ""
    var running = false
    var admissionWaiting = 0
    var tokenUsage: RemoteTokenUsage?
    var performance: ChatPerformanceSummary?
    var selectedModel: String?
    var agentPreset: String?
    var attachmentUploads = false
    var sessionForks = false
    var turn: Int?
    var liveReply: LiveAssistantReply?
    var interactions: [Interaction] = []
    var queue: [RemoteQueueItem] = []
    init() {}
    init(_ row: [String: Any]) throws {
        guard let available = row["online"] as? Bool, let epoch = row["epoch"] as? String else { throw ClientError.invalidResponse }
        let state = row["state"] as? [String: Any] ?? [:]
        self.epoch = epoch
        attachmentUploads = row["attachmentUploads"] as? Bool ?? false
        online = available && !epoch.isEmpty && state["controllable"] as? Bool != false
        if let flag = row["sessionForks"] as? NSNumber, CFGetTypeID(flag) == CFBooleanGetTypeID(),
           let allowed = state["controllable"] as? NSNumber, CFGetTypeID(allowed) == CFBooleanGetTypeID() {
            sessionForks = online && allowed.boolValue && flag.boolValue
        }
        running = state["running"] as? Bool ?? false
        turn = state["turnStart"] as? Int
        liveReply = (state["liveAssistant"] as? [String: Any]).flatMap(LiveAssistantReply.init)
        var found: [Interaction] = []
        for item in state["pending"] as? [[String: Any]] ?? [] {
            guard let id = item["eventId"] as? String, let request = item["request"] as? [String: Any] else { continue }
            let event = item["event"] as? String
            guard event == "approval/request" || event == "user-questions/request" else { continue }
            let details = (try? JSONSerialization.data(withJSONObject: request, options: [.prettyPrinted, .sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? ""
            found.append(Interaction(id: epoch + "/event/" + id, correlation: id, epoch: epoch,
                kind: event == "approval/request" ? .approval : .questions,
                title: request["toolName"] as? String ?? String(localized: "需要你的回答"),
                reason: request["reason"] as? String ?? "", detail: details,
                questions: (request["questions"] as? [[String: Any]] ?? []).compactMap(RemoteQuestion.init)))
        }
        if let rows = state["queue"] as? [[String: Any]], rows.count <= 32 {
            var seen = Set<String>()
            queue = rows.compactMap { item in
                guard let id = item["id"] as? String, !id.isEmpty, id.utf8.count <= 256, seen.insert(id).inserted,
                      let text = item["text"] as? String, text.utf16.count <= 4000, let editable = item["editable"] as? Bool else { return nil }
                return RemoteQueueItem(id: id, text: text, editable: editable)
            }
        }
        let projections = state["projections"] as? [String: Any] ?? [:]
        if let count = state["admissionWaiting"] as? NSNumber,
           CFGetTypeID(count) != CFBooleanGetTypeID(), count.doubleValue >= 0,
           count.doubleValue <= 10000, count.doubleValue == Double(count.intValue) {
            admissionWaiting = online && running ? count.intValue : 0
        }
        tokenUsage = (projections["tokenUsage"] as? [String: Any]).flatMap(RemoteTokenUsage.init)
        performance = (projections["sessionStats"] as? [String: Any]).flatMap { ChatPerformanceSummary(remote: $0) }
        let selection = projections["modelSelection"] as? [String: Any] ?? [:]
        let nextModel = selection["next"] as? [String: Any]
        let lastModel = selection["lastUsed"] as? [String: Any]
        if let value = running ? lastModel ?? nextModel : nextModel ?? lastModel,
           let name = value["model"] as? String, !name.isEmpty, name.count <= 256 {
            selectedModel = name
        }
        if let preset = projections["agentPreset"] as? String, !preset.isEmpty, preset.count <= 256 {
            agentPreset = preset
        }
        if let name = state["modelName"] as? String, selectedModel != nil, !name.isEmpty, name.count <= 256 { selectedModel = name }
        if let name = state["agentPresetName"] as? String, agentPreset != nil, !name.isEmpty, name.count <= 256 { agentPreset = name }
        let questions = projections["userQuestions"] as? [String: Any] ?? [:]
        for item in questions["active"] as? [[String: Any]] ?? [] {
            guard item["state"] as? String == "continued", let call = item["callId"] as? String else { continue }
            found.append(Interaction(id: epoch + "/call/" + call, correlation: call, epoch: epoch,
                kind: .continuedQuestions, title: String(localized: "需要你的回答"), reason: "", detail: "",
                questions: (item["questions"] as? [[String: Any]] ?? []).compactMap(RemoteQuestion.init)))
        }
        var ids = Set<String>()
        interactions = found.filter { ids.insert($0.id).inserted }
    }
}
struct RemoteCommand: Codable, Equatable, Identifiable {
    let id: String
    let epoch: String
    let kind: String
    let payload: [String: JSONValue]
    let createdAt: Date
    let interactionID: String?
    init(epoch: String, kind: String, payload: [String: JSONValue], interactionID: String? = nil, now: Date = Date()) {
        id = UUID().uuidString; self.epoch = epoch; self.kind = kind; self.payload = payload
        createdAt = now; self.interactionID = interactionID
    }
    var body: [String: Any] {
        let raw = try? JSONEncoder().encode(payload)
        let data = raw.flatMap { try? JSONSerialization.jsonObject(with: $0) } ?? [:]
        return ["id": id, "epoch": epoch, "kind": kind, "payload": data]
    }
    func canRetry(snapshot: RemoteSnapshot, now: Date = Date()) -> Bool {
        let age = now.timeIntervalSince(createdAt)
        return snapshot.online && snapshot.epoch == epoch && age >= 0 && age < 25
    }
}
struct CommandReceipt: Identifiable {
    let id: String
    let status: String
    init?(_ row: [String: Any]) {
        guard let id = row["id"] as? String, let status = row["status"] as? String else { return nil }
        self.id = id; self.status = status
    }
    var label: String {
        switch status {
        case "queued": return String(localized: "已排队")
        case "claimed": return String(localized: "已接收")
        case "accepted": return String(localized: "操作已接受")
        case "rejected": return String(localized: "操作被拒绝")
        case "expired": return String(localized: "操作已过期")
        case "uncertain": return String(localized: "结果待确认")
        default: return String(localized: "确认中")
        }
    }
}
struct EventPage {
    let events: [HistoryItem]
    let next: Int
    let durable: Int
    init(_ row: [String: Any], offset: Int) throws {
        guard let next = row["nextSeq"] as? Int, let end = row["durableNextSeq"] as? Int,
              let rows = row["events"] as? [[String: Any]], next >= offset, end >= next else { throw ClientError.invalidResponse }
        let items = rows.compactMap(HistoryItem.init)
        guard items.count == rows.count,
              items.enumerated().allSatisfy({ $0.element.id == offset + $0.offset }),
              next == offset + items.count else { throw ClientError.invalidResponse }
        events = items; self.next = next; durable = end
    }
}
extension ClientError {
    static var invalidResponse: ClientError { ClientError(message: String(localized: "暂时无法完成，请重试。")) }
}

@MainActor protocol ConversationService {
    func snapshot() async throws -> RemoteSnapshot
    func receipts() async throws -> [CommandReceipt]
    func receipt(id: String) async throws -> CommandReceipt?
    func events(offset: Int, limit: Int) async throws -> EventPage
    func send(_ command: RemoteCommand) async throws -> CommandReceipt
}
extension ConversationService {
    func receipt(id: String) async throws -> CommandReceipt? { try await receipts().first { $0.id == id } }
}
@MainActor struct HTTPConversationService: ConversationService {
    let api: API
    let sessionID: String
    private var base: String { "sync/sessions/\(sessionID)" }
    func snapshot() async throws -> RemoteSnapshot {
        guard let row = try await api.request(base + "/state") as? [String: Any] else { throw ClientError.invalidResponse }
        return try RemoteSnapshot(row)
    }
    func receipts() async throws -> [CommandReceipt] {
        guard let rows = try await api.request(base + "/commands") as? [[String: Any]] else { throw ClientError.invalidResponse }
        return rows.compactMap(CommandReceipt.init)
    }
    func receipt(id: String) async throws -> CommandReceipt? {
        guard CreationDevice.validID(id), let row = try await api.request(base + "/commands/" + id) as? [String: Any],
              let receipt = CommandReceipt(row), receipt.id == id else { throw ClientError.invalidResponse }
        return receipt
    }
    func events(offset: Int, limit: Int) async throws -> EventPage {
        guard let row = try await api.request(base + "/events?offset=\(offset)&limit=\(limit)") as? [String: Any] else { throw ClientError.invalidResponse }
        return try EventPage(row, offset: offset)
    }
    func send(_ command: RemoteCommand) async throws -> CommandReceipt {
        guard let row = try await api.request(base + "/commands", body: command.body) as? [String: Any],
              let receipt = CommandReceipt(row), receipt.id == command.id else { throw ClientError.invalidResponse }
        return receipt
    }
}
