import Foundation
import Combine

@MainActor protocol ConversationDirectoryService {
    func page(after: String) async throws -> [Conversation]
    func rename(id: String, title: String, revision: Int64) async throws -> Int64
    func archive(id: String, archived: Bool, revision: Int64) async throws -> Int64
}
extension ConversationDirectoryService {
    func rename(id: String, title: String, revision: Int64) async throws -> Int64 { throw ClientError.invalidResponse }
    func archive(id: String, archived: Bool, revision: Int64) async throws -> Int64 { throw ClientError.invalidResponse }
}
@MainActor struct HTTPConversationDirectory: ConversationDirectoryService {
    let api: API
    func archive(id: String, archived: Bool, revision: Int64) async throws -> Int64 {
        guard let row = try await api.request("sync/sessions/\(id)/archive", body: ["archived": archived, "revision": revision]) as? [String: Any],
              row["archived"] as? Bool == archived, let next = row["revision"] as? NSNumber,
              revision < Int64.max, next.int64Value == revision + 1 else { throw ClientError.invalidResponse }
        return next.int64Value
    }
    func rename(id: String, title: String, revision: Int64) async throws -> Int64 {
        guard let row = try await api.request("sync/sessions/\(id)/title", body: ["title": title, "revision": revision]) as? [String: Any],
              row["title"] as? String == title, let next = row["revision"] as? NSNumber,
              revision < Int64.max, next.int64Value == revision + 1 else { throw ClientError.invalidResponse }
        return next.int64Value
    }
    func page(after: String) async throws -> [Conversation] {
        guard let rows = try await api.request("sync/sessions?after=\(after)&limit=100") as? [[String: Any]] else { throw ClientError.invalidResponse }
        let page = rows.compactMap(Conversation.init)
        guard page.count == rows.count else { throw ClientError.invalidResponse }
        return page
    }
}

