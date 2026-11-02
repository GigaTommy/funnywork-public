import Foundation
import CryptoKit
import Combine

struct QueueWithdrawal: Codable, Equatable {
    let itemID: String
    let text: String
}

struct QueueEditDraft: Codable, Equatable, Identifiable {
    var id: String { itemID }
    let itemID: String
    var text: String
    var command: RemoteCommand? = nil
    var status = ""
}

struct SavedConversation: Codable, Equatable {
    var draft = ""
    var files: [OutgoingAttachment] = []
    var answers: [String: QuestionDraft] = [:]
    var pending: RemoteCommand?
    var interactionRequests: [String: String] = [:]
    var recentCommands: [RemoteCommand] = []
    var admissionRejections: [String: String] = [:]
    var queueWithdrawals: [String: QueueWithdrawal] = [:]
    var seededCreationID: String?
    var reviewDrafts: [String: String] = [:]
    var reviewSnapshots: [String: RemoteResultReview] = [:]
    var reviewFileNotes: [String: [String: String]] = [:]
    var queueEdits: [String: QueueEditDraft] = [:]
    init() {}
    private enum CodingKeys: String, CodingKey { case draft, files, answers, pending, interactionRequests, recentCommands, admissionRejections, queueWithdrawals, seededCreationID, reviewDrafts, reviewSnapshots, reviewFileNotes, queueEdits }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        draft = try values.decodeIfPresent(String.self, forKey: .draft) ?? ""
        files = try values.decodeIfPresent([OutgoingAttachment].self, forKey: .files) ?? []
        answers = try values.decodeIfPresent([String: QuestionDraft].self, forKey: .answers) ?? [:]
        pending = try values.decodeIfPresent(RemoteCommand.self, forKey: .pending)
        interactionRequests = try values.decodeIfPresent([String: String].self, forKey: .interactionRequests) ?? [:]
        recentCommands = try values.decodeIfPresent([RemoteCommand].self, forKey: .recentCommands) ?? []
        admissionRejections = try values.decodeIfPresent([String: String].self, forKey: .admissionRejections) ?? [:]
        queueWithdrawals = try values.decodeIfPresent([String: QueueWithdrawal].self, forKey: .queueWithdrawals) ?? [:]
        seededCreationID = try values.decodeIfPresent(String.self, forKey: .seededCreationID)
        reviewDrafts = try values.decodeIfPresent([String: String].self, forKey: .reviewDrafts) ?? [:]
        reviewSnapshots = try values.decodeIfPresent([String: RemoteResultReview].self, forKey: .reviewSnapshots) ?? [:]
        reviewFileNotes = try values.decodeIfPresent([String: [String: String]].self, forKey: .reviewFileNotes) ?? [:]
        queueEdits = try values.decodeIfPresent([String: QueueEditDraft].self, forKey: .queueEdits) ?? [:]
    }
}

struct ConversationReadMarker: Codable {
    let sequence: Int
    let date: Date
}

struct SavedConversationNavigation: Codable {
    var scope = "mobile"
    var desktopID: String?
}

