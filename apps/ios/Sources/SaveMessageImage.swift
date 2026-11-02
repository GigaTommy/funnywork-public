import Photos
import CryptoKit
import SwiftUI

@MainActor protocol ImageSavingDriver {
    func authorize() async -> Bool
    func save(_ data: Data, filename: String) async throws
}

@MainActor struct SystemImageSavingDriver: ImageSavingDriver {
    func authorize() async -> Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if status == .authorized { return true }
        guard status == .notDetermined else { return false }
        return await PHPhotoLibrary.requestAuthorization(for: .addOnly) == .authorized
    }
    func save(_ data: Data, filename: String) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = filename
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: options)
        }
    }
}

@MainActor final class SaveMessageImage: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var saved = false
    @Published private(set) var needsSettings = false
    @Published var error: String?
    private let driver: ImageSavingDriver
    private let store: ConversationStore
    private var generation = UUID()
    init(store: ConversationStore, driver: ImageSavingDriver? = nil) {
        self.store = store
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--ui-test-image-save") {
            self.driver = driver ?? FixtureImageSavingDriver(); return
        }
        #endif
        self.driver = driver ?? SystemImageSavingDriver()
    }
    // An already submitted Photos transaction cannot be withdrawn. Keep the lock until it finishes.
    func clear() { generation = UUID(); saved = false; needsSettings = false; error = nil }
    func save(_ file: ChatAttachment) async {
        guard !busy else { return }
        clear(); busy = true
        let attempt = generation, revision = store.attachmentCacheRevision
        defer { busy = false }
        do {
            try store.requireActive()
            guard InlineImagePreview.isImage(file), file.bytes <= InlineImagePreview.maximumFileBytes,
                  let url = try store.attachmentURL(file, verify: true) else { throw ImageSaveError.unavailable }
            let authorized = await driver.authorize()
            guard attempt == generation, !Task.isCancelled else { return }
            guard authorized else {
                needsSettings = true; error = String(localized: "请在设置中允许添加照片。"); return
            }
            let read = Task.detached(priority: .userInitiated) {
                let data = try Data(contentsOf: url)
                guard data.count == file.bytes, SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == file.hash else { throw ImageSaveError.unavailable }
                return data
            }
            let data = try await withTaskCancellationHandler { try await read.value } onCancel: { read.cancel() }
            guard attempt == generation, revision == store.attachmentCacheRevision, !Task.isCancelled else { return }
            try store.requireActive()
            try await driver.save(data, filename: file.name)
            guard attempt == generation, !Task.isCancelled else { return }
            saved = true
        } catch {
            guard attempt == generation, !Task.isCancelled else { return }
            self.error = String(localized: "无法保存图片，请重试。")
        }
    }
}
private enum ImageSaveError: Error { case unavailable }

#if DEBUG
@MainActor private struct FixtureImageSavingDriver: ImageSavingDriver {
    func authorize() async -> Bool { !ProcessInfo.processInfo.arguments.contains("--ui-test-image-save-denied") }
    func save(_ data: Data, filename: String) async throws {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-image-save-failed") { throw ImageSaveError.unavailable }
    }
}
#endif
