#if DEBUG
import SwiftUI
import CryptoKit

/// Deterministic UI-test data. No account, credentials, or network access is involved.
@MainActor final class ConversationFixtureService: ConversationService, ForkConversationService {
    var livePhase = 0
    var forkDeviceID = "preview-device"
    var forkOpened: ((Conversation) -> Void)?
    var inheritedThrough: Int?
    private(set) var lastForkSequence: Int?
    func forkSnapshot() async throws -> RemoteSnapshot { try await snapshot() }
    func submitFork(_ request: ForkRequest) async throws -> CreationReceipt {
        lastForkSequence = request.sequence
        if ProcessInfo.processInfo.arguments.contains("--ui-test-fork-lost") { throw URLError(.timedOut) }
        return try CreationReceipt(["id": request.id, "status": "accepted", "result": ["sessionId": "preview-branch-" + request.id]])
    }
    func forkReceipt(_ request: ForkRequest) async throws -> CreationReceipt? {
        lastForkSequence = request.sequence
        return try CreationReceipt(["id": request.id, "status": ProcessInfo.processInfo.arguments.contains("--ui-test-fork-uncertain") ? "uncertain" : "accepted", "result": ["sessionId": "preview-branch-" + request.id]])
    }
    func forkDestination(_ id: String) async throws -> Conversation {
        let row = Conversation(["id": id, "title": "分支对话", "device_id": forkDeviceID, "device_name": "MacBook Pro", "next_seq": (lastForkSequence ?? 1) + 1, "online": true, "updated_at": Date().timeIntervalSince1970 * 1000])!
        forkOpened?(row); return row
    }
    var stoppedText: String?
    var handled = false
    var commands: [CommandReceipt] = []
    var appendedMessages = 0
    var queueRemoved = false
    var running = true
    let questions: Bool
    init(questions: Bool) { self.questions = questions }
    func snapshot() async throws -> RemoteSnapshot {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-offline") { throw URLError(.notConnectedToInternet) }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-queue-withdrawal") || ProcessInfo.processInfo.arguments.contains("--ui-test-queue-edit") {
            return try RemoteSnapshot(["online": true, "epoch": "preview-epoch", "state": ["running": true,
                "queue": queueRemoved ? [] : [["id": "queue-item", "text": "检查测试结果", "editable": true]]]])
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-model-waiting") {
            return try RemoteSnapshot(["online": true, "epoch": "preview-epoch", "state": ["controllable": true,
                "running": running, "turnStart": 1, "admissionWaiting": running ? 1 : 0,
                "projections": ["modelSelection": ["next": ["model": "企业千问"]], "agentPreset": "企业助手",
                    "sessionStats": ["steps": 1, "llmMs": 500, "toolMs": 100, "ttftMs": 100, "ttftSteps": 1, "decodeMs": 400, "decodeTokens": 5],
                    "tokenUsage": ProcessInfo.processInfo.arguments.contains("--ui-test-performance-only") ? [:] : ["uncachedInputTokens": 10, "outputTokens": 5, "cacheReadTokens": 20, "cacheWriteTokens": 3]]]])
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-live-reply") {
            var state: [String: Any] = ["running": livePhase < 2, "turnStart": 1]
            if livePhase < 2 {
                state["liveAssistant"] = ["attemptId": "preview-attempt", "startedAfterSeq": 1, "turnStart": 1,
                    "text": livePhase == 0 ? "先检查项目结构。" : "先检查项目结构。然后核对测试结果。", "truncated": false]
            }
            return try RemoteSnapshot(["online": true, "epoch": "preview-epoch", "state": state])
        }
        let request: [String: Any] = questions
            ? ["questions": [["id": "q1", "question": "优先验证哪个平台？", "options": [["label": "iOS", "description": "先验证手机上的操作"], ["label": "macOS", "description": "先验证桌面执行"]]]]]
            : ["toolName": "运行测试", "reason": "验证这次修改是否通过现有检查。", "command": "pnpm test"]
        return try RemoteSnapshot(["online": true, "epoch": "preview-epoch", "sessionForks": ProcessInfo.processInfo.arguments.contains("--ui-test-fork"), "attachmentUploads": ProcessInfo.processInfo.arguments.contains("--ui-test-upload"), "state": ["controllable": true, "running": running, "turnStart": 1,
            "pending": handled || ProcessInfo.processInfo.arguments.contains("--ui-test-queued") ? [] : [["eventId": "preview-event", "event": questions ? "user-questions/request" : "approval/request", "request": request]]]])
    }
    func receipts() async throws -> [CommandReceipt] { commands }
    func events(offset: Int, limit: Int) async throws -> EventPage {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-offline") { throw URLError(.notConnectedToInternet) }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-long-conversation") {
            let count = (ProcessInfo.processInfo.arguments.contains("--ui-test-search-context") ? 200 : 75) + appendedMessages + (ProcessInfo.processInfo.arguments.contains("--ui-test-unread-update") ? 1 : 0)
            let rows: [[String: Any]] = (offset..<max(offset, min(count, offset + limit))).map { index in
                ["seq": index, "type": "assistant/message", "data": ["content": [["text": "消息 \(index)：这是一段用于检查长对话阅读位置的内容。"]]]]
            }
            return try EventPage(["events": rows, "nextSeq": offset + rows.count, "durableNextSeq": count], offset: offset)
        }
        let rich = ProcessInfo.processInfo.arguments.contains("--ui-test-rich-text")
        let table = ProcessInfo.processInfo.arguments.contains("--ui-test-table")
        let diff = ProcessInfo.processInfo.arguments.contains("--ui-test-diff")
        var reply = diff ? "```diff\n--- a/Config.swift\n+++ b/Config.swift\n@@ -1,2 +1,2 @@\n let title = \"funnywork\"\n-let retries = 1\n+let retries = 3\n```" : table ? "| 功能 | 状态 | 备注 |\n| :--- | :---: | ---: |\n| 对话同步 | 已完成 | 支持离线阅读 |\n| 文件预览 | 已完成 | 支持分享 |" : rich ? "# 检查结果\n\n**已完成**主要调整。\n- 草稿恢复\n- 离线阅读\n\n```swift\nlet message = \"Hello\"\nprint(message)\n```\n\n> 请确认后继续。" : "已经补齐草稿恢复和消息更新。接下来运行检查，再确认交互效果。"
        if ProcessInfo.processInfo.arguments.contains("--ui-test-multi-file-review") {
            reply += "\n\n```diff\n--- a/Notes.swift\n+++ b/Notes.swift\n@@ -1 +1 @@\n-let ready = false\n+let ready = true\n```"
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-long-review") {
            reply = String(repeating: "这里是本轮已同步的详细说明，审阅时可以按需展开查看。", count: 100) + " 完整回复末尾标记\n\n" + reply
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-math") {
            reply = #"公式：\(x_1^2 + y_1^2\)，金额 $5 和 $10。"# + "\n\n" + #"\[\frac{a}{b} = \sqrt{x^2+y^2}\]"# + "\n\n" + #"\[\begin{pmatrix}1&2\\3&4\end{pmatrix}\]"# + "\n\n" + #"\[\unsupported{a}\]"#
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-code-reading") {
            reply = "```swift\nlet title = \"你好，funnywork\"\n\n    let description = \"" + String(repeating: "long_code_", count: 45) + "\"\nprint(title)\n```"
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-lists") {
            reply = "1. 准备\n   - [x] 阅读说明\n   - [ ] 检查结果\n     确认后再继续\n2. 完成\n\n---\n\n下一步"
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-nested-markdown") {
            reply = "- 检查结果\n\n  已完成第一轮检查。\n\n  ```swift\n  let ready = true\n  ```\n\n  功能 | 状态 | 备注\n  --- | --- | ---\n  对话同步 | 已完成 | 支持离线阅读\n\n  > 请确认\n  >\n  > > 保留原始记录\n\n- 下一步"
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-links") {
            reply = "查看[示例网站](https://example.com)，关闭后继续阅读。"
        }
        var all: [[String: Any]] = [
            ["seq": 0, "type": "user/message", "data": ["content": [["text": "继续完善手机端的远程操作。"]]]],
            ["seq": 1, "type": "assistant/message", "data": ["content": [["text": reply]]]]
        ]
        if ProcessInfo.processInfo.arguments.contains("--ui-test-long-user-message") {
            let text = (1...14).map { "第\($0)段：请保留原有内容，并逐项检查阅读、复制和页面恢复。" }.joined(separator: "\n") + "\n完整消息末尾"
            all[0] = ["seq": 0, "type": "user/message", "data": ["content": [["type": "text", "text": text]]]]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-live-reply"), livePhase >= 2 {
            all.append(["seq": 2, "type": "assistant/message", "data": ["interrupted": stoppedText != nil,
                "content": [["text": stoppedText ?? "先检查项目结构。然后核对测试结果。"]]]])
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-activity") {
            all = [all[0],
                ["seq": 1, "type": "tool/call", "data": ["name": "shell", "arguments": "{\"command\":\"cat notes.txt\"}"]],
                ["seq": 2, "type": "tool/result", "data": ["message": ["isError": true, "content": [["type": "text", "text": "notes.txt 不存在"]]]]],
                ["seq": 3, "type": "user/message", "data": ["source": ["kind": "runtime-context"], "content": [["type": "text", "text": "工作区信息已更新"]]]],
                ["seq": 4, "type": "turn/end", "data": ["reason": ["kind": "error", "error": ["message": "请检查文件路径"]]]]
            ]
            if ProcessInfo.processInfo.arguments.contains("--ui-test-many-activities") {
                all = [all[0]] + (1...60).map { sequence in
                    ["seq": sequence, "type": "system/message", "data": ["content": [["text": "活动详情 \(sequence)：工作区上下文和任务状态已更新"]]]] as [String: Any]
                }
            }
        }
        if (ProcessInfo.processInfo.arguments.contains("--ui-test-message-file") || ProcessInfo.processInfo.arguments.contains("--ui-test-message-image")),
           let file = try await AttachmentFixtureService().page(after: "").items.first(where: { $0.name == "说明.txt" || $0.name == "图片.png" }) {
            all[0] = ["seq": 0, "type": "user/message", "data": ["content": [["type": "file", "attachment": [
                "attachmentId": "sha256:" + file.hash, "name": file.name, "bytes": file.bytes]]]]]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-result-file"),
           let file = try await AttachmentFixtureService().page(after: "").items.first(where: { $0.name == "图片.png" }) {
            let previews = try await AttachmentFixtureService().page(after: "").items.filter { ["preview.pdf", "workbook.json", "sheet-1-0.json"].contains($0.name) }
            let variants: [[String: Any]] = previews.map { preview in
                let key = preview.name == "preview.pdf" ? "pdf" : preview.name == "workbook.json" ? "workbook" : "sheet-1-0"
                return ["role": key == "sheet-1-0" ? "sheet" : key, "key": key, "content": ["type": "file", "attachment": ["attachmentId": "sha256:" + preview.hash, "name": preview.name, "bytes": preview.bytes]]]
            }
            let result: [String: Any] = ["schema": 1, "id": "fixture-result", "version": 1,
                "original": ["type": "file", "attachment": ["attachmentId": "sha256:" + file.hash, "name": file.name, "bytes": file.bytes]],
                "variants": ProcessInfo.processInfo.arguments.contains("--ui-test-result-late-preview") ? [] : variants,
                "previewState": ProcessInfo.processInfo.arguments.contains("--ui-test-result-late-preview") ? "pending" : "ready", "warnings": []]
            all = [["seq": 0, "type": "deliverables/presented", "data": ["turn": 1, "files": [["path": "/fixed/image.png", "result": result]]]]]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-gallery") {
            let files = try await AttachmentFixtureService().page(after: "").items.filter(InlineImagePreview.isImage)
            all[0] = ["seq": 0, "type": "user/message", "data": ["content": files.map { file in
                ["type": "file", "attachment": ["attachmentId": "sha256:" + file.hash, "name": file.name, "bytes": file.bytes]] as [String: Any]
            }]]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-review-recovery") {
            all = [
                ["seq": 0, "type": "user/message", "data": ["content": [["text": "Old task"]]]],
                ["seq": 1, "type": "assistant/message", "data": ["content": [["text": "Old result"]]]],
                ["seq": 2, "type": "turn/end", "data": ["reason": ["kind": "completed"]]],
                ["seq": 3, "type": "turn/start", "data": [:]],
                ["seq": 4, "type": "assistant/message", "data": ["content": [["text": "New result"]]]],
                ["seq": 5, "type": "turn/end", "data": ["reason": ["kind": "completed"]]]
            ]
        }
        if let inheritedThrough { all = all.filter { ($0["seq"] as? Int ?? Int.max) <= inheritedThrough } }
        let rows = Array(all.dropFirst(offset).prefix(limit))
        return try EventPage(["events": rows, "nextSeq": offset + rows.count, "durableNextSeq": all.count], offset: offset)
    }
    func send(_ command: RemoteCommand) async throws -> CommandReceipt {
        if command.kind == "cancel", ProcessInfo.processInfo.arguments.contains("--ui-test-live-reply") {
            stoppedText = livePhase == 0 ? "先检查项目结构。" : "先检查项目结构。然后核对测试结果。"
            livePhase = 2
        }
        if command.kind == "queueEdit", !ProcessInfo.processInfo.arguments.contains("--ui-test-queue-edit") { queueRemoved = true }
        if command.kind == "cancel" { running = false }
        if command.kind == "decision" { handled = true }
        let receipt = CommandReceipt(["id": command.id, "status": ProcessInfo.processInfo.arguments.contains("--ui-test-queue-edit") && command.kind == "queueEdit" ? "queued" : "accepted"])!
        commands.insert(receipt, at: 0)
        return receipt
    }
}
@MainActor struct ConversationFixtureScreen: View {
    @State private var forkedConversation: Conversation?
    private let uploadService = PreviewUploadService()
    private let service: ConversationFixtureService
    private let store: ConversationStore
    private let conversation = Conversation(["id": "preview-chat", "title": "完善 iOS 远程操作", "device_id": "preview-device", "device_name": "MacBook Pro", "online": true])!
    init() {
        service = ConversationFixtureService(questions: ProcessInfo.processInfo.arguments.contains("--ui-test-questions"))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("ConversationUITests")
        if ProcessInfo.processInfo.arguments.contains("--ui-test-reset") { try? FileManager.default.removeItem(at: root) }
        store = ConversationStore(key: "preview", root: root)
        if ProcessInfo.processInfo.arguments.contains("--ui-test-review-recovery") {
            let rows: [[String: Any]] = [
                ["seq": 0, "type": "user/message", "data": ["content": [["text": "Old task"]]]],
                ["seq": 1, "type": "assistant/message", "data": ["content": [["text": "Old result"]]]],
                ["seq": 2, "type": "turn/end", "data": ["reason": ["kind": "completed"]]]
            ]
            if let page = try? EventPage(["events": rows, "nextSeq": 3, "durableNextSeq": 3], offset: 0), let review = RemoteResultReview(items: page.events) {
                var saved = (try? store.load(conversation.id)) ?? SavedConversation()
                if saved.reviewDrafts[review.key] == nil {
                    saved.reviewDrafts[review.key] = "Unsubmitted old review"; saved.reviewSnapshots[review.key] = review
                    try? store.save(saved, for: conversation.id)
                }
            }
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-queued") {
            var saved = (try? store.load(conversation.id)) ?? SavedConversation()
            if saved.recentCommands.isEmpty {
                saved.recentCommands = [RemoteCommand(epoch: "preview-epoch", kind: "prompt", payload: ["text": .string("检查上传结果"), "mode": .string("queue")])]
                try? store.save(saved, for: conversation.id)
            }
            service.commands = saved.recentCommands.map { CommandReceipt(["id": $0.id, "status": "queued"])! }
        }
    }
    private func branchService() -> ConversationFixtureService {
        let result = ConversationFixtureService(questions: false)
        result.running = false; result.handled = true; result.inheritedThrough = service.lastForkSequence
        return result
    }
    var body: some View {
        NavigationStack {
            List {
                NavigationLink("示例对话") {
                    ConversationView(conversation: conversation, service: service, store: store, attachmentService: AttachmentFixtureService(), uploadService: uploadService, reading: MessageReading(driver: PreviewReadingDriver()), voiceDriver: PreviewVoiceDriver(), attachmentSelection: ProcessInfo.processInfo.arguments.contains("--ui-test-upload") ? {
                        let url = FileManager.default.temporaryDirectory.appendingPathComponent("项目说明.txt")
                        try Data("附件样例".utf8).write(to: url)
                        return [url]
                    } : nil, cameraSelection: ProcessInfo.processInfo.arguments.contains("--ui-test-camera") ? {
                        if ProcessInfo.processInfo.arguments.contains("--ui-test-camera-cancel") { return nil }
                        return UIGraphicsImageRenderer(size: CGSize(width: 80, height: 60)).image { context in
                            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 80, height: 60))
                        }
                    } : nil)
                        .toolbar {
                            if ProcessInfo.processInfo.arguments.contains("--ui-test-queue-edit") {
                                Button("模拟拒绝") { service.commands = service.commands.map { CommandReceipt(["id": $0.id, "status": "rejected"])! } }
                            }
                            if ProcessInfo.processInfo.arguments.contains("--ui-test-live-reply") {
                                Button("更新回复样例") { service.livePhase += 1 }
                            }
                            if ProcessInfo.processInfo.arguments.contains("--ui-test-queued") {
                                Button("完成测试任务") {
                                    service.running = false
                                    service.commands = service.commands.map { CommandReceipt(["id": $0.id, "status": "accepted"])! }
                                }
                            }
                            if ProcessInfo.processInfo.arguments.contains("--ui-test-long-conversation") {
                                Button("追加测试消息") { service.appendedMessages += 1 }
                            }
                        }
                }
            }.navigationTitle("交互预览")
                .navigationDestination(isPresented: Binding(get: { forkedConversation != nil }, set: { if !$0 { forkedConversation = nil } })) {
                    if let row = forkedConversation {
                        ConversationView(conversation: row, service: branchService(), store: store, attachmentService: AttachmentFixtureService())
                    }
                }
        }.environment(\.openConversation, { row in forkedConversation = row })
    }
}
#endif

