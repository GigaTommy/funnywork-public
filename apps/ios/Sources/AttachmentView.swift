import SwiftUI
import QuickLook

struct AttachmentView: View {
    @StateObject private var model: AttachmentModel
    private let reference: ChatAttachment?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    init(service: AttachmentService, store: ConversationStore, sessionID: String, reference: ChatAttachment? = nil, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.reference = reference
        _model = StateObject(wrappedValue: AttachmentModel(service: service, store: store, sessionID: sessionID, authFailure: authFailure))
    }
    var body: some View {
        NavigationStack {
            List {
                if model.cached {
                    Label("已保存的文件列表", systemImage: "clock").font(.caption).foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                }
                if let error = model.error {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(error).font(.callout)
                        Button("重试") { Task { await model.retry() } }.disabled(model.loading || model.downloading != nil)
                    }.listRowBackground(Theme.input)
                }
                if model.items.isEmpty && !model.loading && model.error == nil {
                    ContentUnavailableView("还没有文件", systemImage: "doc")
                        .listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
                ForEach(model.items) { item in
                    VStack(alignment: .leading, spacing: 10) {
                        Button { model.open(item) } label: {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "doc").font(.title2).accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(item.name).font(.body).lineLimit(3)
                                    HStack {
                                        Text(item.sizeLabel)
                                        if model.files[item.id] != nil { Text("已下载") }
                                        else if !item.complete { Text("同步中") }
                                        else if item.bytes > item.effectiveLimit { Text("请在桌面打开") }
                                    }.font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: model.files[item.id] == nil ? "arrow.down.circle" : "eye")
                            }.padding(.vertical, 6).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                            .disabled(model.downloading != nil || (!item.complete && model.files[item.id] == nil) || item.bytes > item.effectiveLimit)
                        if model.downloading == item.id {
                            HStack {
                                ProgressView(value: model.progress).tint(Theme.accent)
                                Button("取消") { model.cancel() }
                            }.accessibilityElement(children: .contain)
                        }
                        if let url = model.files[item.id] {
                            ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }.font(.caption)
                        }
                    }.listRowBackground(Theme.input)
                }
                if !model.after.isEmpty {
                    Button("加载更多") { Task { await model.refresh(more: true) } }.disabled(model.loading)
                        .listRowBackground(Theme.input)
                }
                if model.loading { ProgressView().listRowBackground(Color.clear) }
            }
            .scrollContentBackground(.hidden).background(Theme.surface)
            .navigationTitle("文件").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { model.cancel(); dismiss() } }
            .refreshable { if let reference { await model.openReference(reference) } else { await model.refresh() } }
            .task { if let reference { await model.openReference(reference) } else { await model.refresh() } }
            .quickLookPreview($model.preview)
        }
        .onDisappear { model.cancel() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { model.cancel() } }
    }
}
