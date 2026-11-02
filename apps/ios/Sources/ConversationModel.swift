import Foundation
import Combine
import PhotosUI
import SwiftUI

@MainActor final class ConversationModel: ObservableObject {
    @Published private(set) var saved = SavedConversation()
    @Published private(set) var items: [HistoryItem] = []
    @Published private(set) var resultReview: RemoteResultReview?
    @Published private(set) var resultVersions: [String: ResultVersion] = [:]
    private var reviewRoutes: [Int: RemoteResultReview] = [:]
    private var queueControlGeneration = UUID()
    @Published private(set) var queueRefreshPending = false
    @Published private(set) var remote = RemoteSnapshot()
    @Published private(set) var receipts: [CommandReceipt] = []
    @Published private(set) var refreshing = false
    @Published private(set) var loadingHistory = false
    @Published private(set) var sending = false
    @Published private(set) var earliest = 0
    @Published private(set) var latest: Int?
    @Published private(set) var lastSync: Date?
    @Published private(set) var showingCachedHistory = false
    @Published private(set) var historyDate: Date?
    @Published var error: String?
    @Published private(set) var storageError: String?
    private var lastCachedHistory: CachedHistory?
    @Published private(set) var uploading = false
    @Published private(set) var importingAttachments = false
    @Published private(set) var uploadProgress = 0.0
    @Published var uploadError: String?
    private var uploadTask: Task<Void, Never>?
    private var uploadGeneration = UUID()
    private let uploadService: UploadService?
    private let service: ConversationService
    private let store: ConversationStore
    private let sessionID: String
    private let authFailure: (Error) -> Void
    private var storageReady = true
    @Published private(set) var controlReady = false
    var draft: String { saved.draft }
    var pending: RemoteCommand? { saved.pending }
    var canOperate: Bool { remote.online && controlReady && storageReady && !sending && pending == nil }
    var files: [OutgoingAttachment] { saved.files }
    var canEditFiles: Bool { storageReady && !sending && pending == nil && !uploading }
    var canAddFiles: Bool { canEditFiles && uploadService != nil && remote.attachmentUploads && controlReady && files.count < 4 }
    var validPrompt: Bool {
        (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !files.isEmpty) && draft.utf16.count <= 16000
            && !uploading && files.allSatisfy(\.uploaded) && (files.isEmpty || remote.attachmentUploads)
    }
    var canRetry: Bool {
        controlReady && !sending && pending?.canRetry(snapshot: remote) == true
            && !receipts.contains { $0.id == pending?.id && $0.status == "uncertain" }
    }
    var interactions: [Interaction] { remote.interactions }
    var showingExecution: Bool {
        controlReady && remote.online && remote.running && !interactions.contains(where: { !blocked($0) })
    }
    var liveReply: LiveAssistantReply? {
        guard controlReady, remote.online, remote.running, let reply = remote.liveReply,
              reply.turnStart == remote.turn,
              !items.contains(where: { $0.id > reply.startedAfter && ["assistant/message", "assistant/attempt"].contains($0.eventType ?? "") }) else { return nil }
        return reply
    }
    var executionLabel: String {
        remote.admissionWaiting > 0 ? String(localized: "正在等待模型容量…") : String(localized: "正在处理…")
    }
    var queuedPrompts: [(command: RemoteCommand, receipt: CommandReceipt)] {
        guard controlReady, remote.online else { return [] }
        let active = Dictionary(receipts.filter { ["queued", "claimed"].contains($0.status) }.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return saved.recentCommands.reversed().compactMap { command in
            guard command.kind == "prompt", command.epoch == remote.epoch, let receipt = active[command.id] else { return nil }
            return (command, receipt)
        }
    }

    init(sessionID: String, service: ConversationService, store: ConversationStore, uploadService: UploadService? = nil, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.uploadService = uploadService
        self.sessionID = sessionID; self.service = service; self.store = store; self.authFailure = authFailure
        do { saved = try store.load(sessionID); saved.files = saved.files.map { var file = $0; file.uploaded = false; return file } }
        catch { storageReady = false; storageError = String(localized: "无法读取草稿，请重新打开对话。") }
        receipts = saved.recentCommands.compactMap { command in
            saved.admissionRejections[command.id] == nil ? nil : CommandReceipt(["id": command.id, "status": "rejected"])
        }
        if let cache = try? store.loadHistory(sessionID) {
            items = cache.items; earliest = cache.earliest; latest = cache.latest
            historyDate = cache.date; showingCachedHistory = true; lastCachedHistory = cache
            refreshReviewRoutes()
        }
    }
    @discardableResult private func persist(_ value: SavedConversation) -> Bool {
        guard storageReady else { return false }
        do { try store.save(value, for: sessionID); saved = value; storageError = nil; return true }
        catch { storageError = String(localized: "保存失败，内容尚未发送。请重试。"); return false }
    }
    func updateDraft(_ text: String) {
        var next = saved; next.draft = text
        if !persist(next) { saved.draft = text }
    }
    func answerKey(_ interaction: Interaction, _ question: RemoteQuestion) -> String {
        // JSON tuple avoids ambiguous concatenation of remote IDs.
        String(data: try! JSONEncoder().encode([interaction.id, question.id]), encoding: .utf8)!
    }
    func answer(_ interaction: Interaction, _ question: RemoteQuestion) -> QuestionDraft {
        saved.answers[answerKey(interaction, question)] ?? QuestionDraft()
    }
    func updateAnswer(_ value: QuestionDraft, interaction: Interaction, question: RemoteQuestion) {
        var next = saved; next.answers[answerKey(interaction, question)] = value
        if !persist(next) { saved.answers = next.answers }
    }
    func blocked(_ interaction: Interaction) -> Bool {
        guard let id = saved.interactionRequests[interaction.id] else { return false }
        if saved.admissionRejections[id] != nil { return true }
        guard let receipt = receipts.first(where: { $0.id == id }) else { return true }
        return ["queued", "claimed", "accepted", "uncertain"].contains(receipt.status)
    }
    func answersValid(_ interaction: Interaction) -> Bool {
        !interaction.questions.isEmpty && interaction.questions.allSatisfy { answer(interaction, $0).isValid(for: $0) }
    }
    func refresh() async {
        guard !refreshing else { return }
        refreshing = true; error = nil
        let controlAttempt = queueControlGeneration
        defer { refreshing = false }
        async let history: Void = loadLatest()
        do {
            async let stateRequest = service.snapshot()
            async let receiptsRequest = service.receipts()
            let (state, initialCommands) = try await (stateRequest, receiptsRequest)
            var commands = initialCommands
            for command in saved.recentCommands where saved.admissionRejections[command.id] != nil && !commands.contains(where: { $0.id == command.id }) {
                commands.append(CommandReceipt(["id": command.id, "status": "rejected"])!)
            }
            let trackedIDs = Set(saved.queueWithdrawals.keys).union(saved.queueEdits.values.filter { !["accepted", "rejected", "expired"].contains($0.status) }.compactMap { $0.command?.id })
            for id in trackedIDs where !commands.contains(where: { $0.id == id }) {
                if let receipt = try await service.receipt(id: id) { commands.append(receipt) }
            }
            try Task.checkCancellation(); try store.requireActive()
            remote = state; receipts = commands; controlReady = controlAttempt == queueControlGeneration; lastSync = Date()
            if controlReady { queueRefreshPending = false }
            reconcileWithdrawals()
            reconcileQueueEdits()
            if let pending, let receipt = commands.first(where: { $0.id == pending.id }) {
                reconcile(pending, receipt: receipt)
            }
        } catch {
            controlReady = false
            report(error)
        }
        await history
    }
    func deactivate() { controlReady = false; pauseUploads() }
    private func cacheHistory() {
        guard let latest else { return }
        historyDate = Date(); showingCachedHistory = false
        let cache = CachedHistory(items: items, latest: latest)
        guard lastCachedHistory?.latest != cache.latest || lastCachedHistory?.items != cache.items else { return }
        do { try store.saveHistory(cache, for: sessionID); lastCachedHistory = cache } catch { /* Read-only cache failure must not block the conversation. */ }
    }
    private func merge(_ added: [HistoryItem]) {
        guard !added.isEmpty else { return }
        var values = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
        for item in added { values[item.id] = item }
        items = values.values.sorted { $0.id < $1.id }
        refreshReviewRoutes()
    }
    private func range(start: Int, end: Int) async throws -> [HistoryItem] {
        var cursor = start
        var result: [HistoryItem] = []
        while cursor < end {
            try Task.checkCancellation(); try store.requireActive()
            let page = try await service.events(offset: cursor, limit: min(50, end - cursor))
            guard page.next > cursor, page.next <= end else { throw ClientError.invalidResponse }
            result += page.events; cursor = page.next
        }
        return result
    }
    func loadLatest() async {
        guard !loadingHistory else { return }
        loadingHistory = true
        defer { loadingHistory = false }
        do {
            if let cursor = latest {
                let page = try await service.events(offset: cursor, limit: 100)
                try Task.checkCancellation(); try store.requireActive()
                guard page.next >= cursor else { throw ClientError.invalidResponse }
                if page.events.isEmpty && page.next == cursor {
                    historyDate = Date(); showingCachedHistory = false
                    return
                }
                merge(page.events); latest = page.next
            } else {
                // Existing API exposes the durable cursor; no new server endpoint is required.
                let head = try await service.events(offset: 0, limit: 1)
                let start = max(0, head.durable - 50)
                let values = try await range(start: start, end: head.durable)
                try Task.checkCancellation(); try store.requireActive()
                merge(values); earliest = start; latest = head.durable
            }
            cacheHistory()
        } catch { report(error) }
    }
    func loadEarlier() async {
        guard !loadingHistory, earliest > 0 else { return }
        loadingHistory = true
        defer { loadingHistory = false }
        do {
            let start = max(0, earliest - 50)
            let values = try await range(start: start, end: earliest)
            try Task.checkCancellation()
            merge(values); earliest = start
            cacheHistory()
        } catch { report(error) }
    }
    /// Gaps are explicit when search opens an older window beside the latest history.
    var historyGaps: [Int: Range<Int>] {
        var result: [Int: Range<Int>] = [:]
        for (previous, next) in zip(items, items.dropFirst()) where next.id > previous.id + 1 {
            result[next.id] = (previous.id + 1)..<next.id
        }
        return result
    }
    private func waitForHistory() async throws {
        while loadingHistory { try await Task.sleep(for: .milliseconds(50)) }
        try Task.checkCancellation()
    }
    func loadAround(_ sequence: Int) async -> Bool {
        guard sequence >= 0 else { return false }
        do {
            try await waitForHistory()
            if items.contains(where: { $0.id == sequence }) { return true }
            if latest == nil { await loadLatest() }
            try await waitForHistory()
            if items.contains(where: { $0.id == sequence }) { return true }
            guard latest != nil else { return false }
            loadingHistory = true
            defer { loadingHistory = false }
            let head = try await service.events(offset: sequence, limit: 1)
            guard head.events.first?.id == sequence, head.durable > sequence else { throw ClientError.invalidResponse }
            let start = max(0, sequence - 25)
            let end = sequence + min(26, head.durable - sequence)
            let values = try await range(start: start, end: end)
            try Task.checkCancellation(); try store.requireActive()
            merge(values); earliest = items.first?.id ?? start; latest = max(latest ?? end, end)
            cacheHistory()
            return true
        } catch { report(error); return false }
    }
    func loadGap(before sequence: Int) async -> Int? {
        do {
            try await waitForHistory()
            guard let gap = historyGaps[sequence] else { return nil }
            loadingHistory = true
            defer { loadingHistory = false }
            let end = gap.lowerBound + min(50, gap.count)
            let values = try await range(start: gap.lowerBound, end: end)
            try Task.checkCancellation(); try store.requireActive()
            merge(values); cacheHistory()
            return gap.lowerBound
        } catch { report(error); return nil }
    }
    func prompt(steer: Bool = false) async {
        guard validPrompt, !withdrawalPending else { return }
        var payload: [String: JSONValue] = ["text": .string(draft), "mode": .string(steer ? "steer" : "queue")]
        if !files.isEmpty { payload["attachments"] = .array(files.map(\.payload)) }
        if steer {
            guard remote.running, let turn = remote.turn else { return }
            payload["expectedTurn"] = .number(Double(turn))
        }
        await prepare(kind: "prompt", payload: payload)
    }
    @discardableResult func updateQueue(_ item: RemoteQueueItem, action: String, text: String = "") async -> Bool {
        guard canOperate, !queueEditWaiting(item.id), remote.queue.contains(where: { $0.id == item.id }), ["edit", "remove", "steer"].contains(action),
              action != "edit" || item.editable && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.utf16.count <= 16000 else { return false }
        let previous = saved.recentCommands.first?.id
        var payload: [String: JSONValue] = ["itemId": .string(item.id), "action": .string(action)]
        if action == "edit" { payload["text"] = .string(text) }
        await prepare(kind: "queueEdit", payload: payload)
        guard let command = saved.recentCommands.first, command.id != previous else { return false }
        let accepted = receipts.contains { $0.id == command.id && $0.status == "accepted" }
        if accepted { await refresh() }
        return accepted
    }
    var withdrawalPending: Bool { !saved.queueWithdrawals.isEmpty }
    var canKeepWithdrawalCopy: Bool {
        guard !sending, !refreshing, !saved.queueWithdrawals.isEmpty else { return false }
        return saved.queueWithdrawals.keys.allSatisfy { id in
            receipts.contains { $0.id == id && $0.status == "uncertain" } ||
                saved.recentCommands.contains { $0.id == id && Date().timeIntervalSince($0.createdAt) >= 30 }
        }
    }
    func keepWithdrawalCopyAfterReview() {
        guard canKeepWithdrawalCopy else { return }
        var next = saved
        for (id, withdrawal) in next.queueWithdrawals.sorted(by: { $0.key < $1.key }) {
            next.draft = Self.appendingDraft(withdrawal.text, to: next.draft)
            if next.pending?.id == id { next.pending = nil }
        }
        next.queueWithdrawals = [:]
        persist(next)
    }
    func withdrawing(_ item: RemoteQueueItem) -> Bool { saved.queueWithdrawals.values.contains { $0.itemID == item.id } }
    func withdrawToDraft(_ item: RemoteQueueItem) async {
        guard canOperate, !withdrawalPending, !queueEditWaiting(item.id), item.editable,
              let current = remote.queue.first(where: { $0.id == item.id }), current.editable,
              current.text == item.text else { return }
        await prepare(kind: "queueEdit", payload: ["itemId": .string(item.id), "action": .string("remove")],
                      withdrawal: QueueWithdrawal(itemID: item.id, text: item.text))
        if !withdrawalPending, pending == nil { await refresh() }
    }
    private func reconcileWithdrawals() {
        guard !saved.queueWithdrawals.isEmpty else { return }
        do {
            var next = try store.load(sessionID)
            for (id, withdrawal) in next.queueWithdrawals.sorted(by: { $0.key < $1.key }) {
                guard let receipt = receipts.first(where: { $0.id == id }) else { continue }
                if receipt.status == "accepted" {
                    // Only the desktop's final receipt proves removal. Preserve newer typing and files.
                    next.draft = Self.appendingDraft(withdrawal.text, to: next.draft)
                    next.queueWithdrawals.removeValue(forKey: id)
                    controlReady = false
                } else if ["rejected", "expired"].contains(receipt.status) {
                    next.queueWithdrawals.removeValue(forKey: id)
                    error = String(localized: "未能撤回，内容可能已开始处理。请刷新队列。")
                }
            }
            if next != saved { persist(next) }
        } catch { report(error) }
    }
    static func appendingDraft(_ text: String, to draft: String) -> String {
        guard !text.isEmpty else { return draft }
        return draft.isEmpty ? text : draft + "\n\n" + text
    }
    @discardableResult func appendDraft(_ text: String) -> Bool {
        guard storageReady, pending == nil, !sending else { return false }
        var next = saved; next.draft = Self.appendingDraft(text, to: saved.draft)
        return persist(next)
    }
    func updateReviewDraft(_ text: String, key: String, result: RemoteResultReview? = nil) {
        var next = saved; next.reviewDrafts[key] = text
        if let result, result.key == key { next.reviewSnapshots[key] = result }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !(next.reviewFileNotes[key]?.values.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? false) { next.reviewSnapshots.removeValue(forKey: key) }
        if !persist(next) { saved.reviewDrafts[key] = text }
    }
    @discardableResult func stageReviewFeedback(_ text: String, key: String) -> Bool {
        guard storageReady, saved.reviewDrafts[key] != nil else { return false }
        var next = saved; next.draft = Self.appendingDraft(text, to: saved.draft)
        next.reviewDrafts.removeValue(forKey: key)
        next.reviewSnapshots.removeValue(forKey: key)
        next.reviewFileNotes.removeValue(forKey: key)
        return persist(next)
    }
    func updateReviewFileNote(_ text: String, change: ResultChange, result: RemoteResultReview) {
        var next = saved; next.reviewFileNotes[result.key, default: [:]][change.id] = text
        next.reviewSnapshots[result.key] = result
        if next.reviewDrafts[result.key] == nil { next.reviewDrafts[result.key] = "" }
        if !persist(next), (try? store.requireActive()) != nil { saved = next }
    }
    func pendingReviewNotes(_ key: String) -> String {
        let notes = saved.reviewFileNotes[key] ?? [:], changes = saved.reviewSnapshots[key]?.changes ?? []
        return notes.sorted { $0.key < $1.key }.compactMap { id, note -> String? in
            guard !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            let change = changes.first { $0.id == id }
            return (change.map { "#\($0.sequence) · \($0.path)" } ?? "#" + key) + "\n" + note
        }.joined(separator: "\n\n")
    }
    @discardableResult func commitReviewFileNote(_ change: ResultChange, result: RemoteResultReview) -> Bool {
        guard let note = saved.reviewFileNotes[result.key]?[change.id], !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return true }
        var next = saved
        let text = "#\(change.sequence) · \(change.path)\n" + note
        next.reviewDrafts[result.key] = Self.appendingDraft(text, to: next.reviewDrafts[result.key] ?? "")
        next.reviewFileNotes[result.key]?.removeValue(forKey: change.id)
        return persist(next)
    }
    var unsubmittedReviewKeys: [String] {
        let drafts = Set(saved.reviewDrafts.filter { !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.keys)
        let notes = Set(saved.reviewFileNotes.filter { $0.value.values.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }.keys)
        return drafts.union(notes).sorted { (Int($0) ?? -1) > (Int($1) ?? -1) }
    }
    func reviewSnapshot(_ key: String) -> RemoteResultReview? {
        if let value = saved.reviewSnapshots[key] { return value }
        guard let sequence = Int(key), sequence >= 0, let value = RemoteResultReview(items: items.filter { $0.id <= sequence }), value.key == key else { return nil }
        return value
    }
    func recoverReview(_ key: String) async -> RemoteResultReview? {
        guard !Task.isCancelled, (try? store.requireActive()) != nil else { return nil }
        if let value = reviewSnapshot(key) { return value }
        if let sequence = Int(key), sequence >= 0, await loadAround(sequence), let value = reviewSnapshot(key) { return value }
        guard !Task.isCancelled else { return nil }
        error = String(localized: "这轮记录暂不可用，审阅意见仍已保留。")
        return nil
    }
    var sendDisabledReason: String? {
        if storageError != nil { return storageError }
        if sending { return String(localized: "正在提交，请稍候。") }
        if withdrawalPending { return String(localized: "正在核对撤回，草稿已保留。") }
        if pending != nil { return String(localized: "请先核对上一条操作。") }
        if queueRefreshPending { return refreshing ? String(localized: "正在刷新队列状态，草稿已保留。") : String(localized: "请刷新队列状态后继续。") }
        if !controlReady { return refreshing ? String(localized: "正在核对连接，草稿已保留。") : String(localized: "连接尚未确认，请重试。") }
        if !remote.online { return String(localized: "此电脑离线，草稿已保留。") }
        if uploading || files.contains(where: { !$0.uploaded }) { return String(localized: "请先完成附件上传。") }
        if draft.utf16.count > 16000 { return String(localized: "内容过长，请缩短后重试。") }
        return nil
    }
    private func refreshReviewRoutes() {
        resultVersions = [:]
        for item in items { for result in item.results { resultVersions[result.identity] = result } }
        resultReview = RemoteResultReview(items: items)
        var routes: [Int: RemoteResultReview] = [:]; var round: [HistoryItem] = []
        func add(_ rows: [HistoryItem]) {
            if let review = RemoteResultReview(items: rows) {
                for message in review.messages { routes[message.id] = review }
            }
        }
        var previous: Int?
        for item in items {
            if let previous, item.id > previous + 1 { add(round); round = [] }
            previous = item.id
            if item.eventType == "turn/start" { add(round); round = [] }
            round.append(item)
            if item.eventType == "turn/end" { add(round); round = [] }
        }
        add(round); reviewRoutes = routes
    }
    func reviewContaining(_ sequence: Int) -> RemoteResultReview? { reviewRoutes[sequence] }
    func queueEditText(_ item: RemoteQueueItem) -> String {
        guard let edit = saved.queueEdits[item.id], edit.status != "accepted" else { return item.text }
        return edit.text
    }
    func queueEditWaiting(_ itemID: String) -> Bool {
        guard let edit = saved.queueEdits[itemID], edit.command != nil else { return false }
        return !["accepted", "rejected", "expired"].contains(edit.status)
    }
    func queueEditLabel(_ edit: QueueEditDraft) -> String {
        switch edit.status {
        case "accepted": return String(localized: "修改已生效")
        case "rejected", "expired": return String(localized: "修改未生效，编辑副本已保留")
        case "queued", "claimed": return String(localized: "修改已提交，等待电脑确认")
        default: return edit.command == nil ? String(localized: "未提交的队列编辑") : String(localized: "修改结果待确认")
        }
    }
    func saveQueueEdit(_ itemID: String, text: String) {
        guard !queueEditWaiting(itemID) else { return }
        var next = saved; next.queueEdits[itemID] = QueueEditDraft(itemID: itemID, text: text)
        persist(next)
    }
    private func reconcileQueueEdits() {
        guard !saved.queueEdits.isEmpty else { return }
        do {
            var next = try store.load(sessionID)
            for (itemID, edit) in next.queueEdits {
                guard !["accepted", "rejected", "expired"].contains(edit.status), let command = edit.command, let receipt = receipts.first(where: { $0.id == command.id }) else { continue }
                var value = edit; value.status = receipt.status; next.queueEdits[itemID] = value
                if receipt.status == "accepted" { queueControlGeneration = UUID(); queueRefreshPending = true; controlReady = false }
            }
            if next != saved { persist(next) }
        } catch { report(error) }
    }
    func cancel() async {
        guard remote.running, let turn = remote.turn else { return }
        await prepare(kind: "cancel", payload: ["expectedTurn": .number(Double(turn))])
    }
    func decide(_ interaction: Interaction, allow: Bool) async {
        guard interaction.kind == .approval else { return }
        await prepare(kind: "decision", payload: ["eventId": .string(interaction.correlation), "outcome": .string(allow ? "allowed-once" : "rejected")], interaction: interaction)
    }
    func reply(_ interaction: Interaction) async {
        guard answersValid(interaction) else { return }
        let answers = interaction.questions.map { answer(interaction, $0).payload(id: $0.id) }
        var payload: [String: JSONValue] = ["answer": .object(["answers": .array(answers)])]
        let continued = interaction.kind == .continuedQuestions
        payload[continued ? "callId" : "eventId"] = .string(interaction.correlation)
        await prepare(kind: continued ? "answer" : "decision", payload: payload, interaction: interaction)
    }
    private func prepare(kind: String, payload: [String: JSONValue], interaction: Interaction? = nil, withdrawal: QueueWithdrawal? = nil) async {
        guard canOperate else { return }
        if let interaction {
            guard remote.epoch == interaction.epoch, interactions.contains(where: { $0.id == interaction.id }), !blocked(interaction) else { return }
        }
        guard let data = try? JSONEncoder().encode(payload), data.count <= 65536 else {
            error = String(localized: "内容过长，请缩短后重试。"); return
        }
        let command = RemoteCommand(epoch: remote.epoch, kind: kind, payload: payload, interactionID: interaction?.id)
        var next = saved; next.pending = command
        if let withdrawal { next.queueWithdrawals[command.id] = withdrawal }
        if kind == "queueEdit", payload["action"] == .string("edit"),
           case .string(let itemID) = payload["itemId"], case .string(let text) = payload["text"] {
            next.queueEdits[itemID] = QueueEditDraft(itemID: itemID, text: text, command: command)
        }
        next.recentCommands.insert(command, at: 0)
        next.recentCommands = Array(next.recentCommands.prefix(20))
        let retained = Set(next.recentCommands.map(\.id))
        next.admissionRejections = next.admissionRejections.filter { retained.contains($0.key) }
        guard persist(next) else { return }
        await transmit(command)
    }
    func retry() async {
        guard let original = pending, !sending else { return }
        // Always reconcile with the server first. A missing old command must never be replayed.
        await refresh()
        guard pending?.id == original.id, canRetry else { return }
        await transmit(original)
    }
    private func transmit(_ command: RemoteCommand) async {
        guard !sending else { return }
        sending = true
        defer { sending = false }
        do {
            let receipt = try await service.send(command)
            receipts.removeAll { $0.id == receipt.id }; receipts.insert(receipt, at: 0)
            reconcile(command, receipt: receipt)
            error = nil
            reconcileWithdrawals()
            reconcileQueueEdits()
        } catch let failure as CommandAdmissionRejected {
            let receipt = CommandReceipt(["id": command.id, "status": "rejected"])!
            receipts.removeAll { $0.id == receipt.id }; receipts.insert(receipt, at: 0)
            reconcile(command, receipt: receipt, admissionCode: failure.code)
            reconcileWithdrawals()
            reconcileQueueEdits()
            report(failure)
        } catch { report(error) }
    }
    private func reconcile(_ command: RemoteCommand, receipt: CommandReceipt, admissionCode: String? = nil) {
        // Uncertain delivery remains explicitly unresolved; the app never silently sends it again.
        guard ["queued", "claimed", "accepted", "rejected", "expired"].contains(receipt.status) else { return }
        var next: SavedConversation
        do { next = try store.load(sessionID) }
        catch { storageError = String(localized: "无法读取草稿，请重新打开对话。"); return }
        guard next.pending?.id == command.id else { return }
        next.pending = nil
        if let admissionCode { next.admissionRejections[command.id] = admissionCode }
        if let interactionID = command.interactionID { next.interactionRequests[interactionID] = command.id }
        if command.kind == "prompt", ["queued", "claimed", "accepted"].contains(receipt.status),
           case .string(let text) = command.payload["text"], next.draft == text { next.draft = "" }
        if ["queued", "claimed", "accepted"].contains(receipt.status), case .array(let attachments) = command.payload["attachments"] {
            let hashes = Set(attachments.compactMap { value -> String? in
                guard case .object(let file) = value, case .string(let hash) = file["hash"] else { return nil }
                return hash
            })
            let sent = next.files.filter { hashes.contains($0.hash) }
            next.files.removeAll { hashes.contains($0.hash) }
            if persist(next) { for file in sent { store.removeUpload(file, sessionID: sessionID) } }
        } else { persist(next) }
    }
    func commandText(_ id: String) -> String? {
        guard let command = saved.recentCommands.first(where: { $0.id == id }),
              case .string(let text) = command.payload["text"] else { return nil }
        return text
    }
    func restoreDraft(_ receipt: CommandReceipt) {
        guard draft.isEmpty, files.isEmpty, pending == nil, !sending, !uploading, ["rejected", "expired"].contains(receipt.status),
              let command = saved.recentCommands.first(where: { $0.id == receipt.id }), let text = commandText(receipt.id) else { return }
        var next = saved; next.draft = text
        if case .array(let values) = command.payload["attachments"] { next.files = values.compactMap(OutgoingAttachment.init(payload:)) }
        persist(next)
    }
    func dismissPendingAfterReview() {
        guard !sending else { return }
        var next = saved; next.pending = nil; persist(next)
    }
    private func report(_ failure: Error) {
        if failure is CancellationError || (failure as? URLError)?.code == .cancelled { return }
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}

extension ConversationModel {
    func pauseUploads() {
        uploadGeneration = UUID(); uploadTask?.cancel(); uploadTask = nil; uploading = false; importingAttachments = false
    }
    func addFiles(_ urls: [URL]) {
        addAttachments(urls.map { url in { try await OutgoingAttachment.read(url) } })
    }
    func addPhotos(_ items: [PhotosPickerItem]) {
        addAttachments(items.map { item in {
            guard let photo = try await item.loadTransferable(type: PhotoAttachment.self) else {
                throw ClientError(message: String(localized: "无法读取这张照片，请重新选择。"))
            }
            return (photo.file, photo.data)
        } })
    }
    func addAttachments(_ selections: [() async throws -> (OutgoingAttachment, Data)]) {
        guard canEditFiles, uploadService != nil, !selections.isEmpty else { return }
        guard selections.count + files.count <= 4 else { uploadError = String(localized: "每条消息最多添加 4 个附件。"); return }
        startUploadWork(importing: true) { [self] in
            for selection in selections {
                try Task.checkCancellation()
                let (file, data) = try await selection()
                try Task.checkCancellation()
                if files.contains(where: { $0.hash == file.hash }) { continue }
                guard files.reduce(0, { $0 + $1.bytes }) + file.bytes <= OutgoingAttachment.capacity else {
                    throw ClientError(message: String(localized: "附件合计不能超过 32 MB。"))
                }
                try store.saveUpload(data, file: file, sessionID: sessionID)
                var next = saved; next.files.append(file)
                guard persist(next) else {
                    store.removeUpload(file, sessionID: sessionID)
                    throw ClientError(message: String(localized: "保存失败，内容尚未发送。请重试。"))
                }
            }
            importingAttachments = false
            try await uploadFiles()
        }
    }
    func retryUploads() {
        guard canEditFiles, !files.isEmpty, uploadService != nil else { return }
        startUploadWork { [self] in try await uploadFiles() }
    }
    func removeFile(_ file: OutgoingAttachment) {
        guard canEditFiles, files.contains(where: { $0.hash == file.hash }), let uploadService else { return }
        startUploadWork { [self] in
            // An ambiguous removal must be rechecked before this file can be sent again.
            var waiting = saved
            if let index = waiting.files.firstIndex(where: { $0.hash == file.hash }) { waiting.files[index].uploaded = false }
            guard persist(waiting) else { throw ClientError(message: String(localized: "保存失败，内容尚未发送。请重试。")) }
            try await uploadService.discard(file)
            try Task.checkCancellation()
            var next = saved; next.files.removeAll { $0.hash == file.hash }
            if persist(next) { store.removeUpload(file, sessionID: sessionID) }
        }
    }
    private func startUploadWork(importing: Bool = false, _ operation: @escaping @MainActor () async throws -> Void) {
        pauseUploads(); uploading = true; uploadError = nil; uploadProgress = 0
        importingAttachments = importing
        let generation = uploadGeneration
        uploadTask = Task { [weak self] in
            do { try Task.checkCancellation(); try await operation() }
            catch {
                guard let self, self.uploadGeneration == generation else { return }
                if !(error is CancellationError), (error as? URLError)?.code != .cancelled {
                    self.uploadError = ClientError.displayMessage(for: error)
                    if error is AuthenticationError { self.authFailure(error) }
                }
            }
            guard let self, self.uploadGeneration == generation else { return }
            self.uploading = false; self.importingAttachments = false; self.uploadTask = nil
        }
    }
    private func uploadFiles() async throws {
        guard let uploadService else { return }
        for file in files where !file.uploaded {
            try Task.checkCancellation()
            var status = try await uploadService.prepare(file)
            try Task.checkCancellation()
            uploadProgress = file.bytes == 0 ? 0 : Double(status.next) / Double(file.bytes)
            if !status.complete {
                let data = try store.loadUpload(file, sessionID: sessionID)
                repeat {
                    try Task.checkCancellation()
                    let end = min(file.bytes, status.next + 384000)
                    let chunk = data.subdata(in: status.next..<end)
                    let acknowledged = try await uploadService.chunk(file, offset: status.next, data: chunk)
                    try Task.checkCancellation()
                    guard acknowledged.next == end, acknowledged.complete == (end == file.bytes) else { throw ClientError.invalidResponse }
                    status = acknowledged; uploadProgress = file.bytes == 0 ? 1 : Double(status.next) / Double(file.bytes)
                } while !status.complete
            }
            var next = saved
            if let index = next.files.firstIndex(where: { $0.hash == file.hash }) { next.files[index].uploaded = true }
            guard persist(next) else { throw ClientError(message: String(localized: "保存失败，内容尚未发送。请重试。")) }
        }
    }
}