#if DEBUG
@MainActor final class DirectoryFixtureService: ObservableObject, ConversationDirectoryService {
    private var forkedRows: [Conversation] = []
    var forkBoundaries: [String: Int] = [:]
    func addFork(_ row: Conversation) { if !forkedRows.contains(where: { $0.id == row.id }) { forkedRows.append(row) } }
    private var titles: [String: String] = [:]
    private var revisions: [String: Int64] = [:]
    private var archived: [String: Bool] = [:]
    private var archiveRevisions: [String: Int64] = [:]
    func archive(id: String, archived: Bool, revision: Int64) async throws -> Int64 {
        guard revision == archiveRevisions[id, default: 0] else { throw ClientError.invalidResponse }
        self.archived[id] = archived; archiveRevisions[id] = revision + 1
        return revision + 1
    }
    func rename(id: String, title: String, revision: Int64) async throws -> Int64 {
        guard revision == revisions[id, default: 0] else { throw ClientError.invalidResponse }
        titles[id] = title; revisions[id] = revision + 1
        return revision + 1
    }
    func page(after: String) async throws -> [Conversation] {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-overview-offline") { throw URLError(.notConnectedToInternet) }
        guard after.isEmpty else { return [] }
        let now = Date().timeIntervalSince1970 * 1000
        var rows = [
            Conversation(["id": "a", "title": "修改登录页", "device_id": "mac", "device_name": "MacBook Pro", "online": true, "updated_at": now - 120000])!,
            Conversation(["id": "b", "title": "整理会议纪要", "device_id": "windows", "device_name": "Windows", "online": false, "updated_at": now - 3600000])!,
            Conversation(["id": "c", "title": "准备下一版更新", "device_id": "mac", "device_name": "MacBook Pro", "online": true, "updated_at": now - 7200000])!
        ]
        if ProcessInfo.processInfo.arguments.contains("--ui-test-overview") {
            rows.append(Conversation(["id": "d", "title": "确认发布安排", "device_id": "mac", "device_name": "MacBook Pro", "online": true, "updated_at": now - 10800000])!)
        }
        rows += forkedRows
        return rows.map { row in
            var updated = row
            if ProcessInfo.processInfo.arguments.contains("--ui-test-overview") {
                updated.previewText = ["a": "登录表单已调整，等待批准运行检查。", "b": "会议纪要已整理为 notes.md。", "c": "正在整理本次更新内容。", "d": "这次发布需要包含哪些平台？"][row.id]
                updated.taskState = ["a": .approval, "c": .running, "d": .question][row.id]
                updated.taskStateExpiresAt = Date().addingTimeInterval(ProcessInfo.processInfo.arguments.contains("--ui-test-overview-stale") ? -1 : 30)
            }
            if ProcessInfo.processInfo.arguments.contains("--ui-test-unread") {
                updated.nextSequence = (ProcessInfo.processInfo.arguments.contains("--ui-test-long-conversation") ? 75 : 2) + (ProcessInfo.processInfo.arguments.contains("--ui-test-unread-update") ? 1 : 0)
            }
            updated.title = titles[row.id] ?? row.title
            updated.titleRevision = revisions[row.id, default: 0]
            updated.archived = archived[row.id, default: false]
            updated.archiveRevision = archiveRevisions[row.id, default: 0]
            return updated
        }
    }
}
@MainActor struct DirectoryFixtureScreen: View {
    @StateObject private var directoryService = DirectoryFixtureService()
    @StateObject private var pushModel: PushNotifications
    private var push: PushNotifications? { ProcessInfo.processInfo.arguments.contains("--ui-test-push") ? pushModel : nil }
    @StateObject private var store: ConversationStore
    private let creation = CreationFixtureService()
    init() {
        _pushModel = StateObject(wrappedValue: PushFixture.make())
        _store = StateObject(wrappedValue: Self.makeStore())
    }
    private static func makeStore() -> ConversationStore {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("CreationUITests")
        if ProcessInfo.processInfo.arguments.contains("--ui-test-reset") { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "preview", root: root)
        if ProcessInfo.processInfo.arguments.contains("--ui-test-storage") {
            let data = Data("Downloaded fixture".utf8)
            let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            if let file = try? ChatAttachment(["hash": hash, "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + hash, "name": "sample.txt", "bytes": data.count]]]) {
                _ = try? store.saveAttachment(data, item: file)
            }
        }
        return store
    }
    private func conversationService(_ row: Conversation) -> ConversationFixtureService {
        let service = ConversationFixtureService(questions: ProcessInfo.processInfo.arguments.contains("--ui-test-questions"))
        service.forkDeviceID = row.deviceID
        service.forkOpened = { [weak service] result in
            directoryService.addFork(result)
            directoryService.forkBoundaries[result.id] = service?.lastForkSequence
        }
        if row.id.hasPrefix("preview-branch-") {
            service.running = false; service.handled = true; service.inheritedThrough = directoryService.forkBoundaries[row.id]
        }
        return service
    }
    var body: some View {
        ConversationListView(service: directoryService, store: store, createService: creation, searchService: HistorySearchFixtureService(), push: push, name: "Tommy", initialSidebarOpen: !ProcessInfo.processInfo.arguments.contains("--ui-test-sidebar"), logout: {}) { row in
            if ProcessInfo.processInfo.arguments.contains("--ui-test-search-context") || ProcessInfo.processInfo.arguments.contains("--ui-test-pending-context") || ProcessInfo.processInfo.arguments.contains("--ui-test-unread") || ProcessInfo.processInfo.arguments.contains("--ui-test-fork") || ProcessInfo.processInfo.arguments.contains("--ui-test-task-creation") {
                ConversationView(conversation: row, service: conversationService(row), store: store, attachmentService: AttachmentFixtureService())
            } else { Text(row.title).accessibilityIdentifier("selected-conversation") }
        }.safeAreaInset(edge: .bottom) { if let push { PushFixtureTrigger(model: push) } }
    }
}
#endif

#if DEBUG
@MainActor final class ResultPreviewFixtureService: ResultService {
    static let shared = ResultPreviewFixtureService()
    private var calls = 0
    func directory() async throws -> [ResultRecord] { try await versions("fixture-result") }
    func versions(_ id: String) async throws -> [ResultRecord] {
        calls += 1
        let files = try await AttachmentFixtureService().page(after: "").items
        guard let original = files.first(where: { $0.name == "图片.png" }) else { throw ClientError.invalidResponse }
        func reference(_ file: ChatAttachment) -> ResultFile { ResultFile(type: "file", attachment: .init(attachmentId: "sha256:" + file.hash, name: file.name, bytes: file.bytes)) }
        let variants = calls < 2 ? [] : files.filter { $0.name == "preview.pdf" }.map { ResultVariant(role: "pdf", key: "pdf", content: reference($0)) }
        let result = ResultVersion(schema: 1, id: id, version: 1, previousHash: nil, original: reference(original), variants: variants, previewState: calls < 2 ? "pending" : "ready", warnings: [])
        return [ResultRecord(manifest: result, eventSeq: calls, createdAt: 1, transfers: ["original": ResultTransfer(nextOffset: original.bytes, complete: true), "pdf": ResultTransfer(nextOffset: 0, complete: !variants.isEmpty)])]
    }
}
@MainActor struct AttachmentFixtureService: AttachmentService {
    private var data: Data {
        if (ProcessInfo.processInfo.arguments.contains("--ui-test-message-image") || ProcessInfo.processInfo.arguments.contains("--ui-test-result-file")), let image = UIImage(named: "BrandMark")?.pngData() { return image }
        return Data("附件预览已就绪。\n\n草稿恢复、离线阅读和文件下载均可在这里验证。".utf8)
    }
    private static var pausedResultDownload = false
    private static let resultPDF = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
        for page in 1...2 { context.beginPage(); ("Fixed version · page \(page)" as NSString).draw(at: CGPoint(x: 40, y: 40), withAttributes: [.font: UIFont.systemFont(ofSize: 24)]) }
    }
    private var resultPreviews: [(String, Data)] {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-result-pdf") {
            return [("preview.pdf", Self.resultPDF)]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-result-workbook") {
            let index: [String: Any] = ["schema": 1, "sheets": [["id": "sheet-1", "name": "月报", "rows": 3, "columns": 3, "tiles": ["sheet-1-0"], "merges": ["A1:B1"], "widths": [14, 12, 10]]], "missingResults": 1, "warnings": ["formula-result-missing"]]
            let tile: [String: Any] = ["schema": 1, "sheetId": "sheet-1", "firstRow": 0, "cells": [["row": 0, "column": 0, "text": "月报标题", "bold": true, "mergeRows": 1, "mergeColumns": 2], ["row": 1, "column": 0, "text": "25.0%"], ["row": 2, "column": 0, "text": "", "formula": "2+2", "missingResult": true]]]
            return [("workbook.json", try! JSONSerialization.data(withJSONObject: index, options: [.sortedKeys])), ("sheet-1-0.json", try! JSONSerialization.data(withJSONObject: tile, options: [.sortedKeys]))]
        }
        return []
    }
    private var secondImage: Data {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: 1400, height: 700), format: format).pngData { context in
            UIColor(white: 0.82, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: 1400, height: 700))
            ("funnywork" as NSString).draw(at: CGPoint(x: 100, y: 260), withAttributes: [.font: UIFont.systemFont(ofSize: 90, weight: .semibold), .foregroundColor: UIColor.darkGray])
        }
    }
    private var secondHash: String { SHA256.hash(data: secondImage).map { String(format: "%02x", $0) }.joined() }
    private var hash: String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func page(after: String) async throws -> AttachmentPage {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-offline") { throw URLError(.notConnectedToInternet) }
        var rows: [[String: Any]] = [
            ["hash": hash, "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + hash, "bytes": data.count, "name": (ProcessInfo.processInfo.arguments.contains("--ui-test-message-image") || ProcessInfo.processInfo.arguments.contains("--ui-test-result-file")) ? "图片.png" : "说明.txt"]]],
            ["hash": String(repeating: "a", count: 64), "complete": false, "metadata": ["attachment": ["attachmentId": "sha256:" + String(repeating: "a", count: 64), "bytes": 2048, "name": "会议纪要.pdf"]]],
            ["hash": String(repeating: "b", count: 64), "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + String(repeating: "b", count: 64), "bytes": 50 * 1024 * 1024, "name": "演示视频.mov"]]]
        ].sorted { ($0["hash"] as! String) < ($1["hash"] as! String) }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-gallery") {
            rows.append(["hash": secondHash, "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + secondHash, "bytes": secondImage.count, "name": "工作区.png"]]])
            rows.sort { ($0["hash"] as! String) < ($1["hash"] as! String) }
        }
        for (name, bytes) in resultPreviews {
            let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
            rows.append(["hash": digest, "complete": true, "metadata": ["attachment": ["attachmentId": "sha256:" + digest, "name": name, "bytes": bytes.count]]])
        }
        rows.sort { ($0["hash"] as! String) < ($1["hash"] as! String) }
        return try AttachmentPage(["items": after.isEmpty ? rows : [], "after": ""], after: after)
    }
    func chunk(_ attachment: ChatAttachment, offset: Int) async throws -> AttachmentChunk {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-offline") { throw URLError(.notConnectedToInternet) }
        let bytes = resultPreviews.first(where: { $0.0 == attachment.name })?.1 ?? (ProcessInfo.processInfo.arguments.contains("--ui-test-gallery") && attachment.hash == secondHash ? secondImage : data)
        let pausesPreview = ProcessInfo.processInfo.arguments.contains("--ui-test-result-pause-preview") && ["preview.pdf", "workbook.json"].contains(attachment.name)
        if pausesPreview, offset == 4, !Self.pausedResultDownload { Self.pausedResultDownload = true; try await Task.sleep(for: .seconds(60)) }
        let end = min(bytes.count, offset + (pausesPreview && offset == 0 ? 4 : 384000))
        return try AttachmentChunk(["data": bytes.subdata(in: offset..<end).base64EncodedString(), "nextOffset": end, "complete": end == bytes.count], offset: offset, expected: attachment.bytes)
    }
}
#endif

#if DEBUG
@MainActor final class CreationFixtureService: NewConversationService, DeviceManagementService {
    private var accepted: String?
    private var access: [String: (Bool, Int64)] = [:]
    func setRevoked(_ revoked: Bool, deviceID: String, revision: Int64) async throws {
        guard ProcessInfo.processInfo.arguments.contains("--ui-test-device-management"), (access[deviceID]?.1 ?? 0) == revision else { throw ClientError.invalidResponse }
        access[deviceID] = (revoked, revision + 1)
    }
    private func managed(_ device: CreationDevice) throws -> CreationDevice {
        guard ProcessInfo.processInfo.arguments.contains("--ui-test-device-management") else { return device }
        let state = access[device.id] ?? (false, 0)
        return try CreationDevice(["id": device.id, "name": device.name, "online": state.1 == 0 && device.online, "epoch": device.epoch, "workspaces": state.1 == 0 ? device.workspaces.map { ["id": $0.id, "title": $0.title] } : [], "platform": device.platform ?? "darwin", "revoked": state.0, "accessRevision": state.1])
    }
    func devices() async throws -> [CreationDevice] {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-model-choices") {
            return [try CreationDevice(["id": "device", "name": "MacBook Pro", "online": true, "epoch": "preview-epoch",
                "workspaces": [["id": "project", "title": "funnywork"]], "choices": [
                    "models": [["provider": "enterprise", "model": "qwen", "name": "企业千问"], ["provider": "enterprise", "model": "deepseek", "name": "企业 DeepSeek"]],
                    "presets": [["id": "enterprise-helper-v1", "name": "企业助手"]]]])]
        }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-creation-locations") {
            return try [
                try CreationDevice(["id": "device", "name": "MacBook Pro", "online": true, "epoch": "preview-epoch", "workspaces": [["id": "project", "title": "funnywork"]]]),
                try CreationDevice(["id": "second", "name": "Mac mini", "platform": "darwin", "lastSeen": Date().timeIntervalSince1970 * 1000, "online": !ProcessInfo.processInfo.arguments.contains("--ui-test-creation-offline"), "epoch": "preview-epoch", "workspaces": [["id": "project", "title": "funnywork"], ["id": "notes", "title": "Notes"]]])
            ].map(managed)
        }
        return [try CreationDevice(["id": "device", "name": "MacBook Pro", "online": true, "epoch": "preview-epoch", "workspaces": [["id": "project", "title": "funnywork"]]])]
    }
    func submit(_ request: CreationRequest) async throws -> CreationReceipt {
        accepted = request.id
        if let status = fixtureReceiptStatus { return try CreationReceipt(["id": request.id, "status": status]) }
        return try CreationReceipt(["id": request.id, "status": ProcessInfo.processInfo.arguments.contains("--ui-test-creation-uncertain") ? "uncertain" : "queued"])
    }
    private var fixtureReceiptStatus: String? {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-creation-rejected") { return "rejected" }
        if ProcessInfo.processInfo.arguments.contains("--ui-test-creation-expired") { return "expired" }
        return nil
    }
    func receipt(_ request: CreationRequest) async throws -> CreationReceipt {
        if let status = fixtureReceiptStatus { return try CreationReceipt(["id": request.id, "status": status]) }
        return try CreationReceipt(["id": request.id, "status": ProcessInfo.processInfo.arguments.contains("--ui-test-creation-uncertain") ? "uncertain" : accepted == request.id ? "accepted" : "missing", "result": ["sessionId": "new-chat"]])
    }
}
#endif

