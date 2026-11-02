import Foundation
import CryptoKit
import Combine

struct ChatAttachment: Identifiable, Codable, Equatable {
    var id: String { hash }
    let hash: String
    let name: String
    let bytes: Int
    let complete: Bool
    /// Absolute disk-download ceiling; server capacity can be lower.
    static let maxDownloadBytes = 256 * 1024 * 1024
    let downloadLimit: Int?
    var effectiveLimit: Int { min(downloadLimit ?? 32 * 1024 * 1024, Self.maxDownloadBytes) }
    static func validHash(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy { "0123456789abcdef".contains($0) }
    }
    init(_ row: [String: Any]) throws {
        guard let hash = row["hash"] as? String, Self.validHash(hash),
              let metadata = row["metadata"] as? [String: Any],
              let attachment = metadata["attachment"] as? [String: Any],
              attachment["attachmentId"] as? String == "sha256:" + hash,
              let bytes = attachment["bytes"] as? Int, bytes >= 0,
              let complete = row["complete"] as? Bool else { throw ClientError.invalidResponse }
        self.hash = hash; self.bytes = bytes; self.complete = complete
        downloadLimit = (row["maxBytes"] as? Int).map { min(max(1, $0), Self.maxDownloadBytes) }
        name = Self.safeName(attachment["name"] as? String ?? String(localized: "未命名文件"))
    }
    static func safeName(_ input: String) -> String {
        let allowed = input.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) && !"/\\:".unicodeScalars.contains($0) }
        let value = String(String.UnicodeScalarView(allowed)).trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        return value.isEmpty ? String(localized: "未命名文件") : String(value.suffix(160)).trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
    }
    init(reference: [String: Any]) throws {
        guard let id = reference["attachmentId"] as? String, id.hasPrefix("sha256:") else { throw ClientError.invalidResponse }
        try self.init(["hash": String(id.dropFirst(7)), "complete": false, "metadata": ["attachment": reference]])
    }
    var sizeLabel: String { ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file) }
}
struct AttachmentPage: Codable {
    let items: [ChatAttachment]
    let after: String
    init(_ row: [String: Any], after cursor: String) throws {
        guard let rows = row["items"] as? [[String: Any]], rows.count <= 100,
              let next = row["after"] as? String else { throw ClientError.invalidResponse }
        let rowCapacity = row["maxBytes"]
        items = try rows.map { row in var value = row; if let maxBytes = row["maxBytes"] ?? rowCapacity { value["maxBytes"] = maxBytes }; return try ChatAttachment(value) }
        var previous = cursor
        for item in items {
            guard item.hash > previous else { throw ClientError.invalidResponse }
            previous = item.hash
        }
        guard next.isEmpty || (next == items.last?.hash && next > cursor) else { throw ClientError.invalidResponse }
        after = next
    }
}
struct AttachmentChunk {
    let data: Data
    let next: Int
    let complete: Bool
    init(_ row: [String: Any], offset: Int, expected: Int) throws {
        guard let encoded = row["data"] as? String, encoded.utf8.count <= 512000,
              let data = Data(base64Encoded: encoded), data.base64EncodedString() == encoded,
              let next = row["nextOffset"] as? Int, let complete = row["complete"] as? Bool,
              offset <= expected, data.count <= expected - offset,
              next == offset + data.count, complete == (next == expected),
              !data.isEmpty || (expected == 0 && complete) else { throw ClientError.invalidResponse }
        self.data = data; self.next = next; self.complete = complete
    }
}
@MainActor protocol AttachmentService {
    func page(after: String) async throws -> AttachmentPage
    func chunk(_ attachment: ChatAttachment, offset: Int) async throws -> AttachmentChunk
    func lookup(hash: String) async throws -> ChatAttachment?
}
extension AttachmentService {
    func lookup(hash: String) async throws -> ChatAttachment? {
        var cursor = ""
        repeat {
            try Task.checkCancellation()
            let result = try await page(after: cursor)
            if let item = result.items.first(where: { $0.hash == hash }) { return item }
            cursor = result.after
        } while !cursor.isEmpty
        return nil
    }
}
@MainActor struct HTTPAttachmentService: AttachmentService {
    let api: API
    let sessionID: String
    func lookup(hash: String) async throws -> ChatAttachment? {
        guard ChatAttachment.validHash(hash), let row = try await api.request("sync/sessions/\(sessionID)/attachments?hash=\(hash)") as? [String: Any] else { throw ClientError.invalidResponse }
        let page = try AttachmentPage(row, after: "")
        guard page.items.count <= 1, page.items.allSatisfy({ $0.hash == hash }) else { throw ClientError.invalidResponse }
        return page.items.first
    }
    func page(after: String) async throws -> AttachmentPage {
        guard let row = try await api.request("sync/sessions/\(sessionID)/attachments?after=\(after)") as? [String: Any] else { throw ClientError.invalidResponse }
        return try AttachmentPage(row, after: after)
    }
    func chunk(_ attachment: ChatAttachment, offset: Int) async throws -> AttachmentChunk {
        guard let row = try await api.request("sync/sessions/\(sessionID)/attachments/\(attachment.hash)?offset=\(offset)") as? [String: Any] else { throw ClientError.invalidResponse }
        return try AttachmentChunk(row, offset: offset, expected: attachment.bytes)
    }
}

