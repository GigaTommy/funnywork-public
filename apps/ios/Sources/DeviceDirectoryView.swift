import SwiftUI

@MainActor final class DeviceDirectory: ObservableObject {
    @Published private(set) var devices: [CreationDevice] = []
    @Published private(set) var loading = false
    @Published private(set) var changing = false
    @Published private(set) var fresh = false
    @Published private(set) var error: String?
    private var generation = UUID()
    private let service: NewConversationService
    private let authFailure: (Error) -> Void
    init(service: NewConversationService, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.service = service; self.authFailure = authFailure
    }
    func deactivate() { generation = UUID(); fresh = false }
    func refresh(userInitiated: Bool = false) async {
        guard !loading, !changing else { return }
        let attempt = generation
        loading = true
        if userInitiated { error = nil }
        defer { loading = false }
        do {
            let result = try await service.devices()
            try Task.checkCancellation()
            guard generation == attempt else { return }
            guard Set(result.map(\.id)).count == result.count else { throw ClientError.invalidResponse }
            devices = result.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            fresh = true; error = nil
        } catch {
            guard generation == attempt else { return }
            fresh = false
            guard !(error is CancellationError), (error as? URLError)?.code != .cancelled else { return }
            self.error = ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
        }
    }
    func canCreate(_ device: CreationDevice) -> Bool { fresh && !changing && device.online && !device.revoked && !device.workspaces.isEmpty }
    var supportsManagement: Bool { service is DeviceManagementService }
    func canManage(_ device: CreationDevice) -> Bool {
        fresh && !changing && supportsManagement && device.accessRevision != nil && devices.contains { $0.id == device.id && $0.accessRevision == device.accessRevision && $0.revoked == device.revoked }
    }
    func changeAccess(_ device: CreationDevice) async {
        guard canManage(device), let revision = device.accessRevision, let management = service as? DeviceManagementService else { return }
        generation = UUID()
        let attempt = generation
        changing = true; fresh = false; error = nil
        defer { changing = false }
        var operationError: Error?
        do {
            try await management.setRevoked(!device.revoked, deviceID: device.id, revision: revision)
            try Task.checkCancellation()
        } catch { operationError = error }
        guard attempt == generation, !Task.isCancelled else { return }
        if let failure = operationError as? AuthenticationError { authFailure(failure); return }
        // Read the current state even when the response was lost. Never replay the mutation.
        do {
            let result = try await service.devices()
            try Task.checkCancellation()
            guard attempt == generation else { return }
            guard Set(result.map(\.id)).count == result.count else { throw ClientError.invalidResponse }
            devices = result.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            fresh = true
            if let operationError { error = ClientError.displayMessage(for: operationError) }
        } catch {
            guard attempt == generation, !Task.isCancelled else { return }
            self.error = String(localized: "暂时无法确认结果，请刷新设备状态。")
            if error is AuthenticationError { authFailure(error) }
        }
    }
}

