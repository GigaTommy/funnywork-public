import SwiftUI
import QuickLook

struct MessageImageSelection: Identifiable {
    var id: String { selected.hash }
    let selected: ChatAttachment
    let images: [ChatAttachment]
    init(selected: ChatAttachment, attachments: [ChatAttachment]) {
        self.selected = selected
        var seen = Set<String>()
        var result = attachments.filter { InlineImagePreview.isImage($0) && seen.insert($0.hash).inserted }
        if !result.contains(where: { $0.hash == selected.hash }) { result.append(selected) }
        images = result
    }
}

@MainActor final class GalleryImageModel: ObservableObject {
    @Published private(set) var image: UIImage?
    @Published private(set) var url: URL?
    @Published private(set) var loading = false
    @Published private(set) var error: String?
    private let service: AttachmentService
    private let store: ConversationStore
    private let sessionID: String
    private let authFailure: (Error) -> Void
    private var loader: AttachmentModel?
    private var generation = UUID()
    init(service: AttachmentService, store: ConversationStore, sessionID: String, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.store = store; self.sessionID = sessionID; self.authFailure = authFailure
    }
    func clear() { generation = UUID(); loader?.cancel(); loader = nil; image = nil; url = nil; loading = false; error = nil }
    func load(_ file: ChatAttachment) async {
        clear()
        guard InlineImagePreview.isImage(file), file.bytes <= InlineImagePreview.maximumFileBytes else {
            error = String(localized: "文件较大，请在桌面打开。"); return
        }
        let attempt = generation, revision = store.attachmentCacheRevision
        let download = AttachmentModel(service: service, store: store, sessionID: sessionID, authFailure: authFailure)
        loader = download; loading = true
        defer { if attempt == generation { loading = false; loader = nil } }
        await withTaskCancellationHandler {
            await download.openReference(file)
            await download.waitForDownload()
            guard attempt == generation, !Task.isCancelled else { return }
            guard let fileURL = download.preview else {
                error = download.error ?? String(localized: "文件尚未同步，请稍后重试。"); return
            }
            let decoded = await ImageThumbnail.load(fileURL, maxPixels: 2560)
            guard attempt == generation, revision == store.attachmentCacheRevision, !Task.isCancelled else { return }
            do { try store.requireActive() }
            catch { return }
            url = fileURL; image = decoded
            if decoded == nil { error = String(localized: "无法显示这张图片，可以尝试打开文件。") }
        } onCancel: { Task { @MainActor in download.cancel() } }
    }
}

