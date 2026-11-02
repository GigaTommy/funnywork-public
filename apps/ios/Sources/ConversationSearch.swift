import Foundation
import Combine

@MainActor final class ConversationSearch: ObservableObject {
    @Published private(set) var results: [HistoryItem] = []
    @Published private(set) var loading = false
    @Published private(set) var hasMore = false
    @Published private(set) var error: String?
    private var query = ""
    private var generation = UUID()
    private var cursor = 0
    private var end: Int?
    private let service: ConversationService
    private let authFailure: (Error) -> Void
    init(service: ConversationService, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.authFailure = authFailure
    }
    func search(_ text: String, debounce: Bool = true) async {
        generation = UUID(); let current = generation
        query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        results = []; cursor = 0; end = nil; error = nil; loading = false
        hasMore = !query.isEmpty
        guard hasMore else { return }
        if debounce {
            loading = true
            do { try await Task.sleep(for: .milliseconds(300)) }
            catch { if generation == current { loading = false }; return }
        }
        guard generation == current else { return }
        loading = false
        await more()
    }
    func more() async {
        guard hasMore, !loading else { return }
        let current = generation
        loading = true; error = nil
        defer { if generation == current { loading = false } }
        let initialCount = results.count
        do {
            repeat {
                try Task.checkCancellation()
                let page = try await service.events(offset: cursor, limit: min(100, end.map { $0 - cursor } ?? 100))
                try Task.checkCancellation()
                guard generation == current else { return }
                if end == nil { end = page.durable }
                guard let end, page.next >= cursor, page.next <= end,
                      page.next > cursor || cursor == end else { throw ClientError.invalidResponse }
                results += page.events.filter {
                    $0.searchableText.localizedStandardContains(query) || $0.attachments.contains(where: { $0.name.localizedStandardContains(query) })
                }
                cursor = page.next; hasMore = cursor < end
                // Large result sets are loaded in batches without omitting the remaining history.
            } while hasMore && results.count - initialCount < 50
        } catch {
            guard generation == current else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
            self.error = String(localized: "搜索未完成，请重试。")
            if error is AuthenticationError { authFailure(error) }
        }
    }
    func cancel() { generation = UUID(); loading = false }
}
