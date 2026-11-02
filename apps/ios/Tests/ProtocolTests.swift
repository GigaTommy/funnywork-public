import XCTest
@testable import FunnyWork

final class ProtocolTests: XCTestCase {
    func testCachedAssistantBrandUsesCurrentDisplayName() throws {
        let data = Data(#"{"id":1,"label":"funnywork","text":"结果已保存","eventType":"assistant/message"}"#.utf8)
        let item = try JSONDecoder().decode(HistoryItem.self, from: data)
        XCTAssertEqual(item.label, "funnywork")
        XCTAssertEqual(item.displayLabel, "FunnyWork")
        XCTAssertEqual(item.text, "结果已保存")
    }

    func testModelWaitingRequiresLiveRunningStateAndValidatedCounters() throws {
        for value: Any in [-1, 1.5, true, "2", 10001] {
            let snapshot = try RemoteSnapshot(["online": true, "epoch": "e", "state": ["running": true, "admissionWaiting": value]])
            XCTAssertEqual(snapshot.admissionWaiting, 0)
        }
        for online in [true, false] {
            let snapshot = try RemoteSnapshot(["online": online, "epoch": "e", "state": ["running": true, "admissionWaiting": 2,
                "projections": ["modelSelection": ["next": ["model": "qwen"], "lastUsed": ["model": "deepseek"]], "agentPreset": "enterprise-helper-v2"]]])
            XCTAssertEqual(snapshot.admissionWaiting, online ? 2 : 0)
            XCTAssertEqual(snapshot.selectedModel, "deepseek"); XCTAssertEqual(snapshot.agentPreset, "enterprise-helper-v2")
        }
        XCTAssertEqual(try RemoteSnapshot(["online": true, "epoch": "e", "state": ["running": false, "admissionWaiting": 1]]).admissionWaiting, 0)
        XCTAssertThrowsError(try CreationChoices(["models": [["provider": "a", "model": "b", "name": "B"], ["provider": "a", "model": "b", "name": "duplicate"]], "presets": []]))
    }
    func testTokenUsageUsesDisjointBucketsAndRejectsMissingOrMalformedUsage() throws {
        let row: [String: Any] = ["uncachedInputTokens": 10, "outputTokens": 5, "cacheReadTokens": 20, "cacheWriteTokens": 3]
        let usage = try XCTUnwrap(RemoteTokenUsage(row)); XCTAssertEqual(usage.total, 38)
        XCTAssertNil(RemoteTokenUsage([:])); XCTAssertNil(RemoteTokenUsage(row.merging(["outputTokens": true]) { _, b in b }))
        XCTAssertNil(RemoteTokenUsage(row.merging(["cacheReadTokens": -1]) { _, b in b }))
        let snapshot = try RemoteSnapshot(["online": true, "epoch": "e", "state": ["projections": ["tokenUsage": row,
            "modelSelection": ["next": NSNull(), "lastUsed": ["model": "qwen"]]]]])
        XCTAssertEqual(snapshot.tokenUsage?.total, 38); XCTAssertEqual(snapshot.selectedModel, "qwen")
    }
    func testDeviceCatalogIsSharedByWorkspacesAndAcceptsManagedCapacityPlusStandard() throws {
        let presets = [["id": "standard", "name": "Standard"]] + (0..<512).map { ["id": "enterprise-\($0)-v1", "name": "企业助手"] }
        let choices: [String: Any] = ["models": [["provider": "enterprise", "model": "qwen", "name": "企业千问"]], "presets": presets]
        let device = try CreationDevice(["id": "device", "name": "Mac", "online": true, "epoch": "epoch",
            "workspaces": (0..<100).map { ["id": "work-\($0)", "title": "Work"] }, "choices": choices])
        XCTAssertEqual(device.choices?.presets.count, 513)
        XCTAssertEqual(device.workspaces.count, 100)
        let restored = try JSONDecoder().decode(CreationDevice.self, from: JSONEncoder().encode(device))
        XCTAssertEqual(restored.choices, device.choices)
    }
    func testMessageAttachmentsAreValidatedDeduplicatedAndCompatibleWithOldCache() throws {
        let file: [String: Any] = ["attachmentId": "sha256:" + String(repeating: "a", count: 64), "name": "../notes.txt", "bytes": 10]
        for content: [String: Any] in [["content": [["attachment": file], ["attachment": file], ["attachment": ["attachmentId": "../invalid"]]]],
                                    ["message": ["content": [["attachment": file]]]]] {
            let item = try XCTUnwrap(HistoryItem(["seq": 0, "type": "user/message", "data": content]))
            XCTAssertTrue(item.text.isEmpty); XCTAssertEqual(item.attachments.count, 1)
            XCTAssertEqual(item.attachments[0].name, "notes.txt")
            XCTAssertFalse(item.attachments[0].complete)
            XCTAssertEqual(try JSONDecoder().decode(HistoryItem.self, from: JSONEncoder().encode(item)), item)
        }
        let old = Data(#"{"id":0,"label":"you","text":"old cache"}"#.utf8)
        XCTAssertTrue(try JSONDecoder().decode(HistoryItem.self, from: old).attachments.isEmpty)
    }
    func testRequestPreservesPaginationAndAuthentication() throws {
        let api = API(Credentials(server: try API.serverURL("https://example.com"), token: "test-token"))
        let request = try api.makeRequest("sync/sessions/abc/events?offset=100&limit=100")
        XCTAssertEqual(request.url?.path, "/api/account/sync/sessions/abc/events")
        XCTAssertEqual(request.url?.query, "offset=100&limit=100")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
    }
    func testCommandRetryPreservesRequestIdentity() throws {
        let api = API(Credentials(server: try API.serverURL("https://example.com"), token: "test-token"))
        let body: [String: Any] = ["id": "stable-request", "epoch": "runtime-1", "kind": "cancel", "payload": ["expectedTurn": 42]]
        for _ in 0..<2 {
            let request = try api.makeRequest("sync/sessions/abc/commands", body: body)
            let decoded = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
            XCTAssertEqual(decoded["id"] as? String, "stable-request")
            XCTAssertEqual((decoded["payload"] as? [String: Int])?["expectedTurn"], 42)
            XCTAssertEqual(request.httpMethod, "POST")
        }
    }
    func testServerRejectsUnsafeAddresses() throws {
        for address in ["http://example.com", "https://user:secret@example.com", "https://example.com/path", "https://example.com?token=x", "https://example.com#x"] {
            XCTAssertThrowsError(try API.serverURL(address))
        }
        XCTAssertEqual(try API.serverURL("https://example.com").host, "example.com")
    }
    func testBothNativeMessageShapes() {
        for data: [String: Any] in [["message": ["content": [["text": "hello"], ["type": "image"]]]], ["content": [["text": "hello"]]]] {
            XCTAssertEqual(HistoryItem(["seq": 3, "type": "assistant/message", "data": data])?.text, "hello")
        }
    }
    func testReasoningAndToolArgumentsDoNotBecomeReplyText() throws {
        let parts: [[String: Any]] = [["type": "reasoning", "text": "Internal reasoning"],
                                    ["type": "tool-call", "text": "Tool arguments"],
                                    ["type": "text", "text": "Public result"], ["text": "Legacy text"]]
        for data: [String: Any] in [["content": parts], ["message": ["content": parts]]] {
            let item = try XCTUnwrap(HistoryItem(["seq": 1, "type": "assistant/message", "data": data]))
            XCTAssertEqual(item.text, "Public result\nLegacy text")
        }
    }
    func testSessionUsesServerKeysAndRejectsPathInjection() {
        XCTAssertEqual(Conversation(["id": "abc-123", "device_name": "Mac", "online": true])?.device, "Mac")
        XCTAssertNil(Conversation(["id": "../other"]))
    }
    func testNetworkErrorsUseActionableCopyWithoutInternalCodes() {
        XCTAssertEqual(ClientError.displayMessage(for: URLError(.notConnectedToInternet)), String(localized: "连接已断开，请检查网络后重试。"))
        XCTAssertEqual(ClientError.displayMessage(for: URLError(.timedOut)), String(localized: "等待超时，请重试。"))
        XCTAssertEqual(ClientError.displayMessage(for: NSError(domain: "Internal", code: 123)), String(localized: "暂时无法完成，请重试。"))
    }

}
