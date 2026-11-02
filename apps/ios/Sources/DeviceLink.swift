import Foundation
import CoreFoundation
import Combine

struct DeviceLinkCode: Equatable {
    let id: String
    let secret: String
    init(_ input: String, server: URL) throws {
        guard input.utf8.count <= 2048, let url = URLComponents(string: input),
              url.scheme == "funnywork", url.host == "connect", url.path.isEmpty,
              url.user == nil, url.password == nil, url.port == nil, url.fragment == nil,
              let items = url.queryItems, items.count == 4,
              Set(items.map(\.name)) == Set(["v", "server", "id", "secret"]) else { throw Self.invalid }
        let fields = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
        guard fields["v"] == "1", let address = fields["server"], let scanned = try? API.serverURL(address),
              let id = fields["id"], CreationDevice.validID(id), let secret = fields["secret"],
              secret.utf8.count == 64, secret.allSatisfy({ $0.isASCII && ($0.isNumber || ("a"..."f").contains(String($0))) }) else { throw Self.invalid }
        guard Self.origin(OfficialService.identity(scanned)) == Self.origin(OfficialService.identity(server)) else {
            throw ClientError(message: String(localized: "服务地址不一致，请在桌面登录当前服务后重试。"))
        }
        self.id = id; self.secret = secret
    }
    private static var invalid: ClientError { ClientError(message: String(localized: "这不是有效的连接码，请重新扫描。")) }
    private static func origin(_ url: URL) -> String { "\(url.scheme?.lowercased() ?? "")://\(url.host?.lowercased() ?? ""):\(url.port ?? 443)" }
}
struct DeviceLinkPreview: Equatable {
    let id: String
    let deviceID: String
    let name: String
    let platform: String
    let epoch: String
    let expiresIn: TimeInterval
    let confirmed: Bool
    init(_ row: [String: Any]) throws {
        guard let id = row["id"] as? String, CreationDevice.validID(id),
              let device = row["deviceId"] as? String, CreationDevice.validID(device),
              let name = row["name"] as? String, !name.isEmpty, name.utf8.count <= 1024,
              let platform = row["platform"] as? String, platform.utf8.count <= 128,
              let epoch = row["epoch"] as? String, CreationDevice.validID(epoch),
              let remaining = row["expiresIn"] as? NSNumber, CFGetTypeID(remaining) != CFBooleanGetTypeID(),
              remaining.doubleValue > 0, remaining.doubleValue <= 120000, remaining.doubleValue.rounded() == remaining.doubleValue,
              let confirmed = row["confirmed"] as? NSNumber, CFGetTypeID(confirmed) == CFBooleanGetTypeID() else { throw ClientError.invalidResponse }
        self.id = id; deviceID = device; self.name = name; self.platform = platform; self.epoch = epoch
        expiresIn = remaining.doubleValue / 1000; self.confirmed = confirmed.boolValue
    }
}
@MainActor protocol DeviceLinkService {
    var linkServer: URL { get }
    func inspectLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview
    func confirmLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview
}
extension HTTPNewConversationService: DeviceLinkService {
    var linkServer: URL { api.credentials.server }
    func inspectLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview { try await linkRequest(code, action: "inspect") }
    func confirmLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview { try await linkRequest(code, action: "confirm") }
    private func linkRequest(_ code: DeviceLinkCode, action: String) async throws -> DeviceLinkPreview {
        guard let row = try await api.request("sync/device-links/\(code.id)/\(action)", body: ["secret": code.secret]) as? [String: Any] else { throw ClientError.invalidResponse }
        let value = try DeviceLinkPreview(row)
        guard value.id == code.id else { throw ClientError.invalidResponse }
        return value
    }
}
@MainActor final class DeviceLinkModel: ObservableObject {
    @Published private(set) var preview: DeviceLinkPreview?
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    @Published private(set) var connectedID: String?
    private var code: DeviceLinkCode?
    private var deadline: TimeInterval = 0
    private var generation = UUID()
    private let service: DeviceLinkService
    private let authFailure: (Error) -> Void
    private let now: () -> TimeInterval
    init(service: DeviceLinkService, authFailure: @escaping (Error) -> Void = { _ in }, now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.service = service; self.authFailure = authFailure; self.now = now
    }
    var canConfirm: Bool { !busy && preview != nil && code != nil && now() < deadline && connectedID == nil }
    func clear() { generation = UUID(); code = nil; preview = nil; error = nil; connectedID = nil; deadline = 0; busy = false }
    func expire() {
        guard preview != nil, now() >= deadline else { return }
        clear(); error = String(localized: "连接码已过期，请在桌面刷新后重新扫描。")
    }
    func scan(_ input: String) async {
        guard !busy else { return }
        clear()
        let attempt = generation, started = now()
        busy = true
        defer { if attempt == generation { busy = false } }
        do {
            let candidate = try DeviceLinkCode(input, server: service.linkServer)
            let result = try await service.inspectLink(candidate)
            try Task.checkCancellation()
            guard generation == attempt else { return }
            guard result.id == candidate.id else { throw ClientError.invalidResponse }
            code = candidate; preview = result; deadline = started + result.expiresIn
            expire()
        } catch { if generation == attempt { report(error) } }
    }
    func confirm() async {
        expire()
        guard canConfirm, let code, let initial = preview else { return }
        let attempt = generation, started = now()
        busy = true; error = nil
        defer { if attempt == generation { busy = false } }
        do {
            let result: DeviceLinkPreview
            do { result = try await service.confirmLink(code) }
            catch {
                try Task.checkCancellation()
                guard generation == attempt else { return }
                if error is AuthenticationError { throw error }
                // A lost response is reconciled by inspection, never by replaying confirmation.
                let readback = try await service.inspectLink(code)
                guard readback.confirmed else { throw error }
                result = readback
            }
            try Task.checkCancellation()
            guard generation == attempt else { return }
            guard result.id == code.id, result.deviceID == initial.deviceID, result.epoch == initial.epoch,
                  result.confirmed, now() < min(deadline, started + result.expiresIn) else { throw ClientError.invalidResponse }
            connectedID = result.deviceID
        } catch { if generation == attempt { report(error) } }
    }
    private func report(_ failure: Error) {
        guard !(failure is CancellationError), (failure as? URLError)?.code != .cancelled else { return }
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}