struct DeviceDirectoryView: View {
    @StateObject private var model: DeviceDirectory
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirming: CreationDevice?
    @State private var workspaceDevice: CreationDevice?
    private let workspaceService: RemoteWorkspaceService?
    private let directoryService: NewConversationService
    @State private var scanning = false
    @State private var linkedDeviceID: String?
    private let linkService: DeviceLinkService?
    private let authFailure: (Error) -> Void
    let create: (String) -> Void
    init(service: NewConversationService, authFailure: @escaping (Error) -> Void, create: @escaping (String) -> Void) {
        self.workspaceService = service as? RemoteWorkspaceService; self.directoryService = service
        _model = StateObject(wrappedValue: DeviceDirectory(service: service, authFailure: authFailure)); self.create = create
        self.linkService = service as? DeviceLinkService; self.authFailure = authFailure
    }
    var body: some View {
        NavigationStack {
            List {
                if linkService != nil {
                    Section { Button { scanning = true } label: { Label("连接设备", systemImage: "qrcode.viewfinder") }.accessibilityIdentifier("connect-device") }.listRowBackground(Theme.input)
                }
                if let error = model.error {
                    Section {
                        Label(error, systemImage: "exclamationmark.circle").font(.callout).foregroundStyle(Theme.errorStatus)
                        Button("重试") { Task { await model.refresh(userInitiated: true) } }.disabled(model.loading || model.changing)
                    }.listRowBackground(Theme.input)
                }
                if model.devices.isEmpty && !model.loading && model.error == nil {
                    ContentUnavailableView("还没有设备", systemImage: "desktopcomputer", description: Text("在电脑上登录同一账号，并开启同步。"))
                        .listRowBackground(Color.clear)
                }
                ForEach(model.devices) { device in
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Label(device.name, systemImage: "desktopcomputer").font(.headline)
                            let state = RemoteConnectionState.device(device, fresh: model.fresh, checking: model.loading, failed: model.error != nil)
                            StatusIndicator(title: state.title, style: state.style).font(.subheadline)
                            if let platform = device.platform { Text(platform == "darwin" ? "macOS" : platform == "win32" ? "Windows" : platform).font(.caption).foregroundStyle(.secondary) }
                            if let date = device.lastSeen {
                                LabeledContent("最近连接") { Text(date, format: .dateTime.month().day().hour().minute()) }.font(.caption)
                            }
                        }
                        if model.fresh && device.online && !device.revoked {
                            if workspaceService != nil && device.choices?.workspaceOperations == true { Button("管理工作区") { workspaceDevice = device } }
                            ForEach(device.workspaces) { workspace in Label(workspace.title, systemImage: "folder").font(.callout) }
                            if device.workspaces.isEmpty { Text("没有可用工作区，请在桌面检查工作区与客户端版本。").font(.callout) }
                            Button("新对话") { create(device.id); dismiss() }
                                .disabled(!model.canCreate(device)).accessibilityIdentifier("device-create-\(device.id)")
                        }
                        if model.supportsManagement && device.accessRevision != nil {
                            Button(device.revoked ? String(localized: "恢复设备") : String(localized: "停用设备")) { confirming = device }
                                .disabled(!model.canManage(device)).accessibilityIdentifier("device-access-\(device.id)")
                        }
                    }.listRowBackground(Theme.input)
                }
                Section { Text("在电脑上登录同一账号，并开启同步。").font(.callout).foregroundStyle(.secondary) }
                    .listRowBackground(Color.clear)
                if model.loading || model.changing { ProgressView().listRowBackground(Color.clear) }
            }.scrollContentBackground(.hidden).background(Theme.surface)
                .navigationTitle("设备").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("完成") { dismiss() } }
                .alert(confirming?.revoked == true ? String(localized: "恢复设备？") : String(localized: "停用设备？"), isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }), presenting: confirming) { device in
                    Button(device.revoked ? String(localized: "恢复") : String(localized: "停用"), role: device.revoked ? nil : .destructive) {
                        Task { await model.changeAccess(device) }
                    }
                    Button("取消", role: .cancel) { }
                } message: { device in
                    Text(device.name + "\n" + (device.revoked ? String(localized: "恢复后，请在电脑上重新连接。") : String(localized: "停用后将暂停同步和远程操作。")))
                }
                .refreshable { await model.refresh(userInitiated: true) }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { model.deactivate(); return }
                    repeat {
                        await model.refresh()
                        do { try await Task.sleep(for: .seconds(5)) } catch { return }
                    } while !Task.isCancelled
                }
                .sheet(item: $workspaceDevice) { device in
                    if let workspaceService { RemoteWorkspaceView(device: device, service: workspaceService, directory: directoryService, authFailure: authFailure) }
                }
                .sheet(isPresented: $scanning, onDismiss: {
                    if let id = linkedDeviceID { linkedDeviceID = nil; create(id); dismiss() }
                }) {
                    if let linkService { DeviceLinkView(service: linkService, authFailure: authFailure) { linkedDeviceID = $0 } }
                }
                .onDisappear { model.deactivate() }
        }
    }
}