@MainActor final class ConversationDirectory: ObservableObject {
    @Published private(set) var rows: [Conversation] = []
    @Published private(set) var loading = false
    @Published private(set) var renaming = false
    @Published private(set) var archiving = false
    @Published var showingArchived = false
    var modifying: Bool { renaming || archiving }
    @Published var error: String?
    @Published var search = ""
    @Published var deviceID: String?
    @Published private(set) var showingCache = false
    @Published private(set) var cachedAt: Date?
    @Published private(set) var pinnedIDs: Set<String> = []
    private var readSubscription: AnyCancellable?
    private let store: ConversationStore?
    private let service: ConversationDirectoryService
    private let authFailure: (Error) -> Void
    init(service: ConversationDirectoryService, store: ConversationStore? = nil, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.store = store; self.authFailure = authFailure
        readSubscription = store?.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        do { pinnedIDs = try store?.loadPinnedConversations() ?? [] }
        catch { self.error = ClientError.displayMessage(for: error) }
        if let cache = try? store?.loadDirectory() {
            rows = cache.rows; cachedAt = cache.date; showingCache = true
        }
    }
    func hasUnread(_ row: Conversation) -> Bool { store?.hasUnread(row) ?? false }
    var devices: [(id: String, name: String)] {
        var seen = Set<String>()
        return rows.filter { seen.insert($0.deviceID).inserted }.map { ($0.deviceID, remoteHostName($0.device, deviceID: $0.deviceID)) }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    var visible: [Conversation] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return rows.filter {
            ($0.archived == true) == showingArchived && (deviceID == nil || $0.deviceID == deviceID)
                && (query.isEmpty || $0.title.localizedStandardContains(query) || $0.device.localizedStandardContains(query))
        }.sorted {
            if $0.updatedAt != $1.updatedAt { return ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
            return $0.id < $1.id
        }
    }
    var filtered: Bool { deviceID != nil || !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var groups: [ConversationDateGroup] { grouped(at: Date()) }
    func grouped(at now: Date) -> [ConversationDateGroup] {
        let filtered = visible
        if showingArchived { return ConversationDateGroup.group(filtered, now: now) }
        func live(_ row: Conversation) -> ConversationTaskState? {
            guard !showingCache, row.online, let expiry = row.taskStateExpiresAt, expiry > now else { return nil }
            return row.taskState
        }
        let attention = filtered.filter { live($0) == .approval || live($0) == .question }
        let active = filtered.filter { live($0) == .waiting || live($0) == .running }
        let liveIDs = Set((attention + active).map(\.id))
        let unread = filtered.filter { !liveIDs.contains($0.id) && hasUnread($0) }
        let priorityIDs = liveIDs.union(unread.map(\.id))
        let pinned = filtered.filter { !priorityIDs.contains($0.id) && pinnedIDs.contains($0.id) }
        let other = filtered.filter { !priorityIDs.contains($0.id) && !pinnedIDs.contains($0.id) }
        return [(ConversationDateGroup.Period.attention, attention), (.active, active), (.unread, unread), (.pinned, pinned)]
            .filter { !$0.1.isEmpty }.map { ConversationDateGroup(id: $0.0, rows: $0.1) }
            + ConversationDateGroup.group(other, now: now)
    }
    func togglePin(_ id: String) {
        guard rows.contains(where: { $0.id == id }) else { return }
        var updated = pinnedIDs
        if !updated.insert(id).inserted { updated.remove(id) }
        do {
            try store?.savePinnedConversations(updated)
            pinnedIDs = updated
        } catch {
            self.error = ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
        }
    }
    func clearFilters() { search = ""; deviceID = nil }
    func rename(_ id: String, to proposed: String, expectedRevision: Int64? = nil) async -> Bool {
        guard !loading, !modifying, let index = rows.firstIndex(where: { $0.id == id }),
              let revision = rows[index].titleRevision else { return false }
        if let expectedRevision, expectedRevision != revision {
            error = String(localized: "状态已更新，请刷新后重试。")
            return false
        }
        let title = proposed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.utf16.count <= 160,
              title.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else { return false }
        renaming = true; error = nil
        defer { renaming = false }
        do {
            try store?.requireActive()
            let next = try await service.rename(id: id, title: title, revision: revision)
            try Task.checkCancellation(); try store?.requireActive()
            rows[index].title = title; rows[index].titleRevision = next
            try? store?.saveDirectory(rows)
            return true
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled { return false }
            self.error = ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
            return false
        }
    }
    func setArchived(_ row: Conversation, to archived: Bool) async -> Bool {
        guard !loading, !modifying, let index = rows.firstIndex(where: { $0.id == row.id }),
              let revision = row.archiveRevision else { return false }
        guard rows[index].archiveRevision == revision else {
            error = String(localized: "状态已更新，请刷新后重试。")
            return false
        }
        archiving = true; error = nil
        defer { archiving = false }
        do {
            try store?.requireActive()
            let next = try await service.archive(id: row.id, archived: archived, revision: revision)
            try Task.checkCancellation(); try store?.requireActive()
            rows[index].archived = archived; rows[index].archiveRevision = next
            try? store?.saveDirectory(rows)
            return true
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled { return false }
            self.error = ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
            return false
        }
    }
    func refresh() async {
        guard !loading, !modifying else { return }
        loading = true; error = nil
        defer { loading = false }
        var collected: [String: Conversation] = [:]
        var cursor = ""
        do {
            try store?.requireActive()
            while true {
                try Task.checkCancellation()
                let page = try await service.page(after: cursor)
                try Task.checkCancellation()
                var previous = cursor
                for row in page {
                    guard row.id > previous else { throw ClientError.invalidResponse }
                    previous = row.id
                    collected[row.id] = row
                }
                if page.count < 100 { break }
                guard let next = page.last?.id, next > cursor else { throw ClientError.invalidResponse }
                cursor = next
            }
            try store?.requireActive()
            rows = Array(collected.values)
            showingCache = false; cachedAt = Date()
            try? store?.saveDirectory(rows)
            if let selected = deviceID, !rows.contains(where: { $0.deviceID == selected }) { deviceID = nil }
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
            showingCache = !rows.isEmpty
            self.error = ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
        }
    }
}

struct ConversationDateGroup: Identifiable {
    enum Period: Int, CaseIterable { case attention, active, unread, pinned, today, yesterday, week, month, earlier }
    let id: Period
    let rows: [Conversation]
    var title: String {
        switch id {
        case .attention: return String(localized: "需要处理")
        case .active: return String(localized: "进行中")
        case .unread: return String(localized: "有更新")
        case .pinned: return String(localized: "已置顶")
        case .today: return String(localized: "今天")
        case .yesterday: return String(localized: "昨天")
        case .week: return String(localized: "过去 7 天")
        case .month: return String(localized: "过去 30 天")
        case .earlier: return String(localized: "更早")
        }
    }
    /// Calendar boundaries keep groups stable across daylight saving changes.
    static func group(_ rows: [Conversation], now: Date = Date(), calendar: Calendar = .current) -> [Self] {
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let week = calendar.date(byAdding: .day, value: -7, to: today)!
        let month = calendar.date(byAdding: .day, value: -30, to: today)!
        let groups = Dictionary(grouping: rows) { row -> Period in
            guard let date = row.updatedAt else { return .earlier }
            if date >= today { return .today }
            if date >= yesterday { return .yesterday }
            if date >= week { return .week }
            if date >= month { return .month }
            return .earlier
        }
        return Period.allCases.compactMap { period in
            groups[period].map { Self(id: period, rows: $0) }
        }
    }
}
