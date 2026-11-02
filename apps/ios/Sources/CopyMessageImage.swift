import SwiftUI
import ImageIO
import UniformTypeIdentifiers
import CryptoKit

@MainActor protocol ImageClipboardDriver {
    func write(_ data: Data, type: String)
}
@MainActor struct SystemImageClipboardDriver: ImageClipboardDriver {
    var pasteboard: UIPasteboard = .general
    func write(_ data: Data, type: String) {
        pasteboard.setItems([[type: data]], options: [.localOnly: true])
    }
}

@MainActor final class CopyMessageImage: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var copied = false
    @Published var error: String?
    private let store: ConversationStore
    private let driver: ImageClipboardDriver
    private let read: (URL) async throws -> Data
    private var generation = UUID()
    init(store: ConversationStore, driver: ImageClipboardDriver? = nil, read: ((URL) async throws -> Data)? = nil) {
        self.store = store; self.driver = driver ?? SystemImageClipboardDriver()
        self.read = read ?? { url in
            let task = Task.detached(priority: .userInitiated) { try Data(contentsOf: url) }
            return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
        }
    }
    func clear() { generation = UUID(); copied = false; error = nil }
    func copy(_ file: ChatAttachment) async {
        guard !busy else { return }
        clear(); busy = true
        let attempt = generation, revision = store.attachmentCacheRevision
        defer { busy = false }
        do {
            try store.requireActive()
            guard InlineImagePreview.isImage(file), file.bytes <= InlineImagePreview.maximumFileBytes,
                  let url = try store.attachmentURL(file, verify: true) else { throw ClipboardError.unavailable }
            let data = try await read(url)
            guard attempt == generation, revision == store.attachmentCacheRevision, !Task.isCancelled else { return }
            try store.requireActive()
            guard data.count == file.bytes,
                  SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == file.hash,
                  let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
                  let type = CGImageSourceGetType(source) as String?, UTType(type)?.conforms(to: .image) == true else { throw ClipboardError.unavailable }
            driver.write(data, type: type)
            copied = true
        } catch {
            guard attempt == generation, !Task.isCancelled else { return }
            self.error = String(localized: "无法复制图片，请重试。")
        }
    }
}
private enum ClipboardError: Error { case unavailable }
