import SwiftUI
import ImageIO
import UniformTypeIdentifiers

actor ImagePreviewSlots {
    static let shared = ImagePreviewSlots()
    private var active = 0
    private var waiting: [(UUID, CheckedContinuation<Void, Error>)] = []
    func acquire() async throws {
        try Task.checkCancellation()
        if active < 2 { active += 1; return }
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { waiting.append((id, $0)) }
        } onCancel: { Task { await self.cancel(id) } }
    }
    private func cancel(_ id: UUID) {
        guard let index = waiting.firstIndex(where: { $0.0 == id }) else { return }
        waiting.remove(at: index).1.resume(throwing: CancellationError())
    }
    func release() {
        if waiting.isEmpty { active -= 1 }
        else { waiting.removeFirst().1.resume() }
    }
}

enum ImageThumbnail {
    static func load(_ url: URL, maxPixels: Int = 720) async -> UIImage? {
        let task = Task.detached(priority: .utility) { () -> UIImage? in
            guard !Task.isCancelled,
                  let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: maxPixels
                  ] as CFDictionary), !Task.isCancelled else { return nil }
            return UIImage(cgImage: image)
        }
        return await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }
}

@MainActor final class InlineImagePreview: ObservableObject {
    @Published private(set) var image: UIImage?
    @Published private(set) var loading = false
    private var generation = UUID()
    private var download: AttachmentModel?
    static let automaticLimit = 8 * 1024 * 1024
    static let maximumFileBytes = 32 * 1024 * 1024
    nonisolated static func isImage(_ file: ChatAttachment) -> Bool {
        UTType(filenameExtension: (file.name as NSString).pathExtension)?.conforms(to: .image) == true
    }
    func load(_ file: ChatAttachment, service: AttachmentService, store: ConversationStore, sessionID: String,
              authFailure: @escaping (Error) -> Void = { _ in }) async {
        cancel()
        guard Self.isImage(file), file.bytes <= Self.maximumFileBytes else { return }
        guard file.bytes <= Self.automaticLimit || (try? store.attachmentURL(file, verify: true)) != nil else { return }
        loading = true
        let current = generation
        let loader = AttachmentModel(service: service, store: store, sessionID: sessionID, authFailure: authFailure)
        download = loader
        defer { if generation == current { loading = false; download = nil } }
        await withTaskCancellationHandler {
            do {
                try await ImagePreviewSlots.shared.acquire()
                defer { Task { await ImagePreviewSlots.shared.release() } }
                try Task.checkCancellation()
                guard generation == current else { return }
                await loader.openReference(file)
                await loader.waitForDownload()
                try Task.checkCancellation()
                guard generation == current, let url = loader.preview else { return }
                let thumbnail = await ImageThumbnail.load(url)
                try Task.checkCancellation()
                try store.requireActive()
                if generation == current { image = thumbnail }
            } catch { /* Keep the file card available for explicit preview and retry. */ }
        } onCancel: { Task { @MainActor in loader.cancel() } }
    }
    func cancel() {
        generation = UUID(); download?.cancel(); download = nil; image = nil; loading = false
    }
}

struct MessageAttachmentCard: View {
    let file: ChatAttachment
    let service: AttachmentService?
    let store: ConversationStore
    let sessionID: String
    let authFailure: (Error) -> Void
    let open: () -> Void
    @StateObject private var preview = InlineImagePreview()
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 8) {
                if InlineImagePreview.isImage(file) {
                    ZStack {
                        Theme.surface
                        if let image = preview.image {
                            Image(uiImage: image).resizable().scaledToFit()
                                .accessibilityIdentifier("message-image-" + file.hash)
                        } else { Image(systemName: "photo").font(.largeTitle).foregroundStyle(.secondary) }
                    }.frame(width: 240, height: 180).clipShape(RoundedRectangle(cornerRadius: 12))
                }
                HStack(spacing: 12) {
                    if preview.loading { ProgressView().controlSize(.small) }
                    else { Image(systemName: InlineImagePreview.isImage(file) ? "photo" : "doc") }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(file.name).lineLimit(2)
                        Text(file.sizeLabel).font(.caption).foregroundStyle(.secondary)
                    }
                    Image(systemName: "chevron.right").font(.caption)
                }
            }.padding(12).background(Theme.input, in: RoundedRectangle(cornerRadius: 16))
        }.buttonStyle(.plain).disabled(service == nil)
            .task(id: phase) {
                guard phase == .active, let service else { preview.cancel(); return }
                await preview.load(file, service: service, store: store, sessionID: sessionID, authFailure: authFailure)
            }
            .onDisappear { preview.cancel() }
    }
}
