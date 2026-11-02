import Foundation
import Security

struct ClientError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    static func displayMessage(for error: Error) -> String {
        if let network = error as? URLError {
            switch network.code {
            case .notConnectedToInternet, .networkConnectionLost:
                return String(localized: "连接已断开，请检查网络后重试。")
            case .timedOut:
                return String(localized: "等待超时，请重试。")
            case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
                return String(localized: "无法连接，请检查服务地址后重试。")
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid:
                return String(localized: "无法建立安全连接，请联系管理员。")
            default: return String(localized: "暂时无法连接，请稍后重试。")
            }
        }
        if error is ClientError || error is AuthenticationError || error is CommandAdmissionRejected { return error.localizedDescription }
        return String(localized: "暂时无法完成，请重试。")
    }
}

struct Credentials: Codable, Hashable {
    let server: URL
    let token: String
    var principalID: String? = nil
    var tenant: String? = nil
}

enum CredentialStore {
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.funnyworker.ios",
         kSecAttrAccount as String: "session"]
    }
    static func load() -> Credentials? {
        var request = query
        request[kSecReturnData as String] = true
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess,
              let data = value as? Data else { return nil }
        return try? JSONDecoder().decode(Credentials.self, from: data)
    }
    static func save(_ value: Credentials) throws {
        let data = try JSONEncoder().encode(value)
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw ClientError(message: String(localized: "无法安全保存登录，请重试。")) }
    }
    static func clear() { SecItemDelete(query as CFDictionary) }
}

