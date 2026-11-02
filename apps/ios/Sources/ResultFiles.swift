import Foundation

struct ResultFile: Codable, Equatable {
    struct Reference: Codable, Equatable { let attachmentId: String; let name: String; let bytes: Int }
    let type: String
    let attachment: Reference
    var file: ChatAttachment? { try? ChatAttachment(reference: ["attachmentId": attachment.attachmentId, "name": attachment.name, "bytes": attachment.bytes]) }
}
struct ResultVariant: Codable, Equatable, Identifiable {
    var id: String { key }
    let role: String
    let key: String
    let content: ResultFile
}
struct ResultVersion: Codable, Equatable, Identifiable {
    let schema: Int
    let id: String
    let version: Int
    let previousHash: String?
    let original: ResultFile
    let variants: [ResultVariant]
    let previewState: String
    let warnings: [String]
    var identity: String { id + ":" + String(version) }
    var valid: Bool {
        schema == 1 && CreationDevice.validID(id) && (1...1000).contains(version) && original.type == "file" && original.file != nil &&
        variants.count <= 256 && Set(variants.map(\.key)).count == variants.count && variants.allSatisfy {
            ["pdf", "thumbnail", "workbook", "sheet"].contains($0.role) && $0.key != "original" && $0.key.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil && $0.content.type == "file" && $0.content.file != nil
        } && ["pending", "ready", "failed"].contains(previewState) && warnings.count <= 20 && warnings.allSatisfy { $0.count <= 512 }
    }
    static func parse(_ value: Any) -> Self? {
        guard JSONSerialization.isValidJSONObject(value), let data = try? JSONSerialization.data(withJSONObject: value), data.count <= 262144,
              let result = try? JSONDecoder().decode(Self.self, from: data), result.valid else { return nil }
        return result
    }
    var name: String { original.file?.name ?? String(localized: "未命名文件") }
    func feedback(_ comment: String, location: String) -> String {
        String(localized: "请修改这个固定版本的结果文件：") + "\n" + name + "\nresultId=" + id + "\nversion=" + String(version) +
        "\nsha256=" + String(original.attachment.attachmentId.dropFirst(7)) + "\n" + location + "\n" + comment +
        "\n" + String(localized: "修改后调用 publish_result，使用上述 resultId 和 expectedVersion 发布新版本。")
    }
}

struct ResultTransfer: Codable, Equatable { let nextOffset: Int; let complete: Bool }
struct ResultRecord: Codable, Equatable, Identifiable {
    var id: String { manifest.identity }
    let manifest: ResultVersion
    let eventSeq: Int
    let createdAt: Int64
    let transfers: [String: ResultTransfer]
}
@MainActor protocol ResultService {
    func versions(_ id: String) async throws -> [ResultRecord]
    func directory() async throws -> [ResultRecord]
}
@MainActor struct HTTPResultService: ResultService {
    let api: API
    let sessionID: String
    func directory() async throws -> [ResultRecord] {
        var after = -1, records: [ResultRecord] = []
        repeat {
            guard let page = try await api.request("sync/sessions/\(sessionID)/results?after=\(after)") as? [String: Any],
                  let items = page["items"], let cursor = page["after"] as? Int, let more = page["hasMore"] as? Bool else { throw ClientError.invalidResponse }
            let data = try JSONSerialization.data(withJSONObject: items), values = try JSONDecoder().decode([ResultRecord].self, from: data)
            guard values.count <= 256, values.allSatisfy({ $0.manifest.valid && $0.eventSeq > after }), records.count + values.count <= 1000 else { throw ClientError.invalidResponse }
            records.append(contentsOf: values)
            if !more { return records }; guard cursor > after else { throw ClientError.invalidResponse }; after = cursor
        } while !Task.isCancelled
        throw CancellationError()
    }
    func versions(_ id: String) async throws -> [ResultRecord] {
        guard CreationDevice.validID(id) else { throw ClientError.invalidResponse }
        let row = try await api.request("sync/sessions/\(sessionID)/results/\(id)/versions")
        let data = try JSONSerialization.data(withJSONObject: row)
        let values = try JSONDecoder().decode([ResultRecord].self, from: data)
        guard values.count <= 1000, values.allSatisfy({ $0.manifest.valid && $0.manifest.id == id }) else { throw ClientError.invalidResponse }
        return values
    }
}

struct WorkbookIndex: Decodable {
    struct Sheet: Decodable, Identifiable { let id: String; let name: String; let rows: Int; let columns: Int; let tiles: [String]; let merges: [String]; let widths: [Double] }
    let schema: Int
    let sheets: [Sheet]
    let missingResults: Int
    let warnings: [String]
}
struct WorkbookTile: Decodable {
    struct Cell: Decodable, Identifiable { var id: String { "\(row):\(column)" }; let row: Int; let column: Int; let text: String; let formula: String?; let missingResult: Bool?; let bold: Bool?; let italic: Bool?; let align: String?; let mergeRows: Int?; let mergeColumns: Int? }
    let schema: Int
    let sheetId: String
    let firstRow: Int
    let cells: [Cell]
}
