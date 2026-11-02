import Foundation
import Combine
import CoreFoundation

struct ForkRequest: Codable, Equatable {
    let id: String
    let sourceID: String
    let deviceID: String
    let epoch: String
    let sequence: Int
    let createdAt: Date
    init(source: Conversation, epoch: String, sequence: Int, now: Date = Date()) {
        id = UUID().uuidString; sourceID = source.id; deviceID = source.deviceID
        self.epoch = epoch; self.sequence = sequence; createdAt = now
    }
    func valid(for source: Conversation) -> Bool {
        [id, sourceID, deviceID, epoch].allSatisfy(CreationDevice.validID)
            && sourceID == source.id && deviceID == source.deviceID && sequence >= 0 && sequence <= 9007199254740991
            && createdAt.timeIntervalSince1970.isFinite
    }
    var body: [String: Any] { ["id": id, "epoch": epoch, "kind": "fork", "payload": ["atSeq": sequence]] }
}
struct SavedFork: Codable { var pending: ForkRequest? }
@MainActor protocol ForkConversationService {
    func forkSnapshot() async throws -> RemoteSnapshot
    func submitFork(_ request: ForkRequest) async throws -> CreationReceipt
    func forkReceipt(_ request: ForkRequest) async throws -> CreationReceipt?
    func forkDestination(_ id: String) async throws -> Conversation
}
extension HTTPConversationService: ForkConversationService {
    func forkSnapshot() async throws -> RemoteSnapshot { try await snapshot() }
    func submitFork(_ request: ForkRequest) async throws -> CreationReceipt {
        guard request.sourceID == sessionID, CreationDevice.validID(sessionID),
              let row = try await api.request("sync/sessions/\(sessionID)/commands", body: request.body) as? [String: Any] else { throw ClientError.invalidResponse }
        return try CreationReceipt(row)
    }
    func forkReceipt(_ request: ForkRequest) async throws -> CreationReceipt? {
        guard request.sourceID == sessionID, CreationDevice.validID(sessionID), CreationDevice.validID(request.id) else { throw ClientError.invalidResponse }
        let value = try await api.request("sync/sessions/\(sessionID)/commands/\(request.id)", allowMissing: true)
        if value is NSNull { return nil }
        guard let row = value as? [String: Any], row["kind"] as? String == "fork" else { throw ClientError.invalidResponse }
        return try CreationReceipt(row)
    }
    func forkDestination(_ id: String) async throws -> Conversation {
        guard CreationDevice.validID(id), let row = try await api.request("sync/sessions/\(id)") as? [String: Any],
              let result = Conversation(row), result.id == id else { throw ClientError.invalidResponse }
        return result
    }
}
@MainActor final class ForkConversationModel: ObservableObject {
    @Published private(set) var pending: ForkRequest?
    @Published private(set) var status = ""
    @Published private(set) var busy = false
    @Published private(set) var canRetry = false
    @Published private(set) var created: Conversation?
    @Published var error: String?
    let supported: Bool
    private let source: Conversation
    private let service: ForkConversationService?
    private let store: ConversationStore
    private let authFailure: (Error) -> Void
    private var generation = UUID()
    init(source: Conversation, service: ForkConversationService?, store: ConversationStore, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.source = source; self.service = service; self.store = store; self.authFailure = authFailure; supported = service != nil
        do {
            pending = try store.loadFork(source.id)?.pending
            if let pending, !pending.valid(for: source) { throw ClientError.invalidResponse }
        } catch { self.error = ClientError.displayMessage(for: error) }
    }
    func activate() { created = nil; generation = UUID() }
    func deactivate() { generation = UUID(); canRetry = false }
    func create(at sequence: Int) async {
        guard pending == nil, !busy, sequence >= 0, sequence <= 9007199254740991, let service else { return }
        let attempt = generation; busy = true; error = nil
        defer { busy = false }
        do {
            try store.requireActive()
            let snapshot = try await service.forkSnapshot()
            guard attempt == generation, !Task.isCancelled else { return }
            try store.requireActive()
            guard snapshot.online, snapshot.sessionForks else { throw ClientError(message: String(localized: "暂时无法创建分支，请刷新后重试。")) }
            let request = ForkRequest(source: source, epoch: snapshot.epoch, sequence: sequence)
            guard request.valid(for: source) else { throw ClientError.invalidResponse }
            if let previous = try store.loadFork(source.id)?.pending { pending = previous; return }
            try store.saveFork(SavedFork(pending: request), sourceID: source.id)
            pending = request
            let receipt = try await service.submitFork(request)
            guard attempt == generation, !Task.isCancelled else { return }
            try await accept(receipt, request: request, attempt: attempt)
        } catch { if attempt == generation { report(error) } }
    }
    func refresh() async {
        guard !busy, let request = pending, let service else { return }
        let attempt = generation; busy = true; canRetry = false; error = nil
        defer { busy = false }
        do {
            try store.requireActive()
            guard request.valid(for: source) else { throw ClientError.invalidResponse }
            let receipt = try await service.forkReceipt(request)
            guard attempt == generation, !Task.isCancelled else { return }
            if let receipt { try await accept(receipt, request: request, attempt: attempt) }
            else {
                status = "missing"
                let snapshot = try await service.forkSnapshot()
                guard attempt == generation, !Task.isCancelled else { return }
                try store.requireActive()
                let age = Date().timeIntervalSince(request.createdAt)
                canRetry = snapshot.online && snapshot.sessionForks && snapshot.epoch == request.epoch && age >= 0 && age < 30
            }
        } catch { if attempt == generation { report(error) } }
    }
    func retry() async {
        guard canRetry, !busy, let request = pending, let service else { return }
        // Recheck absence and the exact runtime before a user-requested resubmission.
        await refresh()
        guard canRetry, pending?.id == request.id, !busy else { return }
        let attempt = generation; busy = true; canRetry = false
        defer { busy = false }
        do {
            let receipt = try await service.submitFork(request)
            guard attempt == generation, !Task.isCancelled else { return }
            try await accept(receipt, request: request, attempt: attempt)
        } catch { if attempt == generation { report(error) } }
    }
    private func accept(_ receipt: CreationReceipt, request: ForkRequest, attempt: UUID) async throws {
        guard receipt.id == request.id else { throw ClientError.invalidResponse }
        try store.requireActive(); status = receipt.status; error = nil
        guard receipt.status == "accepted", let id = receipt.sessionID, let service else { return }
        let destination = try await service.forkDestination(id)
        guard attempt == generation, !Task.isCancelled else { return }
        try store.requireActive()
        guard destination.id != source.id, destination.deviceID == source.deviceID,
              destination.nextSequence.map({ $0 > request.sequence }) == true else { throw ClientError.invalidResponse }
        guard try store.clearFork(source.id, matching: request.id) else {
            pending = try store.loadFork(source.id)?.pending; return
        }
        pending = nil; created = destination
    }
    func clearAfterReview() {
        guard !busy, let request = pending else { return }
        do { _ = try store.clearFork(source.id, matching: request.id); pending = try store.loadFork(source.id)?.pending; status = ""; error = nil; canRetry = false }
        catch { report(error) }
    }
    private func report(_ failure: Error) {
        if failure is CancellationError || (failure as? URLError)?.code == .cancelled { return }
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}
