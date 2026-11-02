import SwiftUI

struct ConversationSearchView: View {
    @StateObject private var model: ConversationSearch
    @State private var query = ""
    private let attachmentService: AttachmentService?
    private let store: ConversationStore?
    private let sessionID: String
    private let authFailure: (Error) -> Void
    private let openMessage: ((Int) -> Void)?
    @Environment(\.dismiss) private var dismiss
    init(service: ConversationService, attachmentService: AttachmentService? = nil, store: ConversationStore? = nil,
         sessionID: String = "", authFailure: @escaping (Error) -> Void, openMessage: ((Int) -> Void)? = nil) {
        self.openMessage = openMessage
        self.attachmentService = attachmentService; self.store = store; self.sessionID = sessionID; self.authFailure = authFailure
        _model = StateObject(wrappedValue: ConversationSearch(service: service, authFailure: authFailure))
    }
    var body: some View {
        NavigationStack {
            List {
                ForEach(model.results) { item in
                    NavigationLink {
                        MessageSearchDetail(item: item, attachmentService: attachmentService, store: store, sessionID: sessionID, authFailure: authFailure)
                            .safeAreaInset(edge: .bottom) {
                                if let openMessage {
                                    Button("查看上下文") { openMessage(item.id); dismiss() }
                                        .buttonStyle(PrimaryActionStyle()).padding().background(Theme.surface)
                                        .accessibilityIdentifier("open-message-context")
                                }
                            }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.displayLabel).font(.caption).foregroundStyle(.secondary)
                            if !item.searchableText.isEmpty { Text(verbatim: item.searchableText).lineLimit(4) }
                            if item.interrupted { InterruptedReplyNotice() }
                            ForEach(item.attachments) { file in
                                Label(file.name, systemImage: "doc").font(.callout).lineLimit(1)
                            }
                        }.padding(.vertical, 4)
                    }.listRowBackground(Theme.input).accessibilityIdentifier("search-result-\(item.id)")
                }
                if model.loading { ProgressView("正在搜索…").listRowBackground(Color.clear) }
                if let error = model.error {
                    Text(error).font(.callout).listRowBackground(Color.clear)
                    Button("重试") { Task { await model.more() } }.disabled(model.loading)
                } else if model.hasMore && !model.loading {
                    Button("更多结果") { Task { await model.more() } }
                } else if model.results.isEmpty && !model.loading && !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    ContentUnavailableView("没有找到消息", systemImage: "magnifyingglass")
                        .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden).background(Theme.surface)
            .searchable(text: $query, prompt: "搜索消息")
            .navigationTitle("搜索消息").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { dismiss() } }
            .task(id: query) { await model.search(query) }

        }.onDisappear { model.cancel() }
    }
}


struct MessageSearchDetail: View {
    let item: HistoryItem
    let attachmentService: (any AttachmentService)?
    let store: ConversationStore?
    let sessionID: String
    let authFailure: (Error) -> Void
    @State private var selectedAttachment: ChatAttachment?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(item.displayLabel).font(.caption).foregroundStyle(.secondary)
                if let activity = item.activity { HistoryActivityView(item: item, activity: activity) }
                else if !item.text.isEmpty {
                    if item.isUser { Text(verbatim: item.text).textSelection(.enabled) }
                    else { MessageContent(text: item.text) }
                }
                if item.interrupted { InterruptedReplyNotice() }
                ForEach(item.attachments) { file in
                    if let store {
                        MessageAttachmentCard(file: file, service: attachmentService, store: store, sessionID: sessionID,
                            authFailure: authFailure) { selectedAttachment = file }
                            .accessibilityIdentifier("search-attachment-" + file.hash)
                    } else { Label(file.name, systemImage: "doc") }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding()
        }.background(Theme.surface)
            .navigationTitle("消息").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !item.text.isEmpty {
                    Button { UIPasteboard.general.string = item.text } label: { Label("复制", systemImage: "doc.on.doc").labelStyle(.iconOnly) }
                    ShareLink(item: item.text) { Label("分享", systemImage: "square.and.arrow.up").labelStyle(.iconOnly) }
                }
            }
        .sheet(item: $selectedAttachment) { file in
            if let attachmentService, let store {
                AttachmentView(service: attachmentService, store: store, sessionID: sessionID, reference: file, authFailure: authFailure)
            }
        }
    }
}
