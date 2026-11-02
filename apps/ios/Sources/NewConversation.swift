import Foundation
import CoreFoundation
import Combine

struct CreationModelChoice: Codable, Equatable, Identifiable {
    let provider: String
    let model: String
    let name: String
    var id: String { provider + "\n" + model }
}
struct CreationPresetChoice: Codable, Equatable, Identifiable {
    let id: String
    let name: String
}
struct CreationChoices: Codable, Equatable {
    let workspaceOperations: Bool?
    let models: [CreationModelChoice]
    let presets: [CreationPresetChoice]
    init(_ row: [String: Any]) throws {
        workspaceOperations = row["workspaceOperations"] as? Bool
        guard let models = row["models"] as? [[String: String]], models.count <= 1024,
              let presets = row["presets"] as? [[String: String]], presets.count <= 1024 else { throw ClientError.invalidResponse }
        func text(_ value: String?) -> Bool {
            guard let value else { return false }
            return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.count <= 256 && !value.contains("\n") && !value.contains("\r")
        }
        var modelIDs = Set<String>(); var presetIDs = Set<String>()
        self.models = try models.map {
            guard text($0["provider"]), text($0["model"]), text($0["name"]) else { throw ClientError.invalidResponse }
            let value = CreationModelChoice(provider: $0["provider"]!, model: $0["model"]!, name: $0["name"]!)
            guard modelIDs.insert(value.id).inserted else { throw ClientError.invalidResponse }; return value
        }
        self.presets = try presets.map {
            guard text($0["id"]), text($0["name"]), presetIDs.insert($0["id"]!).inserted else { throw ClientError.invalidResponse }
            return CreationPresetChoice(id: $0["id"]!, name: $0["name"]!)
        }
    }
}
struct CreationWorkspace: Identifiable, Codable, Equatable {
    let id: String
    let title: String
}
struct CreationDevice: Identifiable, Codable, Equatable {
    let id: String
    let name: String
    let online: Bool
    let epoch: String
    let platform: String?
    let lastSeen: Date?
    let revoked: Bool
    let accessRevision: Int64?
    let workspaces: [CreationWorkspace]
    let choices: CreationChoices?
    static func validID(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 64 && value.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }
    init(_ row: [String: Any]) throws {
        guard let id = row["id"] as? String, Self.validID(id), let name = row["name"] as? String,
              let online = row["online"] as? Bool, let epoch = row["epoch"] as? String,
              !online || Self.validID(epoch), let rows = row["workspaces"] as? [[String: Any]], rows.count <= 1000 else { throw ClientError.invalidResponse }
        var found = Set<String>()
        workspaces = try rows.map {
            guard let id = $0["id"] as? String, Self.validID(id), found.insert(id).inserted,
                  let title = $0["title"] as? String else { throw ClientError.invalidResponse }
            return CreationWorkspace(id: id, title: title)
        }
        choices = try (row["choices"] as? [String: Any]).flatMap { $0.isEmpty ? nil : try CreationChoices($0) }
        platform = row["platform"] as? String
        lastSeen = (row["lastSeen"] as? NSNumber).flatMap { $0.doubleValue > 0 ? Date(timeIntervalSince1970: $0.doubleValue / 1000) : nil }
        if let value = row["accessRevision"] {
            guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue >= 0, number.doubleValue <= 9007199254740991,
                  number.doubleValue == Double(number.int64Value) else { throw ClientError.invalidResponse }
            accessRevision = number.int64Value
        } else { accessRevision = nil }
        revoked = row["revoked"] as? Bool ?? false
        self.id = id; self.name = remoteHostName(name, deviceID: id); self.online = online && !revoked; self.epoch = epoch
    }
}
struct CreationRequest: Codable, Equatable {
    let id: String
    let deviceID: String
    let deviceName: String
    let epoch: String
    let workspaceID: String
    let createdAt: Date
    var model: CreationModelChoice? = nil
    var agentPreset: String? = nil
    init(device: CreationDevice, workspaceID: String, model: CreationModelChoice? = nil, agentPreset: String? = nil, now: Date = Date()) {
        id = UUID().uuidString; deviceID = device.id; deviceName = device.name
        epoch = device.epoch; self.workspaceID = workspaceID; createdAt = now
        self.model = model; self.agentPreset = agentPreset
    }
    var body: [String: Any] {
        var payload: [String: Any] = ["workspaceId": workspaceID]
        if let model { payload["model"] = ["provider": model.provider, "model": model.model] }
        if let agentPreset { payload["agentPreset"] = agentPreset }
        return ["id": id, "epoch": epoch, "kind": "create", "payload": payload]
    }
}
struct CreationReceipt {
    let id: String
    let status: String
    let sessionID: String?
    init(_ row: [String: Any]) throws {
        guard let id = row["id"] as? String, CreationDevice.validID(id),
              let status = row["status"] as? String,
              ["missing", "queued", "claimed", "accepted", "rejected", "expired", "uncertain"].contains(status) else { throw ClientError.invalidResponse }
        let session = (row["result"] as? [String: Any])?["sessionId"] as? String
        guard status != "accepted" || session.map(CreationDevice.validID) == true else { throw ClientError.invalidResponse }
        self.id = id; self.status = status; self.sessionID = session
    }
}
struct CreationTarget: Codable, Equatable {
    let deviceID: String
    let workspaceID: String
}
struct CreationDraftSelection: Codable, Equatable {
    let deviceID: String
    let workspaceID: String
    let modelID: String
    let presetID: String
}
struct SavedCreation: Codable {
    var pending: CreationRequest?
    var recentTarget: CreationTarget? = nil
    var draft: String? = nil
    var pendingDraft: String? = nil
    var pendingSourceChatID: String? = nil
    var selection: CreationDraftSelection? = nil
}
@MainActor protocol NewConversationService {
    func devices() async throws -> [CreationDevice]
    func submit(_ request: CreationRequest) async throws -> CreationReceipt
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt
}
@MainActor protocol DeviceManagementService {
    func setRevoked(_ revoked: Bool, deviceID: String, revision: Int64) async throws
}
@MainActor struct HTTPNewConversationService: NewConversationService, DeviceManagementService {
    let api: API
    func devices() async throws -> [CreationDevice] {
        var result: [CreationDevice] = []; var cursor = ""
        while true {
            guard let rows = try await api.request("sync/devices?after=\(cursor)") as? [[String: Any]], rows.count <= 100 else { throw ClientError.invalidResponse }
            let page = try rows.map(CreationDevice.init)
            for device in page {
                guard device.id > cursor else { throw ClientError.invalidResponse }
                result.append(device); cursor = device.id
            }
            if rows.count < 100 { return result }
            try Task.checkCancellation()
        }
    }
    func setRevoked(_ revoked: Bool, deviceID: String, revision: Int64) async throws {
        guard CreationDevice.validID(deviceID), revision >= 0, revision <= 9007199254740991 else { throw ClientError.invalidResponse }
        let action = revoked ? "revoke" : "restore"
        guard let row = try await api.request("sync/devices/\(deviceID)/\(action)", body: ["revision": revision]) as? [String: Any], row["ok"] as? Bool == true else { throw ClientError.invalidResponse }
    }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt {
        guard let row = try await api.request("sync/devices/\(request.deviceID)/commands", body: request.body) as? [String: Any] else { throw ClientError.invalidResponse }
        return try CreationReceipt(row)
    }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt {
        guard let row = try await api.request("sync/devices/\(request.deviceID)/commands/\(request.id)") as? [String: Any] else { throw ClientError.invalidResponse }
        return try CreationReceipt(row)
    }
}
@MainActor final class NewConversationModel: ObservableObject {
    @Published private(set) var devices: [CreationDevice] = []
    @Published var deviceID = ""
    @Published var workspaceID = ""
    @Published var modelID = ""
    @Published var presetID = ""
    @Published private(set) var pending: CreationRequest?
    @Published private(set) var status = ""
    @Published private(set) var loading = false
    @Published private(set) var sending = false
    @Published private(set) var ready = false
    @Published private(set) var presenceFresh = false
    @Published private(set) var created: Conversation?
    @Published var error: String?
    @Published private(set) var draft = ""
    private var pendingDraft: String?
    private let sourceChatID: String?
    private var storageReady = true
    private var recentTarget: CreationTarget?
    private var draftSelection: CreationDraftSelection?
    private var resolvedInitialSelection = false
    private let preferredDeviceID: String?
    private var workspaceDeviceID = ""
    private let service: NewConversationService
    private let store: ConversationStore
    private let authFailure: (Error) -> Void
    init(service: NewConversationService, store: ConversationStore, preferredDeviceID: String? = nil, sourceChatID: String? = nil, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.store = store; self.authFailure = authFailure
        self.preferredDeviceID = preferredDeviceID
        self.sourceChatID = sourceChatID
        do {
            let saved = try store.loadCreation()
            pending = saved?.pending; recentTarget = saved?.recentTarget
            draft = saved?.draft ?? ""; pendingDraft = saved?.pendingDraft
            draftSelection = saved?.selection
        }
        catch { storageReady = false; self.error = String(localized: "无法读取创建记录，请重新打开。") }
    }
    var submittedDraft: String { pendingDraft ?? "" }
    var selected: CreationDevice? { devices.first { $0.id == deviceID } }
    var choices: CreationChoices? { selected?.workspaces.contains { $0.id == workspaceID } == true ? selected?.choices : nil }
    var selectedModel: CreationModelChoice? { choices?.models.first { $0.id == modelID } }
    var validChoices: Bool {
        (modelID.isEmpty || selectedModel != nil) && (presetID.isEmpty || choices?.presets.contains { $0.id == presetID } == true)
    }
    func resetChoices() { modelID = ""; presetID = "" }
    var canCreate: Bool {
        draft.utf16.count <= 16000 && validChoices && ready && storageReady && !sending && pending == nil && created == nil && selected?.online == true && selected?.workspaces.contains(where: { $0.id == workspaceID }) == true
    }
    var canRetry: Bool {
        guard ready, storageReady, !sending, status == "missing", let pending,
              Date().timeIntervalSince(pending.createdAt) >= 0, Date().timeIntervalSince(pending.createdAt) < 25,
              let device = devices.first(where: { $0.id == pending.deviceID }) else { return false }
        guard device.online, device.epoch == pending.epoch,
              device.workspaces.contains(where: { $0.id == pending.workspaceID }) else { return false }
        if let model = pending.model, device.choices?.models.contains(where: { $0.provider == model.provider && $0.model == model.model }) != true { return false }
        if let preset = pending.agentPreset, device.choices?.presets.contains(where: { $0.id == preset }) != true { return false }
        return true
    }
    var canClear: Bool {
        guard !sending, let pending else { return false }
        return ["rejected", "expired", "uncertain"].contains(status) || Date().timeIntervalSince(pending.createdAt) >= 30
    }
    func selectDevice(preserveInvalid: Bool = false) {
        let changedDevice = workspaceDeviceID != deviceID
        workspaceDeviceID = deviceID
        if changedDevice {
            resetChoices()
            if let recentTarget, recentTarget.deviceID == deviceID,
               selected?.workspaces.contains(where: { $0.id == recentTarget.workspaceID }) == true {
                workspaceID = recentTarget.workspaceID
            } else {
                workspaceID = selected?.workspaces.count == 1 ? selected!.workspaces[0].id : ""
            }
        } else if !preserveInvalid && selected?.workspaces.contains(where: { $0.id == workspaceID }) != true {
            workspaceID = ""
        }
    }
    func refresh() async {
        guard !loading else { return }
        loading = true; ready = false
        defer { loading = false }
        do {
            let current = try await service.devices()
            try Task.checkCancellation()
            devices = current; ready = true; presenceFresh = true
            if !resolvedInitialSelection {
                resolvedInitialSelection = true
                if let selection = draftSelection, preferredDeviceID == nil {
                    deviceID = selection.deviceID; workspaceDeviceID = selection.deviceID
                    workspaceID = selection.workspaceID; modelID = selection.modelID; presetID = selection.presetID
                } else if let preferredDeviceID {
                    deviceID = current.first { $0.id == preferredDeviceID }?.id ?? ""
                } else if let recentTarget {
                    // An unavailable remembered location must not silently switch hosts.
                    if let device = current.first(where: { $0.id == recentTarget.deviceID }) {
                        deviceID = device.id
                        workspaceDeviceID = device.id
                        workspaceID = device.workspaces.contains { $0.id == recentTarget.workspaceID } ? recentTarget.workspaceID : ""
                    }
                } else if current.filter({ $0.online && !$0.workspaces.isEmpty }).count == 1 {
                    deviceID = current.first { $0.online && !$0.workspaces.isEmpty }!.id
                } else if !current.contains(where: { $0.online && !$0.workspaces.isEmpty }) {
                    resolvedInitialSelection = false
                }
            }
            if draftSelection == nil, !current.contains(where: { $0.id == deviceID }) { deviceID = "" }
            selectDevice(preserveInvalid: draftSelection != nil)
            if let pending { let receipt = try await service.receipt(pending); try reconcile(receipt, request: pending) }
            if storageReady { error = nil }
        } catch { ready = false; presenceFresh = false; report(error) }
    }
    private var currentDraftSelection: CreationDraftSelection {
        CreationDraftSelection(deviceID: deviceID, workspaceID: workspaceID, modelID: modelID, presetID: presetID)
    }
    private func persistDraftSelection(forceSelection: Bool = false) {
        do {
            var saved = try store.loadCreation() ?? SavedCreation(pending: nil)
            saved.draft = draft; saved.selection = forceSelection || !deviceID.isEmpty || draftSelection != nil ? currentDraftSelection : nil
            try store.saveCreation(saved); draftSelection = saved.selection
        } catch { report(error); storageReady = false }
    }
    func updateDraft(_ text: String) { draft = text; persistDraftSelection() }
    func chooseDevice(_ id: String) { deviceID = id; selectDevice(); persistDraftSelection(forceSelection: true) }
    func chooseWorkspace(_ id: String) { workspaceID = id; resetChoices(); persistDraftSelection(forceSelection: true) }
    func chooseModel(_ id: String) { modelID = id; persistDraftSelection(forceSelection: true) }
    func choosePreset(_ id: String) { presetID = id; persistDraftSelection(forceSelection: true) }
    var createDisabledReason: String? {
        if !storageReady { return String(localized: "无法保存任务草稿，请重新打开。") }
        if sending { return String(localized: "正在创建，请稍候。") }
        if !ready { return loading ? String(localized: "正在核对执行位置。") : String(localized: "执行位置尚未确认，请重试。") }
        if deviceID.isEmpty { return String(localized: "请选择设备。") }
        if selected == nil { return String(localized: "所选设备暂不可用，请重新选择。") }
        if selected?.online != true { return String(localized: "此电脑离线，草稿已保留。") }
        if workspaceID.isEmpty { return String(localized: "请选择工作区。") }
        if selected?.workspaces.contains(where: { $0.id == workspaceID }) != true { return String(localized: "所选工作区暂不可用，请重新选择。") }
        if !validChoices { return String(localized: "所选模型或预设已不可用，请重新选择。") }
        if draft.utf16.count > 16000 { return String(localized: "内容过长，请缩短后继续。") }
        return nil
    }
    func deactivate() { ready = false; presenceFresh = false }
    func create() async {
        guard canCreate, let selected else { return }
        let request = CreationRequest(device: selected, workspaceID: workspaceID, model: selectedModel, agentPreset: presetID.isEmpty ? nil : presetID)
        do { try store.saveCreation(SavedCreation(pending: request, recentTarget: recentTarget, draft: draft, pendingDraft: draft, pendingSourceChatID: sourceChatID, selection: currentDraftSelection)); pending = request; pendingDraft = draft; status = "" }
        catch { report(error); return }
        await transmit(request)
    }
    func retry() async {
        guard !sending, let original = pending else { return }
        await refresh()
        guard canRetry, pending?.id == original.id else { return }
        await transmit(original)
    }
    private func transmit(_ request: CreationRequest) async {
        sending = true
        defer { sending = false }
        do { let receipt = try await service.submit(request); try reconcile(receipt, request: request); error = nil }
        catch { report(error) }
    }
    private func reconcile(_ value: CreationReceipt, request: CreationRequest) throws {
        try Task.checkCancellation()
        guard value.id == request.id else { throw ClientError.invalidResponse }
        guard pending?.id == request.id else { return }
        status = value.status
        if value.status == "accepted", let id = value.sessionID {
            guard let saved = try store.loadCreation(), saved.pending?.id == request.id else {
                pending = try store.loadCreation()?.pending; status = ""; return
            }
            guard let accepted = Conversation(["id": id, "title": String(localized: "未命名对话"), "device_id": request.deviceID, "device_name": request.deviceName, "online": true]) else { throw ClientError.invalidResponse }
            if let text = pendingDraft, !text.isEmpty {
                var conversation = try store.load(id)
                if conversation.seededCreationID != request.id {
                    conversation.draft = ConversationModel.appendingDraft(text, to: conversation.draft)
                    conversation.seededCreationID = request.id
                    try store.save(conversation, for: id)
                }
            }
            // Persist the association before clearing the receipt: interrupted recovery remains idempotent.
            if let chatID = saved.pendingSourceChatID { try store.linkResultTask(accepted, sourceChatID: chatID) }
            guard try store.clearCreation(matching: request.id, recentTarget: CreationTarget(deviceID: request.deviceID, workspaceID: request.workspaceID)) else {
                pending = try store.loadCreation()?.pending; status = ""
                return
            }
            pending = nil
            created = accepted
        }
    }
    func clearAfterReview() {
        guard canClear, let request = pending else { return }
        do {
            _ = try store.clearCreation(matching: request.id)
            pending = try store.loadCreation()?.pending; status = ""; error = nil
        }
        catch { report(error) }
    }
    private func report(_ failure: Error) {
        if failure is CancellationError || (failure as? URLError)?.code == .cancelled { return }
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
}
