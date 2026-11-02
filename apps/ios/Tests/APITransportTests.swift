import XCTest
@testable import FunnyWork

private final class TransportProtocol: URLProtocol {
    struct Response {
        var status = 200
        var data = Data("{}".utf8)
        var started: ((URLRequest) -> Void)?
        var stopped: (() -> Void)?
        var pending = false
        var reply: ((URLRequest) -> (Int, Data))?
    }
    private static let lock = NSLock()
    private static var responses: [String: Response] = [:]
    private var stopped: (() -> Void)?
    static func register(_ response: Response, host: String) {
        lock.lock(); defer { lock.unlock() }; responses[host] = response
    }
    static func remove(_ host: String) {
        lock.lock(); defer { lock.unlock() }; responses.removeValue(forKey: host)
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let response = Self.responses[request.url?.host ?? ""]; Self.lock.unlock()
        guard let response else { client?.urlProtocol(self, didFailWithError: URLError(.resourceUnavailable)); return }
        stopped = response.stopped; response.started?(request)
        guard !response.pending else { return }
        let (status, data) = response.reply?(request) ?? (response.status, response.data)
        let http = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { stopped?(); stopped = nil }
}

final class APITransportTests: XCTestCase {
    func testCommandAdmissionRejectsOnlyExactPreInsertionFailures() async {
        for code in ["SYNC_DECISION_EXPIRED", "SYNC_DEVICE_OFFLINE"] {
            for path in ["sync/sessions/chat/commands", "sync/devices/device/commands"] {
                let client = api(.init(status: 409, data: Data("{\"code\":\"\(code)\",\"message\":\"Private detail\"}".utf8)))
                do { _ = try await client.request(path, body: ["id": "command"]); XCTFail("Expected rejection") }
                catch {
                    XCTAssertEqual((error as? CommandAdmissionRejected)?.code, code)
                    XCTAssertFalse(ClientError.displayMessage(for: error).contains("Private detail"))
                }
            }
        }
    }
    func testOtherFailuresNeverProveCommandAdmissionRejected() async {
        let cases: [(String, Int, String, Bool)] = [
            ("sync/sessions/chat/commands", 409, "UNKNOWN_CONFLICT", true),
            ("sync/sessions/chat/commands", 404, "SYNC_DECISION_EXPIRED", true),
            ("sync/sessions/chat/commands", 500, "SYNC_DEVICE_OFFLINE", true),
            ("sync/sessions/chat/commands/id", 409, "SYNC_DEVICE_OFFLINE", true),
            ("sync/sessions/chat/commands", 409, "SYNC_DEVICE_OFFLINE", false),
            ("sync/device-links/code", 409, "SYNC_DEVICE_OFFLINE", true)
        ]
        for (path, status, code, post) in cases {
            let client = api(.init(status: status, data: Data("{\"code\":\"\(code)\"}".utf8)))
            do { _ = try await client.request(path, body: post ? ["id": "command"] : nil); XCTFail("Expected failure") }
            catch { XCTAssertFalse(error is CommandAdmissionRejected, "\(path) \(status)") }
        }
        XCTAssertNil(CommandAdmissionRejected.error(Data("{invalid".utf8)))
        XCTAssertNil(CommandAdmissionRejected.error(Data(#"{"code":409}"#.utf8)))
    }
    private var hosts: [String] = []
    override func tearDown() { hosts.forEach(TransportProtocol.remove); hosts = [] }
    private func api(_ response: TransportProtocol.Response, token: String = "test-token") -> API {
        let host = UUID().uuidString.lowercased() + ".invalid"; hosts.append(host)
        TransportProtocol.register(response, host: host)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [TransportProtocol.self]
        return API(Credentials(server: URL(string: "https://" + host)!, token: token), configuration: configuration)
    }
    @MainActor func testDeviceAccessPostsExpectedRevision() async throws {
        for revoked in [true, false] {
            let received = expectation(description: "Device access received")
            let api = api(.init(data: Data("{\"ok\":true}".utf8), started: { request in
                XCTAssertEqual(request.httpMethod, "POST")
                XCTAssertEqual(request.url?.path, "/api/account/sync/devices/device/" + (revoked ? "revoke" : "restore"))
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
                var body = request.httpBody
                if body == nil, let stream = request.httpBodyStream {
                    stream.open(); defer { stream.close() }
                    var bytes = [UInt8](repeating: 0, count: 4096)
                    let count = stream.read(&bytes, maxLength: bytes.count)
                    if count > 0 { body = Data(bytes.prefix(count)) }
                }
                let json = body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Int]
                XCTAssertEqual(json, ["revision": 7]); received.fulfill()
            }))
            try await HTTPNewConversationService(api: api).setRevoked(revoked, deviceID: "device", revision: 7)
            await fulfillment(of: [received], timeout: 2)
        }
    }
    @MainActor func testMobileHistoryQueryEncodesSearchScopeAndCursor() async throws {
        let received = expectation(description: "History query")
        let transport = api(.init(data: Data(#"{"items":[{"id":"chat","title":"Title","model_id":"qwen","revision":4,"history_revision":1,"archived":true}],"next":"cursor-next","historyManagement":true}"#.utf8), started: { request in
            let parts = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
            XCTAssertEqual(parts.path, "/api/account/chats")
            let values = Dictionary(uniqueKeysWithValues: (parts.queryItems ?? []).map { ($0.name, $0.value ?? "") })
            XCTAssertEqual(values["query"], "50%_! 中文 & ?")
            XCTAssertEqual(values["after"], "opaque+/="); XCTAssertEqual(values["archived"], "true")
            received.fulfill()
        }))
        let page = try await HTTPMobileChatService(api: transport).history(archived: true, query: "50%_! 中文 & ?", after: "opaque+/=")
        XCTAssertTrue(page.supported); XCTAssertEqual(page.next, "cursor-next")
        XCTAssertEqual(page.items.first?.historyRevision, 1)
        await fulfillment(of: [received], timeout: 1)
    }
    @MainActor func testLegacyHistoryResponseOnlyFiltersLoadedTitles() async throws {
        let transport = api(.init(data: Data(#"{"items":[{"id":"chat","title":"Match","modelId":"qwen","revision":4},{"id":"other","title":"Other","modelId":"qwen","revision":1}]}"#.utf8)))
        let page = try await HTTPMobileChatService(api: transport).history(archived: false, query: "Match", after: "")
        XCTAssertFalse(page.supported); XCTAssertEqual(page.items.map(\.id), ["chat"]); XCTAssertTrue(page.next.isEmpty)
    }
    @MainActor func testMetadataConflictIsAnAuthoritativeRejection() async throws {
        let transport = api(.init(status: 409, data: Data(#"{"code":"CHAT_HISTORY_REVISION_CONFLICT"}"#.utf8)))
        let chat = try MobileChatSummary(["id": "chat", "title": "Title", "modelId": "qwen", "revision": 4, "history_revision": 0, "archived": false])
        do { _ = try await HTTPMobileChatService(api: transport).updateHistory(MobileChatHistoryUpdate(chat: chat, title: "Change")); XCTFail("Conflict must reject") }
        catch { XCTAssertTrue(error is MobileChatHistoryRejected) }
    }
    func testAuthenticatedPaginationTravelsThroughURLSession() async throws {
        let received = expectation(description: "GET received")
        let api = api(.init(data: Data("[{\"id\":\"chat\"}]".utf8), started: { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/account/sync/sessions")
            XCTAssertEqual(request.url?.query, "after=abc&limit=100")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
            received.fulfill()
        }))
        let rows = try await api.request("sync/sessions?after=abc&limit=100") as? [[String: String]]
        XCTAssertEqual(rows?.first?["id"], "chat")
        await fulfillment(of: [received], timeout: 2)
    }
    func testLoginPostsJSONWithoutBearerAndDeleteAcceptsEmptyResponse() async throws {
        let received = expectation(description: "Login received")
        let login = api(.init(data: Data("{\"token\":\"new-token\"}".utf8), started: { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            var body = request.httpBody
            if body == nil, let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var bytes = [UInt8](repeating: 0, count: 4096)
                let count = stream.read(&bytes, maxLength: bytes.count)
                if count > 0 { body = Data(bytes.prefix(count)) }
            }
            let json = body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: String]
            XCTAssertEqual(json?["username"], "test-user")
            received.fulfill()
        }), token: "")
        let result = try await login.request("login", body: ["username": "test-user", "password": "fixture-only"]) as? [String: String]
        XCTAssertEqual(result?["token"], "new-token")
        await fulfillment(of: [received], timeout: 2)
        let deletion = api(.init(status: 204, data: Data(), started: { request in
            XCTAssertEqual(request.httpMethod, "DELETE")
        }))
        let deleted = try await deletion.request("sync/sessions/chat/uploads/hash", method: "DELETE")
        XCTAssertNotNil(deleted as? [String: Any])
    }
    func testHTTPFailuresDistinguishInvalidLoginExpiredSessionAndDeniedPermission() async {
        for (path, status, authentication) in [("login", 401, false), ("session", 401, true), ("session", 403, false), ("sync/sessions", 409, false), ("session", 500, false)] {
            let client = api(.init(status: status, data: Data("{\"message\":\"Internal secret detail\"}".utf8)))
            do { _ = try await client.request(path); XCTFail("Expected HTTP failure") }
            catch {
                XCTAssertEqual(error is AuthenticationError, authentication)
                XCTAssertFalse(ClientError.displayMessage(for: error).contains("Internal secret"))
            }
        }
    }

    func testChatFailureUsesChatCopyAndMissingSnapshotsRemainRecoverable() async throws {
        for status in [403, 404] {
            let client = api(.init(status: status, data: Data()))
            do { _ = try await client.request("chats/models"); XCTFail("Expected failure") }
            catch { XCTAssertEqual(error.localizedDescription, String(localized: "暂时无法开始聊天，请稍后再试。")); XCTAssertFalse(error is AuthenticationError) }
        }
        let client = api(.init(status: 404, data: Data()))
        let missing = try await client.request("chats/missing", allowMissing: true); XCTAssertTrue(missing is NSNull)
    }

    func testMenuEndpoint401ChecksSessionBeforeReportingExpiration() async throws {
        for path in ["sync/devices", "sync/sessions?archived=true", "sync/search?q=test", "push", "push/installations/phone"] {
            for sessionStatus in [200, 401, 403, 500] {
                let confirmed = expectation(description: "Account session checked for \(path): \(sessionStatus)")
                let client = api(.init(reply: { request in
                    XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
                    if request.url?.path == "/api/account/session" {
                        XCTAssertEqual(request.httpMethod, "GET")
                        confirmed.fulfill()
                        return (sessionStatus, Data("{\"principalId\":\"owner\",\"tenant\":\"tenant\"}".utf8))
                    }
                    return (401, Data())
                }))
                do { _ = try await client.request(path); XCTFail("Feature request must still fail") }
                catch { XCTAssertEqual(error is AuthenticationError, sessionStatus == 401) }
                await fulfillment(of: [confirmed], timeout: 2)
            }
        }
    }

    @MainActor func testUnsupportedMenuRequestsPreserveAccountAndLocalData() async throws {
        let client = api(.init(reply: { request in
            request.url?.path == "/api/account/session"
                ? (200, Data("{\"principalId\":\"owner\",\"tenant\":\"tenant\",\"nickname\":\"Owner\"}".utf8))
                : (401, Data())
        }))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        var saved: Credentials? = client.credentials
        var cleared = false
        let account = Account(loadCredentials: { saved }, saveCredentials: { saved = $0 },
                              clearCredentials: { cleared = true; saved = nil }, storeRoot: root,
                              sessionChanged: { _ in }, request: { _, path, body in
            try await client.request(path, body: body)
        })
        await account.restore()
        let credentials = try XCTUnwrap(account.credentials)
        let store = try XCTUnwrap(account.store)
        for path in ["sync/devices", "sync/search?q=test", "push", "push/installations/phone"] {
            do { _ = try await client.request(path); XCTFail("Unsupported request should fail") }
            catch { account.handle(error, using: credentials) }
            XCTAssertEqual(account.credentials, credentials)
            XCTAssertEqual(saved, credentials)
            XCTAssertFalse(cleared)
            XCTAssertTrue(account.store === store)
            XCTAssertNoThrow(try store.requireActive())
        }
    }
    func testCancellationStopsTransportAndMalformedJSONDoesNotSucceed() async throws {
        let started = expectation(description: "Pending request started")
        let stopped = expectation(description: "Transport stopped")
        let pending = api(.init(started: { _ in started.fulfill() }, stopped: { stopped.fulfill() }, pending: true))
        let task = Task { try await pending.request("session") }
        await fulfillment(of: [started], timeout: 2); task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch { XCTAssertTrue(error is CancellationError || (error as? URLError)?.code == .cancelled) }
        await fulfillment(of: [stopped], timeout: 2)
        let malformed = api(.init(data: Data("not-json".utf8)))
        do { _ = try await malformed.request("session"); XCTFail("Expected JSON failure") } catch { }
    }
    func testRedirectDelegateRejectsCredentialForwarding() throws {
        let client = api(.init())
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let request = try client.makeRequest("session")
        let task = session.dataTask(with: request)
        let response = HTTPURLResponse(url: request.url!, statusCode: 302, httpVersion: nil, headerFields: ["Location": "https://other.invalid/session"])!
        let redirected = URLRequest(url: URL(string: "https://other.invalid/session")!)
        var called = false
        client.urlSession(session, task: task, willPerformHTTPRedirection: response, newRequest: redirected) { request in
            called = true; XCTAssertNil(request)
        }
        XCTAssertTrue(called)
    }
    @MainActor func testPushRegistrationAndCancellationTransmitPersistedRevision() async throws {
        let value = PushInstallation(id: "installation", secret: String(repeating: "ab", count: 32), revision: 12)
        let registered = expectation(description: "Push PUT")
        let response = try JSONSerialization.data(withJSONObject: ["bindingId": "binding", "available": true, "expiresAt": Date().addingTimeInterval(86400).timeIntervalSince1970 * 1000])
        let client = api(.init(data: response, started: { request in
            XCTAssertEqual(request.httpMethod, "PUT")
            XCTAssertEqual(request.url?.path, "/api/account/push/installations/installation")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
            var data = request.httpBody
            if data == nil, let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var bytes = [UInt8](repeating: 0, count: 4096)
                let count = stream.read(&bytes, maxLength: bytes.count)
                if count > 0 { data = Data(bytes.prefix(count)) }
            }
            let body = data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
            XCTAssertEqual((body?["revision"] as? NSNumber)?.int64Value, 12)
            XCTAssertEqual(body?["secret"] as? String, value.secret)
            XCTAssertEqual(body?["token"] as? String, String(repeating: "cd", count: 32))
            XCTAssertEqual(body?["approvals"] as? Bool, true)
            XCTAssertEqual(body?["questions"] as? Bool, false)
            registered.fulfill()
        }))
        let result = try await HTTPPushService(api: client).register(value, token: String(repeating: "cd", count: 32), preferences: PushPreferences(approvals: true))
        XCTAssertEqual(result.0, "binding"); XCTAssertTrue(result.2)
        await fulfillment(of: [registered], timeout: 2)
        let removed = expectation(description: "Push DELETE")
        let deletion = api(.init(started: { request in
            XCTAssertEqual(request.httpMethod, "DELETE")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Installation-Secret"), value.secret)
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Installation-Revision"), "12")
            removed.fulfill()
        }))
        try await HTTPPushService(api: deletion).remove(value)
        await fulfillment(of: [removed], timeout: 2)
    }

    @MainActor func testForkExactLookupDistinguishesMissingFromAuthenticationFailure() async throws {
        let source = Conversation(["id": "source", "device_id": "device"])!
        let request = ForkRequest(source: source, epoch: "epoch", sequence: 1)
        for code in [404, 401, 403] {
            let received = expectation(description: "Exact fork lookup")
            let client = api(.init(status: code, started: { value in
                XCTAssertEqual(value.httpMethod, "GET")
                if value.url?.path == "/api/account/session" {
                    XCTAssertEqual(code, 401)
                    return
                }
                XCTAssertEqual(value.url?.path, "/api/account/sync/sessions/source/commands/" + request.id)
                received.fulfill()
            }))
            do {
                let value = try await HTTPConversationService(api: client, sessionID: "source").forkReceipt(request)
                XCTAssertEqual(code, 404); XCTAssertNil(value)
            } catch {
                XCTAssertNotEqual(code, 404)
                if code == 401 { XCTAssertTrue(error is AuthenticationError) }
                else { XCTAssertTrue(error is ClientError) }
            }
            await fulfillment(of: [received], timeout: 2)
        }
        do { _ = try await api(.init(status: 404)).request("sync/sessions/source"); XCTFail("Other 404s must remain errors") }
        catch { XCTAssertTrue(error is ClientError) }
    }
    @MainActor func testForkLookupRejectsAnotherCommandKind() async throws {
        let source = Conversation(["id": "source", "device_id": "device"])!
        let request = ForkRequest(source: source, epoch: "epoch", sequence: 1)
        let data = try JSONSerialization.data(withJSONObject: ["id": request.id, "kind": "prompt", "status": "accepted", "result": ["sessionId": "child"]])
        do {
            _ = try await HTTPConversationService(api: api(.init(data: data)), sessionID: "source").forkReceipt(request)
            XCTFail("A prompt receipt must not open a fork")
        } catch { XCTAssertTrue(error is ClientError) }
    }

    @MainActor func testModelBudgetFailureUsesQuotaCopyWithoutExpiringSession() async {
        let api = api(.init(status: 429, data: Data("{\"code\":\"USER_MODEL_BUDGET_EXHAUSTED\",\"message\":\"private provider diagnostic\"}".utf8)))
        do { _ = try await api.request("chats/chat/turns", body: [:]); XCTFail("quota denied") }
        catch { XCTAssertTrue(error is ClientError); XCTAssertFalse(error is AuthenticationError); XCTAssertEqual(error.localizedDescription, ModelAllowanceFailure.message("USER_MODEL_BUDGET_EXHAUSTED")) }
    }
}