@MainActor final class AttachmentModel: ObservableObject {
    @Published private(set) var items: [ChatAttachment] = []
    @Published private(set) var after = ""
    @Published private(set) var loading = false
    @Published private(set) var downloading: String?
    @Published private(set) var progress = 0.0
    @Published private(set) var cached = false
    @Published private(set) var files: [String: URL] = [:]
    @Published var preview: URL?
    @Published var error: String?
    @Published private(set) var paused: ChatAttachment?
    private var activeDownload: ChatAttachment?
    private let service: AttachmentService
    private let store: ConversationStore
    private let sessionID: String
    private let authFailure: (Error) -> Void
    private var failedPageWasMore = false
    private var failedDownload: ChatAttachment?
    private var failedReference: ChatAttachment?
    private var downloadTask: Task<Void, Never>?
    private var referenceGeneration = UUID()
    init(service: AttachmentService, store: ConversationStore, sessionID: String, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.store = store; self.sessionID = sessionID; self.authFailure = authFailure
        if let page = try? store.loadAttachments(sessionID) { items = page.items; after = page.after; cached = true }
        refreshLocalFiles()
    }
    private func refreshLocalFiles() {
        files = [:]
        for item in items {
            if let url = try? store.attachmentURL(item) { files[item.id] = url }
        }
    }
    func refresh(more: Bool = false) async {
        guard !loading, !more || !after.isEmpty else { return }
        failedPageWasMore = more
        loading = true
        defer { loading = false }
        do {
            let page = try await service.page(after: more ? after : "")
            try Task.checkCancellation()
            items = more ? items + page.items : page.items
            after = page.after; cached = false; error = nil; failedDownload = nil
            try? store.saveAttachments(items: items, after: after, sessionID: sessionID)
            refreshLocalFiles()
        } catch { report(error) }
    }
    func open(_ item: ChatAttachment) {
        guard downloading == nil else { return }
        if let url = try? store.attachmentURL(item, verify: true) { paused = nil; failedDownload = nil; preview = url; return }
        files[item.id] = nil
        guard item.complete else { return }
        guard item.bytes <= item.effectiveLimit else {
            error = String(localized: "文件较大，请在桌面打开。"); return
        }
        downloading = item.id; activeDownload = item; paused = nil; progress = 0; error = nil; failedDownload = nil
        let revision = store.attachmentCacheRevision
        downloadTask = Task {
            defer { downloading = nil; activeDownload = nil; downloadTask = nil }
            do {
                var offset = try store.beginAttachmentDownload(item, revision: revision)
                progress = item.bytes == 0 ? 1 : Double(offset) / Double(item.bytes)
                repeat {
                    try Task.checkCancellation()
                    try store.requireActive()
                    if offset == item.bytes && item.bytes > 0 { break }
                    let chunk = try await service.chunk(item, offset: offset)
                    try Task.checkCancellation()
                    try store.appendAttachmentChunk(chunk.data, item: item, offset: offset, revision: revision)
                    offset = chunk.next
                    progress = item.bytes == 0 ? 1 : Double(offset) / Double(item.bytes)
                    if chunk.complete { break }
                } while true
                try Task.checkCancellation()
                let url = try store.finishAttachmentDownload(item, revision: revision)
                refreshLocalFiles(); paused = nil; preview = url
            } catch {
                if !(error is CancellationError) && (error as? URLError)?.code != .cancelled { failedDownload = item }
                report(error)
            }
        }
    }
    func openReference(_ reference: ChatAttachment) async {
        guard !loading, downloading == nil else { return }
        items = [reference]; after = ""; cached = false; error = nil
        failedReference = reference
        if let url = try? store.attachmentURL(reference, verify: true) {
            files = [reference.id: url]; preview = url; failedReference = nil; return
        }
        loading = true
        let generation = referenceGeneration
        let cacheRevision = store.attachmentCacheRevision
        defer { loading = false }
        do {
            let item = try await service.lookup(hash: reference.hash)
            try Task.checkCancellation()
            guard generation == referenceGeneration, cacheRevision == store.attachmentCacheRevision else { return }
            try store.requireActive()
            guard let item, item.complete else { throw ClientError(message: String(localized: "文件尚未同步，请稍后重试。")) }
            guard item.hash == reference.hash, item.bytes == reference.bytes else { throw ClientError.invalidResponse }
            items = [item]; failedReference = nil
            open(item)
        } catch { if generation == referenceGeneration { report(error) } }
    }
    func retry() async {
        if let reference = failedReference { await openReference(reference) }
        else if let item = failedDownload { open(item) }
        else { await refresh(more: failedPageWasMore) }
    }
    func pauseDownload() {
        guard let item = activeDownload else { return }
        paused = item; downloadTask?.cancel()
    }
    func resumeDownload() async {
        guard let item = paused else { return }
        await downloadTask?.value
        guard paused?.id == item.id else { return }
        open(item); await waitForDownload()
    }
    func cancel() { referenceGeneration = UUID(); paused = nil; downloadTask?.cancel() }
    func cancelAndWait() async { let task = downloadTask; cancel(); await task?.value }
    func waitForDownload() async { await downloadTask?.value }
    private func report(_ failure: Error) {
        if failure is CancellationError || (failure as? URLError)?.code == .cancelled { return }
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}
