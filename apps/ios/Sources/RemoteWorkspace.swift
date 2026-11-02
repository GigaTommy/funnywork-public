import SwiftUI
import CryptoKit

@MainActor protocol RemoteWorkspaceService {
    func submitWorkspace(device: CreationDevice, id: String, payload: [String: Any]) async throws -> [String: Any]
    func workspaceReceipt(deviceID: String, id: String) async throws -> [String: Any]
    var workspaceCacheKey: String { get }
}
extension HTTPNewConversationService: RemoteWorkspaceService {
    var workspaceCacheKey: String {
        let identity = api.credentials.server.absoluteString + "\n" + (api.credentials.principalID ?? api.credentials.token) + "\n" + (api.credentials.tenant ?? "")
        return "workspace-command-" + SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    func submitWorkspace(device: CreationDevice, id: String, payload: [String: Any]) async throws -> [String: Any] {
        guard let row = try await api.request("sync/devices/\(device.id)/commands", body: ["id": id, "epoch": device.epoch, "kind": "workspace", "payload": payload]) as? [String: Any] else { throw ClientError.invalidResponse }
        return row
    }
    func workspaceReceipt(deviceID: String, id: String) async throws -> [String: Any] {
        guard CreationDevice.validID(deviceID), CreationDevice.validID(id), let row = try await api.request("sync/devices/\(deviceID)/commands/\(id)") as? [String: Any] else { throw ClientError.invalidResponse }
        return row
    }
}
struct WorkspaceFile: Identifiable {
    let name: String
    let directory: Bool
    let symbolic: Bool
    var id: String { name }
}
struct WorkspaceTree: Identifiable {
    let path: String
    let branch: String
    let removable: Bool
    var id: String { path }
}
@MainActor final class RemoteWorkspaceModel: ObservableObject {
    @Published var device: CreationDevice
    @Published var workspaceID = ""
    @Published var path = ""
    @Published var files: [WorkspaceFile] = []
    @Published var after = ""
    @Published var editingPath: String?
    @Published var content = ""
    @Published var revision = ""
    @Published var savedContent = ""
    var hasUnsavedChanges: Bool { editingPath != nil && (revision == "new" || content != savedContent) }
    @Published var branches: [String] = []
    @Published var currentBranch = ""
    @Published var gitStatus = ""
    @Published var trees: [WorkspaceTree] = []
    @Published private(set) var busy = false
    @Published private(set) var pending = false
    @Published var error: String?
    private let service: RemoteWorkspaceService
    private let directory: NewConversationService
    private let defaults: UserDefaults
    private let authFailure: (Error) -> Void
    private var cacheKey: String { service.workspaceCacheKey + "-" + device.id }
    init(device: CreationDevice, service: RemoteWorkspaceService, directory: NewConversationService, defaults: UserDefaults = .standard, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.device = device; self.service = service; self.directory = directory; self.defaults = defaults; self.authFailure = authFailure
        workspaceID = device.workspaces.first?.id ?? ""
        pending = defaults.string(forKey: service.workspaceCacheKey + "-" + device.id) != nil
    }
    func checkPending() async {
        guard !busy, let id = defaults.string(forKey: cacheKey) else { return }
        busy = true; defer { busy = false }
        do { _ = try await awaitReceipt(id); await refreshCatalog(); error = nil }
        catch { report(error) }
    }
    func clearVerifiedPending() { defaults.removeObject(forKey: cacheKey); pending = false; error = nil }
    private func refreshCatalog(expectedID: String? = nil, removedID: String? = nil) async {
        for attempt in 0..<5 {
            do {
                guard let fresh = try await directory.devices().first(where: { $0.id == device.id }) else { return }
                device = fresh
                let found = expectedID == nil || fresh.workspaces.contains { $0.id == expectedID }
                let removed = removedID == nil || !fresh.workspaces.contains { $0.id == removedID }
                if found && removed { return }
                if attempt < 4 { try await Task.sleep(for: .seconds(1)) }
            } catch { if error is AuthenticationError { authFailure(error) }; return }
        }
    }
    private func report(_ failure: Error) {
        error = ClientError.displayMessage(for: failure)
        if failure is AuthenticationError { authFailure(failure) }
    }
    private func awaitReceipt(_ id: String) async throws -> [String: Any] {
        for _ in 0..<35 {
            try Task.checkCancellation()
            let row = try await service.workspaceReceipt(deviceID: device.id, id: id)
            guard row["id"] as? String == id, let status = row["status"] as? String else { throw ClientError.invalidResponse }
            switch status {
            case "accepted", "rejected", "expired":
                defaults.removeObject(forKey: cacheKey); pending = false
                guard status == "accepted" else {
                    let reason = (row["result"] as? [String: Any])?["error"] as? String
                    let message: String
                    if reason?.contains("File changed") == true { message = String(localized: "文件已被修改，请重新打开后保存。") }
                    else if reason?.contains("outside workspace") == true || reason?.contains("Protected") == true { message = String(localized: "此路径不允许访问，请选择工作区内的文件。") }
                    else if reason?.contains("limit") == true { message = String(localized: "文件超过编辑大小限制。") }
                    else if reason?.contains("Binary") == true || reason?.contains("encoded data") == true { message = String(localized: "此文件不支持文本编辑。") }
                    else { message = String(localized: "电脑未完成操作，请检查路径、Git 状态及是否有未提交文件。") }
                    throw ClientError(message: message)
                }
                guard let result = row["result"] as? [String: Any] else { throw ClientError.invalidResponse }
                return result
            case "uncertain": throw ClientError(message: String(localized: "结果尚未确认，请在电脑检查后再操作。"))
            case "missing":
                // A lost submission may arrive late. Never submit it again automatically.
                throw ClientError(message: String(localized: "未确认命令是否送达，请检查电脑。"))
            case "queued", "claimed": try await Task.sleep(for: .seconds(1))
            default: throw ClientError.invalidResponse
            }
        }
        throw ClientError(message: String(localized: "操作仍在等待，可以检查结果。"))
    }
    func perform(_ action: String, values: [String: Any] = [:]) async -> [String: Any]? {
        guard !busy, !pending else { return nil }
        busy = true; error = nil; defer { busy = false }
        do {
            guard let fresh = try await directory.devices().first(where: { $0.id == device.id }), fresh.online, !fresh.revoked, fresh.choices?.workspaceOperations == true else { throw ClientError(message: String(localized: "电脑已离线，请连接后重试。")) }
            device = fresh
            var payload = values; payload["action"] = action
            if action != "create" { payload["workspaceId"] = workspaceID }
            let id = UUID().uuidString
            defaults.set(id, forKey: cacheKey); pending = true
            do { _ = try await service.submitWorkspace(device: fresh, id: id, payload: payload) }
            catch {
                // Preserve the identity across timeout, navigation and app restart.
                if error is AuthenticationError { throw error }
            }
            let result = try await awaitReceipt(id)
            await refreshCatalog(expectedID: result["workspaceId"] as? String, removedID: action == "delete" ? workspaceID : nil)
            return result
        } catch { report(error); return nil }
    }
    func browse(_ target: String? = nil, more: Bool = false) async {
        let target = target ?? path
        guard let result = await perform("files", values: ["path": target, "after": more ? after : ""]), let entries = result["entries"] as? [[String: Any]], let next = result["after"] as? String else { return }
        let rows = entries.compactMap { row -> WorkspaceFile? in
            guard let name = row["name"] as? String, let directory = row["directory"] as? Bool, let symbolic = row["symbolic"] as? Bool else { return nil }
            return WorkspaceFile(name: name, directory: directory, symbolic: symbolic)
        }
        if rows.count != entries.count { error = ClientError.invalidResponse.message; return }
        path = target; after = next; files = more ? files + rows : rows
    }
    func open(_ file: WorkspaceFile) async {
        let target = path.isEmpty ? file.name : path + "/" + file.name
        if file.directory { await browse(target); return }
        guard let row = await perform("read", values: ["path": target]), let text = row["text"] as? String, let hash = row["revision"] as? String else { return }
        editingPath = target; content = text; savedContent = text; revision = hash
    }
    func save() async {
        guard let editingPath, content.utf8.count <= 64000 else { error = String(localized: "文件超过编辑大小限制。"); return }
        guard let row = await perform("write", values: ["path": editingPath, "text": content, "revision": revision]), let hash = row["revision"] as? String else { return }
        revision = hash; savedContent = content
    }
    func loadGit() async {
        guard let row = await perform("git"), let branches = row["branches"] as? [String], let current = row["current"] as? String, let status = row["status"] as? String, let trees = row["worktrees"] as? [[String: Any]] else { return }
        self.branches = branches; currentBranch = current; gitStatus = status
        self.trees = trees.compactMap { row in guard let path = row["path"] as? String, let branch = row["branch"] as? String else { return nil }; return WorkspaceTree(path: path, branch: branch, removable: row["removable"] as? Bool == true) }
    }
}

struct RemoteWorkspaceView: View {
    @StateObject private var model: RemoteWorkspaceModel
    @Environment(\.dismiss) private var dismiss
    @State private var action = ""
    @State private var input = ""
    @State private var showingInput = false
    @State private var removal: String?
    @State private var tab = 0
    @State private var confirmingResult = false
    @State private var discarding = false
    @State private var deletingBranch: String?
    init(device: CreationDevice, service: RemoteWorkspaceService, directory: NewConversationService, authFailure: @escaping (Error) -> Void) {
        _model = StateObject(wrappedValue: RemoteWorkspaceModel(device: device, service: service, directory: directory, authFailure: authFailure))
    }
    private func ask(_ value: String) { action = value; input = ""; showingInput = true }
    var body: some View {
        NavigationStack {
            List {
                if let error = model.error { Text(error).font(.callout).listRowBackground(Theme.input) }
                if model.pending {
                    Button("检查操作结果") { Task { await model.checkPending() } }.disabled(model.busy)
                    Button("已在电脑确认结果") { confirmingResult = true }.disabled(model.busy)
                }
                Section("工作区") {
                    Picker("工作区", selection: $model.workspaceID) { ForEach(model.device.workspaces) { Text($0.title).tag($0.id) } }.disabled(model.busy || model.pending)
                    HStack {
                        Button("添加") { ask("create") }
                        Button("重命名") { ask("rename") }.disabled(model.workspaceID.isEmpty)
                        Button("移除", role: .destructive) { removal = "workspace" }.disabled(model.workspaceID.isEmpty)
                    }.buttonStyle(.borderless).disabled(model.busy || model.pending)
                }.listRowBackground(Theme.input)
                if !model.workspaceID.isEmpty {
                    Picker("内容", selection: $tab) { Text("文件").tag(0); Text("Git").tag(1) }.pickerStyle(.segmented).disabled(model.busy || model.pending)
                    if tab == 0 { fileSection } else { gitSection }
                }
                if model.busy { ProgressView() }
            }.scrollContentBackground(.hidden).background(Theme.surface)
                .navigationTitle(model.device.name).navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("完成") { dismiss() } }
                .task { if model.pending { await model.checkPending() }; if !model.workspaceID.isEmpty { await model.browse() } }
                .onChange(of: model.workspaceID) { _, _ in model.files = []; model.path = ""; model.after = ""; model.branches = []; model.trees = []; Task { if tab == 0 { await model.browse("") } else { await model.loadGit() } } }
                .onChange(of: tab) { _, value in Task { if value == 0 { await model.browse() } else { await model.loadGit() } } }
                .alert("已核对电脑结果？", isPresented: $confirmingResult) {
                    Button("取消", role: .cancel) { }; Button("已核对") { model.clearVerifiedPending() }
                } message: { Text("解除等待后可以进行新的操作，不会重新执行此前的命令。") }
                .alert(inputTitle, isPresented: $showingInput) {
                    TextField(inputTitle, text: $input).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("取消", role: .cancel) { }
                    Button("确定") { Task { await submitInput() } }.disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } message: { if action == "create" { Text("输入电脑上已有文件夹的完整路径。") } }
                .alert("删除分支？", isPresented: Binding(get: { deletingBranch != nil }, set: { if !$0 { deletingBranch = nil } })) {
                    Button("取消", role: .cancel) { deletingBranch = nil }; Button("删除", role: .destructive) { let branch = deletingBranch; deletingBranch = nil; Task { if let branch { _ = await model.perform("branchDelete", values: ["branch": branch]); await model.loadGit() } } }
                } message: { Text("仅删除已合并的本地分支，不强制删除。") }
                .alert("确认移除？", isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } })) {
                    Button("取消", role: .cancel) { removal = nil }
                    Button("移除", role: .destructive) { let target = removal; removal = nil; Task { if target == "workspace" { if await model.perform("delete") != nil { model.workspaceID = model.device.workspaces.first?.id ?? "" } } else if let target { _ = await model.perform("worktreeRemove", values: ["path": target]); await model.loadGit() } } }
                } message: { Text(removal == "workspace" ? "移除工作区登记，文件和聊天仍保留。" : "移除工作树；存在未提交文件时电脑会拒绝操作。") }
                .sheet(isPresented: Binding(get: { model.editingPath != nil }, set: { if !$0 { model.editingPath = nil } })) { editor }
        }.tint(Theme.accent)
    }
    private var inputTitle: String { switch action { case "create": return String(localized: "添加工作区"); case "rename": return String(localized: "工作区名称"); case "newFile": return String(localized: "文件名"); case "mkdir": return String(localized: "文件夹名称"); case "worktreeAdd": return String(localized: "新工作树分支"); default: return String(localized: "新分支名称") } }
    private var fileSection: some View {
        Section {
            HStack {
                Text(model.path.isEmpty ? "/" : model.path).font(.caption).lineLimit(1)
                Spacer()
                if !model.path.isEmpty { Button("上一级") { Task { await model.browse(model.path.split(separator: "/").dropLast().joined(separator: "/")) } } }
                Button("刷新") { Task { await model.browse() } }
                Menu { Button("新文件") { ask("newFile") }; Button("新文件夹") { ask("mkdir") } } label: { Image(systemName: "plus") }.accessibilityLabel("添加文件")
            }.buttonStyle(.borderless).disabled(model.busy || model.pending)
            ForEach(model.files) { file in Button { Task { await model.open(file) } } label: { Label(file.name, systemImage: file.symbolic ? "link" : file.directory ? "folder" : "doc.text").foregroundStyle(Theme.accent) }.disabled(model.busy || model.pending) }
            if !model.after.isEmpty { Button("加载更多") { Task { await model.browse(more: true) } }.disabled(model.busy || model.pending) }
        }.listRowBackground(Theme.input)
    }
    private var gitSection: some View {
        Section {
            HStack { Text(model.currentBranch.isEmpty ? String(localized: "未选择分支") : model.currentBranch); Spacer(); Button("刷新") { Task { await model.loadGit() } } }.buttonStyle(.borderless)
            HStack { Button("新分支") { ask("branchCreate") }; Button("新工作树") { ask("worktreeAdd") } }.buttonStyle(.borderless)
            ForEach(model.branches, id: \.self) { name in Button { Task { if await model.perform("branchSwitch", values: ["branch": name]) != nil { await model.loadGit() } } } label: { HStack { Text(name); if name == model.currentBranch { Image(systemName: "checkmark") } } }.disabled(name == model.currentBranch).contextMenu {
                Button("从此分支创建工作树") { Task { if await model.perform("worktreeAdd", values: ["branch": name, "newBranch": false]) != nil { await model.loadGit() } } }.disabled(name == model.currentBranch)
                Button("删除分支", role: .destructive) { deletingBranch = name }.disabled(name == model.currentBranch)
            } }
            if !model.gitStatus.isEmpty { Text(model.gitStatus).font(.system(.caption, design: .monospaced)).textSelection(.enabled) }
            ForEach(model.trees) { tree in VStack(alignment: .leading) { Text(tree.branch); Text(tree.path).font(.caption).foregroundStyle(.secondary); if tree.removable { Button("移除工作树", role: .destructive) { removal = tree.path } } } }
        }.disabled(model.busy || model.pending).listRowBackground(Theme.input)
    }
    private var editor: some View {
        NavigationStack {
            VStack {
                if let error = model.error { Text(error).font(.callout).padding() }
                TextEditor(text: $model.content).font(.system(.body, design: .monospaced)).scrollContentBackground(.hidden).background(Theme.surface).disabled(model.busy || model.pending)
                Text("\(model.content.utf8.count) / 64000").font(.caption).foregroundStyle(.secondary)
            }.background(Theme.surface).navigationTitle(model.editingPath ?? "").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { if model.hasUnsavedChanges { discarding = true } else { model.editingPath = nil } }.accessibilityIdentifier("workspace-editor-close") }; ToolbarItem(placement: .confirmationAction) { Button("保存") { Task { await model.save() } }.disabled(model.busy || model.pending || model.content.utf8.count > 64000) } }
        }.tint(Theme.accent).interactiveDismissDisabled(model.hasUnsavedChanges || model.busy)
            .alert("放弃未保存的修改？", isPresented: $discarding) { Button("取消", role: .cancel) { }; Button("放弃", role: .destructive) { model.editingPath = nil } }
    }
    private func submitInput() async {
        let name = input.trimmingCharacters(in: .whitespacesAndNewlines)
        switch action {
        case "create": if let row = await model.perform("create", values: ["path": name]), let id = row["workspaceId"] as? String { model.workspaceID = id }
        case "rename": _ = await model.perform("rename", values: ["title": name])
        case "newFile": model.editingPath = model.path.isEmpty ? name : model.path + "/" + name; model.content = ""; model.savedContent = ""; model.revision = "new"
        case "mkdir": if await model.perform("mkdir", values: ["path": model.path.isEmpty ? name : model.path + "/" + name]) != nil { await model.browse() }
        default: if await model.perform(action, values: ["branch": name]) != nil { await model.loadGit() }
        }
    }
}

