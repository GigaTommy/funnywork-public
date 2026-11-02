import SwiftUI

struct HistorySearchView: View {
    @StateObject private var model: HistorySearchModel
    @State private var query: String
    @Environment(\.dismiss) private var dismiss
    let service: any HistorySearchService
    let store: ConversationStore?
    let archived: Bool
    let authFailure: (Error) -> Void
    let openConversation: (HistorySearchResult) -> Void
    init(service: any HistorySearchService, store: ConversationStore?, query: String = "", archived: Bool, deviceID: String?,
         authFailure: @escaping (Error) -> Void, openConversation: @escaping (HistorySearchResult) -> Void) {
        self.service = service; self.store = store; self.archived = archived; self.authFailure = authFailure; self.openConversation = openConversation
        _query = State(initialValue: query)
        _model = StateObject(wrappedValue: HistorySearchModel(service: service, archived: archived, deviceID: deviceID, authFailure: authFailure))
    }
    var body: some View {
        NavigationStack {
            List {
                ForEach(model.results) { result in
                    NavigationLink {
                        HistorySearchResultView(result: result, service: service, store: store, authFailure: authFailure) {
                            openConversation(result)
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(result.conversation.title).font(.headline).lineLimit(1)
                            Text(verbatim: result.excerpt).lineLimit(4)
                            Label(result.conversation.device, systemImage: "desktopcomputer").font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 4)
                    }.listRowBackground(Theme.input).accessibilityIdentifier("history-search-" + result.id)
                }
                if model.loading { ProgressView("正在搜索…").listRowBackground(Color.clear) }
                if let error = model.error {
                    Text(error).font(.callout).listRowBackground(Color.clear)
                    if model.hasMore { Button("重试") { Task { await model.more() } }.disabled(model.loading) }
                } else if model.hasMore && !model.loading {
                    Button(model.results.isEmpty ? String(localized: "继续搜索") : String(localized: "更多结果")) { Task { await model.more() } }
                } else if model.results.isEmpty && !model.loading && !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    ContentUnavailableView("没有找到消息", systemImage: "magnifyingglass").listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden).background(Theme.surface)
            .searchable(text: $query, prompt: "搜索消息")
            .navigationTitle(archived ? String(localized: "搜索已归档消息") : String(localized: "搜索消息"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { dismiss() } }
            .task(id: query) { await model.search(query) }
        }.onDisappear { model.cancel() }
    }
}

private struct HistorySearchResultView: View {
    let result: HistorySearchResult
    let service: any HistorySearchService
    let store: ConversationStore?
    let authFailure: (Error) -> Void
    let openConversation: () -> Void
    @State private var item: HistoryItem?
    @State private var error: String?
    @State private var retry = 0
    var body: some View {
        Group {
            if let item {
                MessageSearchDetail(item: item, attachmentService: service.attachments(sessionID: result.conversation.id), store: store,
                                    sessionID: result.conversation.id, authFailure: authFailure)
            } else if let error {
                ContentUnavailableView {
                    Label("未能加载消息", systemImage: "exclamationmark.bubble")
                } description: { Text(error) } actions: { Button("重试") { retry += 1 } }
            } else { ProgressView() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.surface)
        .navigationTitle("消息").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            Button("打开对话", action: openConversation).buttonStyle(PrimaryActionStyle()).padding().background(Theme.surface)
                .accessibilityIdentifier("open-search-conversation")
        }
        .task(id: retry) {
            error = nil
            do {
                let value = try await service.message(result)
                try Task.checkCancellation()
                item = value
            } catch {
                if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
                self.error = ClientError.displayMessage(for: error)
                if error is AuthenticationError { authFailure(error) }
            }
        }
    }
}