/// One store instance belongs to one validated account. Logout invalidates existing holders.
@MainActor final class ConversationStore: ObservableObject {
    @Published private(set) var resultLinksRevision = UUID()
    @Published private(set) var readMarkers: [String: ConversationReadMarker] = [:]
    private let directory: URL
    private var enabled = true
    private(set) var attachmentCacheRevision = UUID()
    static func accountKey(server: URL, tenant: String, principal: String, root: URL? = nil) -> String {
        let key = rawAccountKey(server: OfficialService.identity(server), tenant: tenant, principal: principal)
        guard OfficialService.managed(server) else { return key }
        let base = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Conversations", isDirectory: true)
        let legacy = rawAccountKey(server: OfficialService.mainland, tenant: tenant, principal: principal)
        let old = base.appendingPathComponent(legacy, isDirectory: true)
        let values = try? old.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        if !FileManager.default.fileExists(atPath: base.appendingPathComponent(key).path),
           values?.isDirectory == true, values?.isSymbolicLink != true { return legacy }
        return key
    }
    private static func rawAccountKey(server: URL, tenant: String, principal: String) -> String {
        var components = URLComponents(url: server, resolvingAgainstBaseURL: true)!
        components.host = components.host?.lowercased()
        components.path = ""
        let identity = [components.string ?? server.absoluteString, tenant, principal]
        return digest((try? JSONEncoder().encode(identity)) ?? Data())
    }
    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    init(key: String, root: URL? = nil) {
        let base = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Conversations", isDirectory: true)
        directory = base.appendingPathComponent(key, isDirectory: true)
        clearUploadPreview()
        readMarkers = (try? readCache("read-markers", maximumBytes: 512 * 1024)) ?? [:]
    }
    func loadNavigation() throws -> SavedConversationNavigation {
        try readCache("navigation", maximumBytes: 4096) ?? SavedConversationNavigation()
    }
    func saveNavigation(_ value: SavedConversationNavigation) throws {
        guard ["mobile", "desktop"].contains(value.scope), (value.desktopID?.utf8.count ?? 0) <= 256 else { throw ClientError.invalidResponse }
        try writeCache(value, name: "navigation")
    }
    private func file(_ id: String) -> URL { directory.appendingPathComponent(Self.digest(Data(id.utf8)) + ".json") }
    func load(_ id: String) throws -> SavedConversation {
        guard enabled else { throw ClientError(message: String(localized: "请重新登录。")) }
        let url = file(id)
        guard FileManager.default.fileExists(atPath: url.path) else { return SavedConversation() }
        return try JSONDecoder().decode(SavedConversation.self, from: Data(contentsOf: url))
    }
    func save(_ value: SavedConversation, for id: String) throws {
        guard enabled else { throw ClientError(message: String(localized: "请重新登录。")) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = directory
        var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try excluded.setResourceValues(resources)
        try JSONEncoder().encode(value).write(to: file(id), options: [.atomic, .completeFileProtection])
    }
    func loadHistory(_ id: String) throws -> CachedHistory? {
        let cache: CachedHistory? = try readCache("history-" + Self.digest(Data(id.utf8)), maximumBytes: CachedHistory.maximumBytes)
        guard let cache, cache.presentationVersion == CachedHistory.currentPresentationVersion,
              cache.earliest >= 0, cache.latest >= cache.earliest,
              cache.items.count <= 200, cache.latest - cache.earliest == cache.items.count,
              cache.items.enumerated().allSatisfy({ $0.element.id == cache.earliest + $0.offset }) else { return nil }
        return cache
    }
    func saveHistory(_ value: CachedHistory, for id: String) throws {
        var value = value
        if let position = (try? loadHistory(id))?.readingPosition,
           value.items.contains(where: { $0.id == position }) { value.readingPosition = position }
        try writeCache(value, name: "history-" + Self.digest(Data(id.utf8)))
        // Bound read-only history storage without deleting drafts or pending commands.
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])
            .filter { $0.lastPathComponent.hasPrefix("history-") }
            .sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) > ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        for file in files.dropFirst(30) { try FileManager.default.removeItem(at: file) }
    }
    func saveReadingPosition(_ position: Int?, for id: String) throws {
        guard var cache = try loadHistory(id) else { return }
        cache.readingPosition = position.flatMap { candidate in
            cache.items.contains(where: { $0.id == candidate }) ? candidate : nil
        }
        try writeCache(cache, name: "history-" + Self.digest(Data(id.utf8)))
    }
    func markRead(through sequence: Int, for id: String) throws {
        try requireActive()
        guard sequence >= 0, CreationDevice.validID(id) else { return }
        guard sequence > (readMarkers[id]?.sequence ?? -1) else { return }
        var markers: [String: ConversationReadMarker] = try readCache("read-markers", maximumBytes: 512 * 1024) ?? [:]
        if sequence > (markers[id]?.sequence ?? -1) { markers[id] = .init(sequence: sequence, date: Date()) }
        if markers.count > 2000 {
            markers = Dictionary(uniqueKeysWithValues: markers.sorted { $0.value.date > $1.value.date }.prefix(2000).map { ($0.key, $0.value) })
        }
        try writeCache(markers, name: "read-markers")
        readMarkers = markers
    }
    func hasUnread(_ row: Conversation) -> Bool {
        guard let next = row.nextSequence else { return false }
        return next > (readMarkers[row.id]?.sequence ?? 0)
    }
    func loadDirectory() throws -> CachedDirectory? { try readCache("directory") }
    func loadPinnedConversations() throws -> Set<String> { try readCache("pinned-conversations") ?? [] }
    func savePinnedConversations(_ ids: Set<String>) throws { try writeCache(ids, name: "pinned-conversations") }
    func saveDirectory(_ rows: [Conversation]) throws {
        let durable = rows.map { value in
            var row = value; row.taskState = nil; row.taskStateExpiresAt = nil
            return row
        }
        try writeCache(CachedDirectory(rows: Array(durable.sorted { ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }.prefix(500)), date: Date()), name: "directory")
    }
    private func readCache<T: Decodable>(_ name: String, maximumBytes: Int? = nil) throws -> T? {
        guard enabled else { throw AuthenticationError() }
        let url = directory.appendingPathComponent(name + ".json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        if let maximumBytes {
            guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= maximumBytes else { return nil }
        }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
    }
    private func writeCache<T: Encodable>(_ value: T, name: String) throws {
        guard enabled else { throw AuthenticationError() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = directory
        var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try excluded.setResourceValues(resources)
        try JSONEncoder().encode(value).write(to: directory.appendingPathComponent(name + ".json"), options: [.atomic, .completeFileProtection])
    }
    func invalidate() { clearUploadPreview(); enabled = false }
    func invalidateAndClear() throws {
        enabled = false
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
}

struct CachedDirectory: Codable {
    let rows: [Conversation]
    let date: Date
}
struct CachedHistory: Codable {
    static let maximumBytes = 2_000_000
    static let currentPresentationVersion = 1
    let presentationVersion: Int
    var readingPosition: Int?
    let items: [HistoryItem]
    let earliest: Int
    let latest: Int
    let date: Date
    private enum CodingKeys: String, CodingKey { case presentationVersion, readingPosition, items, earliest, latest, date }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        presentationVersion = try values.decodeIfPresent(Int.self, forKey: .presentationVersion) ?? 0
        readingPosition = try values.decodeIfPresent(Int.self, forKey: .readingPosition)
        items = try values.decode([HistoryItem].self, forKey: .items)
        earliest = try values.decode(Int.self, forKey: .earliest)
        latest = try values.decode(Int.self, forKey: .latest)
        date = try values.decode(Date.self, forKey: .date)
    }
    init(items: [HistoryItem], latest: Int, date: Date = Date()) {
        presentationVersion = Self.currentPresentationVersion
        // Keep a contiguous suffix so reconnect can resume at exactly the saved cursor.
        var suffix: [HistoryItem] = []
        // Reserve space for the envelope, cursor, date and optional reading position.
        var bytes = 256
        let encoder = JSONEncoder()
        var expected = latest - 1
        for item in items.suffix(200).reversed() {
            guard item.id == expected else { break }
            expected -= 1
            // Reject obviously oversized content before allocating its encoded copy.
            let minimumBytes = item.text.utf8.count + (item.activity?.detail?.utf8.count ?? 0)
                + (item.activity?.name?.utf8.count ?? 0) + item.label.utf8.count
            guard minimumBytes <= Self.maximumBytes - bytes,
                  let encoded = try? encoder.encode(item), encoded.count + 1 <= Self.maximumBytes - bytes else { break }
            bytes += encoded.count + 1
            suffix.append(item)
        }
        self.items = suffix.reversed()
        self.earliest = self.items.first?.id ?? latest
        self.latest = latest
        self.date = date
    }
}