@MainActor final class WorkspaceFixtureService: NewConversationService, RemoteWorkspaceService {
    let workspaceCacheKey = "workspace-ui-fixture"
    var result: [String: Any] = [:]
    func devices() async throws -> [CreationDevice] { [try CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch", "workspaces": [["id": "work", "title": "Project"]], "choices": ["models": [], "presets": [], "workspaceOperations": true]])] }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt { throw ClientError.invalidResponse }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt { throw ClientError.invalidResponse }
    func submitWorkspace(device: CreationDevice, id: String, payload: [String: Any]) async throws -> [String: Any] {
        switch payload["action"] as? String {
        case "files": result = ["entries": [["name": "README.md", "directory": false, "symbolic": false]], "after": ""]
        case "read": result = ["text": "# Project\n", "revision": String(repeating: "a", count: 64)]
        case "write": result = ["revision": String(repeating: "b", count: 64)]
        case "git": result = ["current": "main", "branches": ["main", "feature"], "status": " M README.md", "worktrees": [["path": "/workspace", "branch": "main"]]]
        default: result = ["ok": true]
        }
        return ["id": id, "status": "queued"]
    }
    func workspaceReceipt(deviceID: String, id: String) async throws -> [String: Any] { ["id": id, "status": "accepted", "result": result] }
}
struct WorkspaceFixtureScreen: View {
    private let service = WorkspaceFixtureService()
    var body: some View {
        if let device = try? CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch", "workspaces": [["id": "work", "title": "Project"]]]) {
            RemoteWorkspaceView(device: device, service: service, directory: service, authFailure: { _ in })
        }
    }
}