#if DEBUG
@MainActor final class PreviewReadingDriver: ReadingDriver {
    var finished: ((UUID) -> Void)?
    var interrupted: (() -> Void)?
    func start(_ text: String, token: UUID) throws { }
    func pause() -> Bool { true }
    func resume() -> Bool { true }
    func stop() { }
}
#endif

#if DEBUG
@MainActor final class PreviewVoiceDriver: VoiceInputDriver {
    var result: ((UUID, String?, Bool, Error?) -> Void)?
    private var token: UUID?
    func start(token: UUID) async throws { self.token = token; result?(token, "语音草稿", false, nil) }
    func finish() { if let token { result?(token, "语音草稿", true, nil) } }
    func stop() { token = nil }
}
#endif

#if DEBUG
@MainActor final class PreviewUploadService: UploadService {
    private var uploaded = Set<String>()
    func prepare(_ file: OutgoingAttachment) async throws -> UploadStatus {
        try UploadStatus(["nextOffset": uploaded.contains(file.hash) ? file.bytes : 0, "complete": uploaded.contains(file.hash)], file: file)
    }
    func chunk(_ file: OutgoingAttachment, offset: Int, data: Data) async throws -> UploadStatus {
        if offset + data.count == file.bytes { uploaded.insert(file.hash) }
        return try UploadStatus(["nextOffset": offset + data.count, "complete": uploaded.contains(file.hash)], file: file)
    }
    func discard(_ file: OutgoingAttachment) async throws { uploaded.remove(file.hash) }
}
#endif

#if DEBUG
@MainActor struct HistorySearchFixtureService: HistorySearchService {
    func page(query: String, cursor: HistorySearchCursor, archived: Bool, deviceID: String?) async throws -> HistorySearchPage {
        let rows = [("a", "修改登录页"), ("c", "准备下一版更新")].map { id, title in
            HistorySearchResult(conversation: Conversation(["id": id, "title": title, "device_id": "mac", "device_name": "MacBook Pro", "archived": false])!,
                                sequence: 2, excerpt: "已完成 release 检查，准备提交。")
        }
        let matches = rows.filter { $0.excerpt.localizedStandardContains(query) && !archived && (deviceID == nil || deviceID == "mac") }
        return .init(items: cursor.session.isEmpty ? matches : [], cursor: .init(session: "c", sequence: 2), hasMore: false)
    }
    func message(_ result: HistorySearchResult) async throws -> HistoryItem {
        HistoryItem(["seq": result.sequence, "type": "assistant/message", "data": ["content": [["type": "text", "text": "完整记录：release 检查已完成，等待确认后提交。"]]]])!
    }
}
#endif