struct SavedAttachments: Codable {
    let items: [ChatAttachment]
    let after: String
}

extension ConversationStore {
    func loadResultLinks() throws -> [MobileResultLink] { try readCache("mobile-result-links") ?? [] }
    func saveResultLinks(_ links: [MobileResultLink]) throws {
        let values = Array(links.suffix(100)), encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        guard try encoder.encode(values) != encoder.encode(loadResultLinks()) else { return }
        try writeCache(values, name: "mobile-result-links"); resultLinksRevision = UUID()
    }
    func linkResultTask(_ conversation: Conversation, sourceChatID: String) throws {
        guard CreationDevice.validID(sourceChatID), CreationDevice.validID(conversation.id) else { throw ClientError.invalidResponse }
        var links = try loadResultLinks()
        if !links.contains(where: { $0.chatID == sourceChatID && $0.id == conversation.id }) {
            links.append(MobileResultLink(chatID: sourceChatID, conversation: conversation, confirmed: false))
            try saveResultLinks(links)
        }
    }
    func saveResultTransferSource(_ chatID: String) throws { try writeCache(chatID, name: "mobile-result-transfer-source") }
    func loadResultTransferSource() throws -> String? { try readCache("mobile-result-transfer-source") }
    func loadResultDirectory(_ session: String) throws -> [ResultRecord]? { try readCache("results-" + Self.digest(Data(session.utf8))) }
    func saveResultDirectory(_ rows: [ResultRecord], session: String) throws { try writeCache(rows, name: "results-" + Self.digest(Data(session.utf8))) }
    func loadMobileHistoryUpdate() throws -> MobileChatHistoryUpdate? {
        try requireActive(); let saved: SavedMobileChatHistoryUpdate? = try readCache("mobile-history-update", maximumBytes: 8192); return saved?.pending
    }
    func saveMobileHistoryUpdate(_ value: MobileChatHistoryUpdate?) throws { try writeCache(SavedMobileChatHistoryUpdate(pending: value), name: "mobile-history-update") }
    func requireActive() throws {
        guard enabled else { throw AuthenticationError() }
    }
    func loadAttachments(_ sessionID: String) throws -> SavedAttachments? {
        try readCache("attachments-" + Self.digest(Data(sessionID.utf8)))
    }
    func saveAttachments(items: [ChatAttachment], after: String, sessionID: String) throws {
        // Keep the first pages as a contiguous prefix, preserving its pagination cursor.
        let limited = Array(items.prefix(1000))
        let cursor = items.count > limited.count ? limited.last!.hash : after
        try writeCache(SavedAttachments(items: limited, after: cursor), name: "attachments-" + Self.digest(Data(sessionID.utf8)))
    }
    private func attachmentPath(_ item: ChatAttachment) throws -> URL {
        try requireActive()
        guard ChatAttachment.validHash(item.hash), item.name == ChatAttachment.safeName(item.name),
              item.bytes >= 0, item.bytes <= ChatAttachment.maxDownloadBytes else { throw ClientError.invalidResponse }
        return directory.appendingPathComponent("Files", isDirectory: true)
            .appendingPathComponent(item.hash, isDirectory: true).appendingPathComponent(item.name)
    }
    func attachmentURL(_ item: ChatAttachment, verify: Bool = false) throws -> URL? {
        let url = try attachmentPath(item)
        guard FileManager.default.fileExists(atPath: url.path),
              (try url.resourceValues(forKeys: [.fileSizeKey])).fileSize == item.bytes else { return nil }
        if verify, try Self.fileDigest(url) != item.hash { return nil }
        return url
    }
    func saveAttachment(_ data: Data, item: ChatAttachment, expectedRevision: UUID? = nil) throws -> URL {
        if let expectedRevision, expectedRevision != attachmentCacheRevision { throw CancellationError() }
        let url = try attachmentPath(item)
        guard data.count == item.bytes, Self.digest(data) == item.hash else {
            throw ClientError(message: String(localized: "文件校验失败，请重新下载。"))
        }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = directory
        var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try excluded.setResourceValues(resources)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        try pruneAttachments(keeping: url)
        return url
    }
    func downloadedFileUsage() throws -> (count: Int, bytes: Int) {
        try requireActive()
        let downloads = directory.appendingPathComponent("Downloads", isDirectory: true)
        let partials = (try? FileManager.default.contentsOfDirectory(at: downloads, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        var count = 0; var bytes = 0
        for file in partials where file.pathExtension == "part" { count += 1; bytes += (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0 }
        let root = directory.appendingPathComponent("Files", isDirectory: true)
        guard FileManager.default.fileExists(atPath: root.path) else { return (count, bytes) }
        let folders = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        for folder in folders {
            for file in try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]) {
                let info = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                if info.isRegularFile == true { count += 1; bytes += info.fileSize ?? 0 }
            }
        }
        return (count, bytes)
    }
    func clearDownloadedFiles() throws {
        try requireActive()
        // Downloads started before this operation must not repopulate the cleared cache.
        attachmentCacheRevision = UUID()
        let root = directory.appendingPathComponent("Files", isDirectory: true)
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
        let partials = directory.appendingPathComponent("Downloads", isDirectory: true)
        if FileManager.default.fileExists(atPath: partials.path) { try FileManager.default.removeItem(at: partials) }
    }
    private struct PartialAttachment: Codable { let bytes: Int; let offset: Int }
    private func partialPath(_ item: ChatAttachment, prune: Bool = false) throws -> URL {
        _ = try attachmentPath(item)
        let root = directory.appendingPathComponent("Downloads", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try excluded.setResourceValues(values)
        let target = root.appendingPathComponent(item.hash + ".part")
        guard prune else { return target }
        let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]).filter { $0.pathExtension == "part" }
        var total = 0
        for file in files { total += (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0 }
        var count = files.count
        for file in files.sorted(by: { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }) where file != target {
            guard count >= 32 || total + item.bytes > 256 * 1024 * 1024 else { break }
            total -= (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0; count -= 1
            try FileManager.default.removeItem(at: file); try? FileManager.default.removeItem(at: file.appendingPathExtension("json"))
        }
        return target
    }
    func beginAttachmentDownload(_ item: ChatAttachment, revision: UUID) throws -> Int {
        try requireActive(); guard revision == attachmentCacheRevision else { throw CancellationError() }
        let url = try partialPath(item, prune: true), metadata = url.appendingPathExtension("json")
        var offset = 0
        if let data = try? Data(contentsOf: metadata), let saved = try? JSONDecoder().decode(PartialAttachment.self, from: data), saved.bytes == item.bytes,
           saved.offset >= 0, saved.offset <= item.bytes, let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size >= saved.offset { offset = saved.offset }
        if !FileManager.default.fileExists(atPath: url.path) { guard FileManager.default.createFile(atPath: url.path, contents: Data(), attributes: [.protectionKey: FileProtectionType.complete]) else { throw CocoaError(.fileWriteUnknown) } }
        let file = try FileHandle(forWritingTo: url); defer { try? file.close() }; try file.truncate(atOffset: UInt64(offset))
        try JSONEncoder().encode(PartialAttachment(bytes: item.bytes, offset: offset)).write(to: metadata, options: [.atomic, .completeFileProtection])
        return offset
    }
    func appendAttachmentChunk(_ data: Data, item: ChatAttachment, offset: Int, revision: UUID) throws {
        try requireActive(); guard revision == attachmentCacheRevision else { throw CancellationError() }
        let url = try partialPath(item)
        guard offset >= 0, data.count <= item.bytes - offset, (try url.resourceValues(forKeys: [.fileSizeKey])).fileSize == offset else { throw ClientError.invalidResponse }
        let file = try FileHandle(forWritingTo: url); defer { try? file.close() }; try file.seek(toOffset: UInt64(offset)); try file.write(contentsOf: data); try file.synchronize()
        try JSONEncoder().encode(PartialAttachment(bytes: item.bytes, offset: offset + data.count)).write(to: url.appendingPathExtension("json"), options: [.atomic, .completeFileProtection])
    }
    func finishAttachmentDownload(_ item: ChatAttachment, revision: UUID) throws -> URL {
        try requireActive(); guard revision == attachmentCacheRevision else { throw CancellationError() }
        let source = try partialPath(item), destination = try attachmentPath(item)
        guard (try source.resourceValues(forKeys: [.fileSizeKey])).fileSize == item.bytes, try Self.fileDigest(source) == item.hash else {
            try? FileManager.default.removeItem(at: source); try? FileManager.default.removeItem(at: source.appendingPathExtension("json"))
            throw ClientError(message: String(localized: "文件校验失败，请重新下载。"))
        }
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
        try FileManager.default.moveItem(at: source, to: destination); try? FileManager.default.removeItem(at: source.appendingPathExtension("json"))
        try pruneAttachments(keeping: destination); return destination
    }
    private static func fileDigest(_ url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }; var hash = SHA256()
        while let bytes = try file.read(upToCount: 384000), !bytes.isEmpty { hash.update(data: bytes) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    private func pruneAttachments(keeping url: URL) throws {
        let root = directory.appendingPathComponent("Files", isDirectory: true)
        let folders = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        var files: [(url: URL, size: Int, date: Date)] = []
        for folder in folders {
            for file in try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) {
                let info = try file.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
                files.append((file, info.fileSize ?? 0, info.contentModificationDate ?? .distantPast))
            }
        }
        var size = files.reduce(0) { $0 + $1.size }
        var count = files.count
        for file in files.sorted(by: { $0.date < $1.date }) where file.url != url {
            guard count > 10 || size > 256 * 1024 * 1024 else { break }
            try FileManager.default.removeItem(at: file.url)
            size -= file.size; count -= 1
        }
        for folder in folders where (try FileManager.default.contentsOfDirectory(atPath: folder.path)).isEmpty {
            try FileManager.default.removeItem(at: folder)
        }
    }
}

extension ConversationStore {
    func loadCreation() throws -> SavedCreation? { try readCache("new-conversation") }
    func loadMobileChat() throws -> SavedMobileChat? { try requireActive(); return try readCache("mobile-chat", maximumBytes: 4194304) }
    func saveMobileChat(_ value: SavedMobileChat) throws {
        guard try JSONEncoder().encode(value).count <= 4194304 else { throw ClientError(message: String(localized: "无法保存聊天记录，请减少草稿后重试。")) }
        try writeCache(value, name: "mobile-chat")
    }
    func saveCreation(_ value: SavedCreation) throws { try writeCache(value, name: "new-conversation") }
    // MainActor serialization keeps the read and clear together across page instances.
    func clearCreation(matching requestID: String, recentTarget: CreationTarget? = nil) throws -> Bool {
        guard var saved = try loadCreation(), saved.pending?.id == requestID else { return false }
        saved.pending = nil
        if recentTarget != nil, saved.draft == saved.pendingDraft { saved.draft = ""; saved.selection = nil }
        saved.pendingDraft = nil; saved.pendingSourceChatID = nil
        if let recentTarget { saved.recentTarget = recentTarget }
        try saveCreation(saved)
        return true
    }
}

extension ConversationStore {
    private func uploadURL(_ file: OutgoingAttachment, sessionID: String) -> URL {
        directory.appendingPathComponent("upload-" + Self.digest(Data((sessionID + ":" + file.hash).utf8)))
    }
    func saveUpload(_ data: Data, file: OutgoingAttachment, sessionID: String) throws {
        guard enabled, data.count == file.bytes, data.count <= OutgoingAttachment.capacity,
              Self.digest(data) == file.hash else { throw ClientError.invalidResponse }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        let used = try files.filter { $0.lastPathComponent.hasPrefix("upload-") && $0 != uploadURL(file, sessionID: sessionID) }
            .reduce(0) { try $0 + ($1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        guard used + data.count <= 256 * 1024 * 1024 else { throw ClientError(message: String(localized: "附件草稿空间已满，请移除部分附件。")) }
        var folder = directory; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
        try data.write(to: uploadURL(file, sessionID: sessionID), options: [.atomic, .completeFileProtection])
    }
    func loadUpload(_ file: OutgoingAttachment, sessionID: String) throws -> Data {
        guard enabled else { throw AuthenticationError() }
        guard file.bytes >= 0, file.bytes <= OutgoingAttachment.capacity else { throw ClientError.invalidResponse }
        let handle = try FileHandle(forReadingFrom: uploadURL(file, sessionID: sessionID))
        defer { try? handle.close() }
        let data = try handle.read(upToCount: file.bytes + 1) ?? Data()
        guard data.count == file.bytes, Self.digest(data) == file.hash else { throw ClientError.invalidResponse }
        return data
    }
    func prepareUploadPreview(_ file: OutgoingAttachment, sessionID: String) throws -> URL {
        let data = try loadUpload(file, sessionID: sessionID)
        try requireActive()
        let folder = directory.appendingPathComponent("DraftPreview", isDirectory: true)
        if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        var protectedFolder = folder; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try protectedFolder.setResourceValues(values)
        let url = folder.appendingPathComponent(ChatAttachment.safeName(file.name))
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }
    func clearUploadPreview() {
        guard enabled else { return }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent("DraftPreview", isDirectory: true))
    }
    func removeUpload(_ file: OutgoingAttachment, sessionID: String) {
        guard enabled else { return }
        try? FileManager.default.removeItem(at: uploadURL(file, sessionID: sessionID))
    }
}


extension ConversationStore {
    func loadFork(_ sourceID: String) throws -> SavedFork? { try readCache("fork-" + Self.digest(Data(sourceID.utf8)), maximumBytes: 8192) }
    func saveFork(_ value: SavedFork, sourceID: String) throws { try writeCache(value, name: "fork-" + Self.digest(Data(sourceID.utf8))) }
    func clearFork(_ sourceID: String, matching requestID: String) throws -> Bool {
        guard let saved = try loadFork(sourceID), saved.pending?.id == requestID else { return false }
        try saveFork(SavedFork(pending: nil), sourceID: sourceID); return true
    }
}
