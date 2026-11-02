import XCTest
@testable import FunnyWork

@MainActor private final class AccountFixture {
    var saved: Credentials?
    var writes: [Credentials] = []
    var calls: [String] = []
    var deferredPath = "session"
    var deferredToken = "old"
    let started = XCTestExpectation(description: "Request suspended")
    var response: CheckedContinuation<Any, Error>?
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)

    func makeAccount() -> Account {
        Account(loadCredentials: { self.saved }, saveCredentials: {
            self.saved = $0; self.writes.append($0)
        }, clearCredentials: { self.saved = nil }, storeRoot: root, request: { credentials, path, body in
            self.calls.append(path)
            if path == self.deferredPath && (path == "login" || credentials.token == self.deferredToken) {
                return try await withCheckedThrowingContinuation {
                    self.response = $0; self.started.fulfill()
                }
            }
            if path == "login" { return ["token": body?["username"] as? String ?? ""] }
            if path == "session" { return self.profile(credentials.token) }
            return [:] as [String: String]
        })
    }

    func profile(_ principal: String) -> [String: String] {
        ["principalId": principal, "tenant": "tenant", "nickname": principal]
    }
    func credentials(_ token: String) -> Credentials {
        Credentials(server: URL(string: "https://example.com")!, token: token, principalID: token, tenant: "tenant")
    }
    func clean() throws {
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
    }
}

@MainActor final class AccountTests: XCTestCase {
    func testPictureSaveRefreshAndLogout() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let credentials = Credentials(server: URL(string: "https://example.com")!, token: "picture", principalID: "user", tenant: "tenant")
        var storedPicture = ""
        let account = Account(loadCredentials: { credentials }, saveCredentials: { _ in }, clearCredentials: {}, storeRoot: root, sessionChanged: { _ in }, request: { _, path, body in
            if path == "avatar" { storedPicture = body?["image"] as? String ?? ""; return [:] as [String: String] }
            return ["principalId": "user", "tenant": "tenant", "nickname": "Gigi", "avatarUrl": storedPicture]
        })
        await account.restore()
        try await account.saveAvatar("data:image/jpeg;base64,AAAA")
        XCTAssertEqual(account.avatarURL, storedPicture)
        storedPicture = "data:image/png;base64,BBBB"
        await account.refreshProfile()
        XCTAssertEqual(account.avatarURL, storedPicture)
        XCTAssertEqual(account.name, "Gigi")
        await account.logout()
        XCTAssertEqual(account.avatarURL, "")
    }

    func testRestoreCannotSignBackInAfterLogout() async throws {
        let fixture = AccountFixture(); defer { try? fixture.clean() }
        fixture.saved = fixture.credentials("old")
        let account = fixture.makeAccount()
        let restoring = Task { await account.restore() }
        await fulfillment(of: [fixture.started], timeout: 2)
        let oldStore = try XCTUnwrap(account.store)
        await account.logout()
        XCTAssertThrowsError(try oldStore.requireActive())
        fixture.response?.resume(returning: fixture.profile("old"))
        await restoring.value
        XCTAssertNil(account.credentials); XCTAssertNil(account.store)
        XCTAssertNil(fixture.saved); XCTAssertTrue(fixture.writes.isEmpty)
        XCTAssertFalse(account.busy)
    }

    func testOldProfileCannotOverwriteNewAccount() async {
        let fixture = AccountFixture(); defer { try? fixture.clean() }
        let account = fixture.makeAccount()
        let oldLogin = Task { await account.login(server: "https://example.com", username: "old", password: "test") }
        await fulfillment(of: [fixture.started], timeout: 2)
        await account.login(server: "https://example.com", username: "new", password: "test")
        fixture.response?.resume(returning: fixture.profile("old"))
        await oldLogin.value
        XCTAssertEqual(account.credentials, fixture.credentials("new"))
        XCTAssertEqual(fixture.saved, account.credentials)
        XCTAssertEqual(fixture.writes.map(\.token), ["new"])
        XCTAssertEqual(account.name, "new"); XCTAssertFalse(account.busy)
    }

    func testOldAuthenticationFailureCannotClearNewAccount() async {
        let fixture = AccountFixture(); defer { try? fixture.clean() }
        let account = fixture.makeAccount()
        let oldLogin = Task { await account.login(server: "https://example.com", username: "old", password: "test") }
        await fulfillment(of: [fixture.started], timeout: 2)
        await account.login(server: "https://example.com", username: "new", password: "test")
        fixture.response?.resume(throwing: AuthenticationError())
        await oldLogin.value
        XCTAssertEqual(account.credentials, fixture.credentials("new"))
        XCTAssertEqual(fixture.saved, account.credentials); XCTAssertNil(account.error)
    }

    func testLogoutDiscardsPendingLoginBeforeRequestingProfile() async {
        let fixture = AccountFixture(); defer { try? fixture.clean() }
        fixture.deferredPath = "login"
        let account = fixture.makeAccount()
        let login = Task { await account.login(server: "https://example.com", username: "old", password: "test") }
        await fulfillment(of: [fixture.started], timeout: 2)
        await account.logout()
        fixture.response?.resume(returning: ["token": "old"])
        await login.value
        XCTAssertEqual(fixture.calls, ["login"])
        XCTAssertNil(account.credentials); XCTAssertNil(fixture.saved)
        XCTAssertTrue(fixture.writes.isEmpty); XCTAssertFalse(account.busy)
    }

    func testAuthenticationFailureMustMatchWholeCredentialScope() async throws {
        let fixture = AccountFixture(); defer { try? fixture.clean() }
        let account = fixture.makeAccount()
        await account.login(server: "https://example.com", username: "new", password: "test")
        let current = try XCTUnwrap(account.credentials)
        let store = try XCTUnwrap(account.store)
        let otherServer = Credentials(server: URL(string: "https://other.example.com")!, token: current.token,
                                      principalID: current.principalID, tenant: current.tenant)
        account.handle(AuthenticationError(), using: otherServer)
        var otherPrincipal = current; otherPrincipal.principalID = "other"
        account.handle(AuthenticationError(), using: otherPrincipal)
        XCTAssertEqual(account.credentials, current); XCTAssertNil(account.error)
        account.handle(AuthenticationError(), using: current)
        XCTAssertNil(account.credentials); XCTAssertNil(fixture.saved)
        XCTAssertThrowsError(try store.requireActive())
    }

    func testRelaunchRestoresSavedAccountAndRetainsItThroughNetworkFailure() async throws {
        let fixture = AccountFixture(); defer { try? fixture.clean() }
        let first = fixture.makeAccount(); await first.login(server: "https://example.com", username: "new", password: "test")
        let persisted = try XCTUnwrap(fixture.saved)
        let relaunched = fixture.makeAccount(); await relaunched.restore(); XCTAssertEqual(relaunched.credentials, persisted); XCTAssertNotNil(relaunched.store)
        let offline = Account(loadCredentials: { persisted }, saveCredentials: { _ in XCTFail("Offline restoration should not replace credentials") }, clearCredentials: { XCTFail("Network failures must not clear credentials") }, storeRoot: fixture.root, sessionChanged: { _ in }, request: { _, _, _ in throw URLError(.notConnectedToInternet) })
        await offline.restore(); XCTAssertEqual(offline.credentials, persisted); XCTAssertNotNil(offline.store)
    }

}