final class API: NSObject, URLSessionTaskDelegate {
    let credentials: Credentials
    private let configuration: URLSessionConfiguration
    convenience init(_ credentials: Credentials) { self.init(credentials, configuration: .ephemeral) }
    init(_ credentials: Credentials, configuration: URLSessionConfiguration) {
        self.credentials = credentials
        self.configuration = configuration.copy() as! URLSessionConfiguration
    }
    static func serverURL(_ input: String) throws -> URL {
        guard let url = URL(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/" else {
            throw ClientError(message: String(localized: "请检查服务地址，使用 https:// 开头的地址。"))
        }
        return url
    }
    // Never forward account credentials through a redirect.
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }

    func makeRequest(_ path: String, body: [String: Any]? = nil) throws -> URLRequest {
        guard let url = URL(string: "api/account/" + path, relativeTo: credentials.server)?.absoluteURL else { throw ClientError(message: String(localized: "暂时无法完成，请重试。")) }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        if !credentials.token.isEmpty { request.setValue("Bearer " + credentials.token, forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpMethod = "POST"
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    func routedRequest(_ path: String, body: [String: Any]? = nil) async throws -> URLRequest {
        var request = try makeRequest(path, body: body)
        guard OfficialService.managed(credentials.server) else { return request }
        guard request.url?.path.hasPrefix("/api/account/") == true else { throw ClientError.invalidResponse }
        let entry = try await OfficialEntryRouter.shared.endpoint(for: credentials.server)
        var components = URLComponents(url: request.url!, resolvingAgainstBaseURL: true)!
        components.host = entry.host
        components.port = entry.port
        request.url = components.url
        try Task.checkCancellation()
        return request
    }

    func request(_ path: String, body: [String: Any]? = nil, method: String? = nil, headers: [String: String] = [:], allowMissing: Bool = false, routeRetry: Bool = true) async throws -> Any {
        var request = try await routedRequest(path, body: body)
        if let method { request.httpMethod = method }
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            if let failure = error as? URLError, failure.code != .cancelled,
               OfficialService.managed(credentials.server), let endpoint = request.url {
                await OfficialEntryRouter.shared.failed(endpoint)
                if request.httpMethod == "GET" && routeRetry {
                    return try await self.request(path, body: body, method: method, headers: headers, allowMissing: allowMissing, routeRetry: false)
                }
            }
            throw error
        }
        guard let http = response as? HTTPURLResponse else { throw ClientError(message: String(localized: "暂时无法完成，请重试。")) }
        if [502, 503, 504].contains(http.statusCode), OfficialService.managed(credentials.server), let endpoint = request.url {
            await OfficialEntryRouter.shared.failed(endpoint)
            if request.httpMethod == "GET" && routeRetry {
                return try await self.request(path, body: body, method: method, headers: headers, allowMissing: allowMissing, routeRetry: false)
            }
        }
        if http.statusCode == 401 {
            if path == "login" { throw ClientError(message: String(localized: "登录失败，请检查账号和密码。")) }
            if path != "session" {
                // Older servers can turn an unsupported feature's error dispatch into 401.
                // Only the account session endpoint can confirm that credentials have expired.
                _ = try await self.request("session")
                throw ClientError(message: String(localized: "请求失败，请稍后重试。"))
            }
            throw AuthenticationError()
        }
        if path.hasPrefix("chats/"), path.hasSuffix("/history"), [400, 403, 404, 409].contains(http.statusCode) {
            throw MobileChatHistoryRejected(message: http.statusCode == 409 ? String(localized: "状态已更新，请刷新后重试。") : String(localized: "未能修改聊天，请刷新后重试。"))
        }
        let commandPath = path.split(separator: "/", omittingEmptySubsequences: false)
        if http.statusCode == 409, request.httpMethod == "POST", commandPath.count == 4,
           commandPath[0] == "sync", ["sessions", "devices"].contains(commandPath[1]),
           !commandPath[2].isEmpty, commandPath[3] == "commands",
           let failure = CommandAdmissionRejected.error(data) { throw failure }
        if !(200..<300).contains(http.statusCode), let failure = ModelAllowanceFailure.error(data) { throw failure }
        if (path == "chats" || path.hasPrefix("chats/")), (http.statusCode == 403 || (!allowMissing && http.statusCode == 404)) { throw ClientError(message: String(localized: "暂时无法开始聊天，请稍后再试。")) }
        if http.statusCode == 403 { throw ClientError(message: String(localized: "没有执行此操作的权限，请联系管理员。")) }
        if allowMissing && http.statusCode == 404 { return NSNull() }
        guard (200..<300).contains(http.statusCode) else {
            if path.hasPrefix("sync/device-links/") {
                throw ClientError(message: http.statusCode == 404 ? String(localized: "无法使用此连接码，请确认两端登录同一账号，并重新扫描。") : http.statusCode == 409 ? String(localized: "连接码已失效或设备离线，请在桌面刷新后重新扫描。") : String(localized: "暂时无法连接，请稍后重试。"))
            }
            throw ClientError(message: http.statusCode == 409 ? String(localized: "状态已更新，请刷新后重试。") : String(localized: "请求失败，请稍后重试。"))
        }
        return data.isEmpty ? [:] : try JSONSerialization.jsonObject(with: data)
    }
}
struct AuthenticationError: LocalizedError {
    var errorDescription: String? { String(localized: "登录已过期，请重新登录。") }
}

// These exact 409 responses prove that the command was rejected before insertion.
// Other failures, including a missing receipt, leave delivery unresolved.
struct CommandAdmissionRejected: LocalizedError {
    let code: String
    var errorDescription: String? { String(localized: "状态已更新，请刷新后重试。") }
    static func error(_ data: Data) -> CommandAdmissionRejected? {
        guard let row = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let code = row["code"] as? String,
              ["SYNC_DECISION_EXPIRED", "SYNC_DEVICE_OFFLINE"].contains(code) else { return nil }
        return CommandAdmissionRejected(code: code)
    }
}

struct Conversation: Identifiable, Codable {
    let id: String
    var title: String
    var previewText: String?
    var previewAttachment: Bool?
    var taskState: ConversationTaskState?
    var taskStateExpiresAt: Date?
    var nextSequence: Int?
    var titleRevision: Int64?
    var archived: Bool?
    var archiveRevision: Int64?
    let device: String
    let online: Bool
    let deviceID: String
    let updatedAt: Date?
    init?(_ row: [String: Any]) {
        guard let id = row["id"] as? String, !id.isEmpty,
              id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        self.id = id
        previewText = (row["preview_text"] as? String).flatMap { $0.utf8.count <= 3200 ? $0 : nil }
        previewAttachment = row["preview_attachment"] as? Bool
        if let value = row["task_state"] as? String, let state = ConversationTaskState(rawValue: value),
           let remaining = row["task_state_remaining_ms"] as? Int, remaining > 0, remaining <= 30000 {
            taskState = state; taskStateExpiresAt = Date().addingTimeInterval(Double(remaining) / 1000)
        }
        nextSequence = (row["next_seq"] as? Int).flatMap { $0 >= 0 ? $0 : nil }
        titleRevision = (row["title_revision"] as? NSNumber)?.int64Value
        archived = row["archived"] as? Bool
        archiveRevision = (row["archive_revision"] as? NSNumber)?.int64Value
        title = (row["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "未命名对话")
        device = remoteHostName(row["device_name"] as? String ?? String(localized: "桌面"), deviceID: row["device_id"] as? String ?? "")
        online = row["online"] as? Bool ?? false
        deviceID = row["device_id"] as? String ?? device
        updatedAt = (row["updated_at"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
    }
}

struct HistoryItem: Identifiable, Codable, Equatable {
    let id: Int
    let label: String
    let text: String
    let attachments: [ChatAttachment]
    let results: [ResultVersion]
    let eventType: String?
    let source: String?
    let activity: HistoryActivity?
    let interrupted: Bool
    var isUser: Bool {
        if let eventType { return eventType == "user/message" && (source == nil || source == "user") }
        return ["你", "You"].contains(label)
    }
    var displayLabel: String { activity?.title ?? (isUser ? String(localized: "你") : label == "funnywork" ? "FunnyWork" : label) }
    var activityText: String { [activity?.detail, text.isEmpty ? nil : text].compactMap { $0 }.joined(separator: "\n\n") }
    var searchableText: String { [activity?.name, activity?.detail, text].compactMap { $0 }.joined(separator: "\n") }
    private enum CodingKeys: String, CodingKey { case id, label, text, attachments, results, eventType, source, activity, interrupted }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(Int.self, forKey: .id)
        label = try values.decode(String.self, forKey: .label)
        text = try values.decode(String.self, forKey: .text)
        attachments = try values.decodeIfPresent([ChatAttachment].self, forKey: .attachments) ?? []
        results = (try values.decodeIfPresent([ResultVersion].self, forKey: .results) ?? []).filter(\.valid)
        eventType = try values.decodeIfPresent(String.self, forKey: .eventType)
        source = try values.decodeIfPresent(String.self, forKey: .source)
        activity = try values.decodeIfPresent(HistoryActivity.self, forKey: .activity)
        let partial = try values.decodeIfPresent(Bool.self, forKey: .interrupted) ?? false
        interrupted = eventType == "assistant/message" && partial
    }
    init?(_ row: [String: Any]) {
        guard let seq = row["seq"] as? Int else { return nil }
        id = seq
        let type = row["type"] as? String ?? ""
        eventType = type.isEmpty ? nil : type
        label = type == "user/message" ? String(localized: "你") : type == "assistant/message" ? "FunnyWork" : type
        let data = row["data"] as? [String: Any] ?? [:]
        results = type == "deliverables/presented" ? (data["files"] as? [[String: Any]] ?? []).compactMap { $0["result"].flatMap(ResultVersion.parse) } : []
        interrupted = type == "assistant/message" && data["interrupted"] as? Bool == true
        let message = data["message"] as? [String: Any] ?? data
        source = (message["source"] as? [String: Any])?["kind"] as? String
        activity = HistoryActivity.parse(type: type, data: data, source: source)
        let parts = message["content"] as? [[String: Any]] ?? []
        text = parts.filter { ($0["type"] as? String).map { $0 == "text" } ?? true }
            .compactMap { $0["text"] as? String }.joined(separator: "\n")
        var hashes = Set<String>()
        attachments = parts.compactMap { part in
            guard let reference = part["attachment"] as? [String: Any],
                  let item = try? ChatAttachment(reference: reference), hashes.insert(item.hash).inserted else { return nil }
            return item
        }
    }
}
