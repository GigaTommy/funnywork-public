import Foundation
import Combine

struct HistorySearchCursor: Equatable {
    var session = ""
    var sequence = -1
    func follows(_ other: Self) -> Bool {
        session > other.session || (session == other.session && sequence > other.sequence)
    }
}
struct HistorySearchResult: Identifiable {
    let conversation: Conversation
    let sequence: Int
    let excerpt: String
    var id: String { "\(conversation.id):\(sequence)" }
}
struct HistorySearchPage {
    let items: [HistorySearchResult]
    let cursor: HistorySearchCursor
    let hasMore: Bool
    init(items: [HistorySearchResult], cursor: HistorySearchCursor, hasMore: Bool) {
        self.items = items; self.cursor = cursor; self.hasMore = hasMore
    }
    init(_ value: [String: Any]) throws {
        guard let rows = value["items"] as? [[String: Any]], rows.count <= 64,
              let session = value["afterSession"] as? String,
              session.isEmpty || CreationDevice.validID(session),
              let sequence = value["afterSeq"] as? Int, sequence >= -1,
              let more = value["hasMore"] as? Bool else { throw ClientError.invalidResponse }
        cursor = .init(session: session, sequence: sequence); hasMore = more
        items = try rows.map { row in
            guard let raw = row["conversation"] as? [String: Any], let conversation = Conversation(raw),
                  let seq = row["seq"] as? Int, seq >= 0, let excerpt = row["excerpt"] as? String,
                  excerpt.utf16.count <= 512 else { throw ClientError.invalidResponse }
            return .init(conversation: conversation, sequence: seq, excerpt: excerpt)
        }
    }
}
@MainActor protocol HistorySearchService {
    func page(query: String, cursor: HistorySearchCursor, archived: Bool, deviceID: String?) async throws -> HistorySearchPage
    func message(_ result: HistorySearchResult) async throws -> HistoryItem
    func attachments(sessionID: String) -> (any AttachmentService)?
}
extension HistorySearchService {
    func attachments(sessionID: String) -> (any AttachmentService)? { nil }
}
@MainActor struct HTTPHistorySearchService: HistorySearchService {
    let api: API
    static func path(query: String, cursor: HistorySearchCursor, archived: Bool, deviceID: String?) -> String {
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "query", value: query), URLQueryItem(name: "afterSession", value: cursor.session),
            URLQueryItem(name: "afterSeq", value: String(cursor.sequence)), URLQueryItem(name: "archived", value: String(archived)),
            URLQueryItem(name: "deviceID", value: deviceID ?? "")]
        return "sync/search?" + (components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B")
    }
    func page(query: String, cursor: HistorySearchCursor, archived: Bool, deviceID: String?) async throws -> HistorySearchPage {
        guard let value = try await api.request(Self.path(query: query, cursor: cursor, archived: archived, deviceID: deviceID)) as? [String: Any] else { throw ClientError.invalidResponse }
        return try HistorySearchPage(value)
    }
    func message(_ result: HistorySearchResult) async throws -> HistoryItem {
        let page = try await HTTPConversationService(api: api, sessionID: result.conversation.id).events(offset: result.sequence, limit: 1)
        guard let item = page.events.first, item.id == result.sequence else { throw ClientError.invalidResponse }
        return item
    }
    func attachments(sessionID: String) -> (any AttachmentService)? { HTTPAttachmentService(api: api, sessionID: sessionID) }
}
@MainActor final class HistorySearchModel: ObservableObject {
    @Published private(set) var results: [HistorySearchResult] = []
    @Published private(set) var loading = false
    @Published private(set) var hasMore = false
    @Published private(set) var error: String?
    private var query = ""
    private var cursor = HistorySearchCursor()
    private var generation = UUID()
    private let service: any HistorySearchService
    private let archived: Bool
    private let deviceID: String?
    private let authFailure: (Error) -> Void
    init(service: any HistorySearchService, archived: Bool = false, deviceID: String? = nil, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.archived = archived; self.deviceID = deviceID; self.authFailure = authFailure
    }
    func search(_ text: String, debounce: Bool = true) async {
        generation = UUID(); let current = generation
        query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        results = []; cursor = .init(); error = nil; loading = false
        hasMore = !query.isEmpty && query.utf16.count <= 160
        if query.utf16.count > 160 { error = String(localized: "搜索内容最多 160 个字符。") }
        guard hasMore else { return }
        if debounce {
            loading = true
            do { try await Task.sleep(for: .milliseconds(300)) }
            catch { if generation == current { loading = false }; return }
        }
        guard generation == current else { return }
        loading = false; await more()
    }
    func more() async {
        guard hasMore, !loading else { return }
        let current = generation
        loading = true; error = nil
        defer { if current == generation { loading = false } }
        var scanned = 0; let initialCount = results.count
        do {
            repeat {
                try Task.checkCancellation()
                let page = try await service.page(query: query, cursor: cursor, archived: archived, deviceID: deviceID)
                try Task.checkCancellation()
                guard current == generation else { return }
                guard page.cursor.follows(cursor) || (!page.hasMore && page.items.isEmpty && page.cursor == cursor) else { throw ClientError.invalidResponse }
                var previous = cursor
                for item in page.items {
                    let position = HistorySearchCursor(session: item.conversation.id, sequence: item.sequence)
                    guard position.follows(previous), !position.follows(page.cursor) else { throw ClientError.invalidResponse }
                    previous = position
                }
                results += page.items; cursor = page.cursor; hasMore = page.hasMore; scanned += 1
                // Keep sparse searches responsive; More continues from the exact scan cursor.
            } while hasMore && results.count - initialCount < 50 && scanned < 20
        } catch {
            guard current == generation else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
            self.error = String(localized: "搜索未完成，请重试。")
            if error is AuthenticationError { authFailure(error) }
        }
    }
    func cancel() { generation = UUID(); loading = false }
}
