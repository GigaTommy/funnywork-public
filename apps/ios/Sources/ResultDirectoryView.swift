import SwiftUI

struct ResultDirectoryView: View {
    let service: ResultService
    let attachments: AttachmentService
    let store: ConversationStore
    let sessionID: String
    let authFailure: (Error) -> Void
    let feedback: (String) -> Void
    @State private var results: [ResultRecord] = []
    @State private var error: String?
    @State private var loading = false
    @State private var selected: ResultVersion?
    @State private var showingFiles = false
    @State private var cached = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if cached { Text("已保存的结果目录").font(.caption).foregroundStyle(.secondary) }
                if let error { Text(error).font(.callout); Button("重试") { Task { await refresh() } } }
                if loading && results.isEmpty { ProgressView() }
                if !loading && results.isEmpty && error == nil { ContentUnavailableView("还没有结果文件", systemImage: "doc.richtext") }
                ForEach(results) { row in
                    Button { selected = row.manifest } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(verbatim: row.manifest.name)
                            Text(String(localized: "版本") + " \(row.manifest.version) · " + (row.transfers["original"]?.complete == true ? String(localized: "原文件已同步") : String(localized: "正在同步原文件")))
                                .font(.caption).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain).listRowBackground(Theme.input)
                        .accessibilityIdentifier("result-directory-" + row.manifest.id)
                }
                Button("查看全部附件") { showingFiles = true }.listRowBackground(Theme.input)
            }.scrollContentBackground(.hidden).background(Theme.surface).navigationTitle("结果文件")
                .toolbar { Button("完成") { dismiss() } }
                .task { if let rows = try? store.loadResultDirectory(sessionID) { results = rows; cached = true }; await refresh() }.refreshable { await refresh() }
                .sheet(item: $selected) { result in ResultFileView(result: result, service: attachments, resultService: service, store: store, sessionID: sessionID, authFailure: authFailure, feedback: feedback) }
                .sheet(isPresented: $showingFiles) { AttachmentView(service: attachments, store: store, sessionID: sessionID, authFailure: authFailure) }
        }
    }
    private func refresh() async {
        guard !loading else { return }; loading = true; defer { loading = false }
        do {
            let rows = try await service.directory(); try Task.checkCancellation()
            var latest: [String: ResultRecord] = [:]
            for row in rows { if latest[row.manifest.id].map({ $0.manifest.version < row.manifest.version }) ?? true { latest[row.manifest.id] = row } }
            results = latest.values.sorted { $0.createdAt > $1.createdAt }; error = nil; cached = false
            try store.saveResultDirectory(results, session: sessionID)
        } catch { if !(error is CancellationError) { self.error = ClientError.displayMessage(for: error); if error is AuthenticationError { authFailure(error) } } }
    }
}
