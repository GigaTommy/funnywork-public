import Foundation
import CryptoKit
import UniformTypeIdentifiers

struct OutgoingAttachment: Codable, Equatable, Identifiable {
    let hash: String
    let name: String
    let bytes: Int
    let mediaType: String
    var uploaded = false
    var id: String { hash }
    static let capacity = 32 * 1024 * 1024
    var payload: JSONValue { .object(["hash": .string(hash), "name": .string(name), "bytes": .number(Double(bytes)), "mediaType": .string(mediaType)]) }
    var metadata: [String: Any] { ["name": name, "bytes": bytes, "mediaType": mediaType] }
    init(data: Data, name: String, mediaType: String) throws {
        guard data.count <= Self.capacity else { throw ClientError(message: String(localized: "附件合计不能超过 32 MB。")) }
        hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        self.name = ChatAttachment.safeName(name); bytes = data.count; self.mediaType = mediaType
    }
    init?(payload: JSONValue) {
        guard case .object(let value) = payload, case .string(let hash) = value["hash"],
              hash.count == 64, hash.allSatisfy({ "0123456789abcdef".contains($0) }),
              case .string(let name) = value["name"], case .string(let mediaType) = value["mediaType"],
              case .number(let count) = value["bytes"], count >= 0, count <= Double(Self.capacity), count.rounded() == count else { return nil }
        self.hash = hash; self.name = name; bytes = Int(count); self.mediaType = mediaType
    }
    static func read(_ url: URL) async throws -> (Self, Data) {
        let task = Task.detached(priority: .userInitiated) { () throws -> (Self, Data) in
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let info = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentTypeKey])
            guard info.isRegularFile == true, let size = info.fileSize, size <= capacity else {
                throw ClientError(message: String(localized: "请选择不超过 32 MB 的文件。"))
            }
            try Task.checkCancellation()
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: capacity + 1) ?? Data()
            try Task.checkCancellation()
            let file = try Self(data: data, name: url.lastPathComponent, mediaType: info.contentType?.preferredMIMEType ?? "application/octet-stream")
            return (file, data)
        }
        return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }
}

struct UploadStatus {
    let next: Int
    let complete: Bool
    init(_ row: [String: Any], file: OutgoingAttachment, metadata: Bool = false) throws {
        guard let next = row["nextOffset"] as? Int, next >= 0, next <= file.bytes,
              let complete = row["complete"] as? Bool, (!complete || next == file.bytes), (complete || next < file.bytes || file.bytes == 0) else { throw ClientError.invalidResponse }
        if metadata {
            guard let value = row["metadata"] as? [String: Any], value["attachmentId"] as? String == "sha256:" + file.hash,
                  value["bytes"] as? Int == file.bytes, value["name"] as? String == file.name,
                  value["mediaType"] as? String == file.mediaType else { throw ClientError.invalidResponse }
        }
        self.next = next; self.complete = complete
    }
}
@MainActor protocol UploadService {
    func prepare(_ file: OutgoingAttachment) async throws -> UploadStatus
    func chunk(_ file: OutgoingAttachment, offset: Int, data: Data) async throws -> UploadStatus
    func discard(_ file: OutgoingAttachment) async throws
}
@MainActor struct HTTPUploadService: UploadService {
    let api: API
    let sessionID: String
    private func path(_ file: OutgoingAttachment) -> String { "sync/sessions/\(sessionID)/uploads/\(file.hash)" }
    func prepare(_ file: OutgoingAttachment) async throws -> UploadStatus {
        guard let value = try await api.request(path(file), body: file.metadata) as? [String: Any] else { throw ClientError.invalidResponse }
        return try UploadStatus(value, file: file, metadata: true)
    }
    func chunk(_ file: OutgoingAttachment, offset: Int, data: Data) async throws -> UploadStatus {
        guard let value = try await api.request(path(file) + "/chunks", body: ["offset": offset, "data": data.base64EncodedString()]) as? [String: Any] else { throw ClientError.invalidResponse }
        return try UploadStatus(value, file: file)
    }
    func discard(_ file: OutgoingAttachment) async throws { _ = try await api.request(path(file), method: "DELETE") }
}