struct MessageImageGallery: View {
    let selection: MessageImageSelection
    @StateObject private var model: GalleryImageModel
    @StateObject private var saving: SaveMessageImage
    @StateObject private var copying: CopyMessageImage
    @State private var index: Int
    @State private var retry = UUID()
    @State private var filePreview: URL?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    init(selection: MessageImageSelection, service: AttachmentService, store: ConversationStore, sessionID: String, authFailure: @escaping (Error) -> Void) {
        self.selection = selection
        _saving = StateObject(wrappedValue: SaveMessageImage(store: store))
        _copying = StateObject(wrappedValue: CopyMessageImage(store: store))
        _index = State(initialValue: selection.images.firstIndex(where: { $0.hash == selection.selected.hash }) ?? 0)
        _model = StateObject(wrappedValue: GalleryImageModel(service: service, store: store, sessionID: sessionID, authFailure: authFailure))
    }
    private var current: ChatAttachment { selection.images[index] }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ZStack {
                    Theme.surface
                    if let image = model.image {
                        GeometryReader { geometry in
                            ZoomableMessageImage(image: image, name: current.name) { direction in move(direction) }
                                .frame(width: geometry.size.width, height: geometry.size.height)
                                .id(current.hash)
                        }
                    } else if model.loading { ProgressView().accessibilityLabel("正在加载图片") }
                    else if let error = model.error {
                        VStack(spacing: 16) {
                            Image(systemName: "photo").font(.largeTitle).foregroundStyle(.secondary)
                            Text(error).multilineTextAlignment(.center).font(.callout)
                            Button("重试") { retry = UUID() }
                            if let url = model.url { Button("打开文件") { filePreview = url } }
                        }.padding(24)
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    .overlay(alignment: .bottom) {
                        if saving.busy || saving.saved || copying.copied {
                            Group {
                                if saving.busy { ProgressView().accessibilityLabel("正在保存图片") }
                                else if copying.copied { Label("已复制", systemImage: "checkmark").font(.caption).accessibilityIdentifier("gallery-copied") }
                                else { Label("已保存", systemImage: "checkmark").font(.caption).accessibilityIdentifier("gallery-saved") }
                            }.foregroundStyle(.secondary).padding(12)
                                .background(Theme.input, in: Capsule()).padding(.bottom, 16)
                        }
                    }
                VStack(spacing: 10) {
                    Text(current.name).font(.callout).lineLimit(2).multilineTextAlignment(.center)
                        .accessibilityIdentifier("gallery-filename")
                    if selection.images.count > 1 {
                        HStack {
                            Button { move(-1) } label: { Label("上一张", systemImage: "chevron.left") }
                                .disabled(index == 0).accessibilityIdentifier("gallery-previous")
                            Spacer()
                            Text("\(index + 1) / \(selection.images.count)").font(.caption).monospacedDigit()
                                .accessibilityIdentifier("gallery-position")
                            Spacer()
                            Button { move(1) } label: { Label("下一张", systemImage: "chevron.right") }
                                .disabled(index == selection.images.count - 1).accessibilityIdentifier("gallery-next")
                        }.labelStyle(.iconOnly).buttonStyle(GalleryActionStyle())
                    }
                }.padding(.horizontal, 20).padding(.bottom, 12)
            }.background(Theme.surface)
                .navigationTitle("图片").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button("完成") { dismiss() } }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        if model.image != nil {
                            Button {
                                let file = current
                                Task { await copying.copy(file) }
                            } label: { Label("复制图片", systemImage: copying.copied ? "checkmark" : "doc.on.doc").labelStyle(.iconOnly) }
                                .disabled(copying.busy).accessibilityIdentifier("gallery-copy")
                            Button {
                                let file = current
                                Task { await saving.save(file) }
                            } label: { Label("保存图片", systemImage: "square.and.arrow.down").labelStyle(.iconOnly) }
                                .disabled(saving.busy).accessibilityIdentifier("gallery-save")
                        }
                        if let url = model.url {
                            ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up").labelStyle(.iconOnly) }
                                .accessibilityIdentifier("gallery-share")
                        }
                    }
                }
                .task(id: GalleryLoadKey(hash: current.hash, active: phase == .active, retry: retry)) {
                    guard phase == .active else { model.clear(); return }
                    await model.load(current)
                }
                .quickLookPreview($filePreview)
                .task(id: copying.copied) {
                    guard copying.copied else { return }
                    do { try await Task.sleep(for: .seconds(2)) } catch { return }
                    copying.clear()
                }
        }.alert("无法复制图片", isPresented: Binding(get: { copying.error != nil }, set: { if !$0 { copying.clear() } })) {
            Button("完成", role: .cancel) { copying.clear() }
        } message: { Text(copying.error ?? "") }
        .alert("无法保存图片", isPresented: Binding(get: { saving.error != nil }, set: { if !$0 { saving.clear() } })) {
            if saving.needsSettings {
                Button("打开设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
            Button("完成", role: .cancel) { saving.clear() }
        } message: { Text(saving.error ?? "") }
        .onDisappear { model.clear(); saving.clear(); copying.clear() }
            .onChange(of: phase) { _, value in if value == .background { filePreview = nil; model.clear(); saving.clear(); copying.clear() } }
    }
    private func move(_ direction: Int) {
        let target = index + direction
        guard selection.images.indices.contains(target) else { return }
        model.clear(); saving.clear(); copying.clear(); index = target
    }
}
private struct GalleryLoadKey: Hashable { let hash: String; let active: Bool; let retry: UUID }
private struct GalleryActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.frame(minWidth: 44, minHeight: 44).foregroundStyle(.primary)
            .background(configuration.isPressed ? Theme.input : .clear, in: Circle())
    }
}
