import XCTest
@testable import FunnyWork

@MainActor private final class DirectoryServiceStub: ConversationDirectoryService {
    var rows: [Conversation] = []
    var cursors: [String] = []
    var failureAfter: String?
    var failRename = false
    var failArchive = false
    var archiveStarted: XCTestExpectation?
    var archiveResponse: CheckedContinuation<Int64, Error>?
    func archive(id: String, archived: Bool, revision: Int64) async throws -> Int64 {
        if failArchive { throw URLError(.timedOut) }
        if let started = archiveStarted {
            return try await withCheckedThrowingContinuation { archiveResponse = $0; started.fulfill() }
        }
        guard let index = rows.firstIndex(where: { $0.id == id }), rows[index].archiveRevision == revision else { throw ClientError.invalidResponse }
        rows[index].archived = archived; rows[index].archiveRevision = revision + 1
        return revision + 1
    }
    func rename(id: String, title: String, revision: Int64) async throws -> Int64 {
        if failRename { throw URLError(.timedOut) }
        guard let index = rows.firstIndex(where: { $0.id == id }), rows[index].titleRevision == revision else { throw ClientError.invalidResponse }
        rows[index].title = title; rows[index].titleRevision = revision + 1
        return revision + 1
    }
    func page(after: String) async throws -> [Conversation] {
        cursors.append(after)
        if failureAfter == after { throw URLError(.notConnectedToInternet) }
        return Array(rows.filter { $0.id > after }.prefix(100))
    }
}
@MainActor final class ConversationDirectoryTests: XCTestCase {
    func testNavigationRestoresWithinAccountAndRejectsLateWrites() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "first", root: root)
        var value = SavedConversationNavigation(); value.scope = "desktop"; value.desktopID = "task"
        try store.saveNavigation(value)
        XCTAssertEqual(try ConversationStore(key: "first", root: root).loadNavigation().desktopID, "task")
        XCTAssertNil(try ConversationStore(key: "other", root: root).loadNavigation().desktopID)
        try store.invalidateAndClear()
        XCTAssertThrowsError(try store.saveNavigation(value))
        XCTAssertEqual(try ConversationStore(key: "first", root: root).loadNavigation().scope, "mobile")
    }
    func testRemoteHostLabelRemovesOnlyMatchingIdentifier() {
        XCTAssertEqual(remoteHostName("MacBook · abc123", deviceID: "abc123"), "MacBook")
        XCTAssertEqual(remoteHostName("MacBook-abc123", deviceID: "abc123"), "MacBook")
        XCTAssertEqual(remoteHostName("MacBook-Pro", deviceID: "abc123"), "MacBook-Pro")
        XCTAssertEqual(remoteHostName("MacBook-123", deviceID: ""), "MacBook-123")
    }
    func testRemoteConnectionUsesDevicePresenceAndIgnoresRevokedDevices() throws {
        func device(_ id: String, online: Bool, revoked: Bool = false) throws -> CreationDevice {
            try CreationDevice(["id": id, "name": id, "online": online, "epoch": "epoch", "workspaces": [[String: Any]](), "revoked": revoked])
        }
        XCTAssertEqual(RemoteConnectionState.resolve([]), .unlinked)
        XCTAssertEqual(RemoteConnectionState.resolve([try device("mac", online: false)]), .offline)
        XCTAssertEqual(RemoteConnectionState.resolve([try device("mac", online: true)]), .online)
        XCTAssertEqual(RemoteConnectionState.resolve([try device("mac", online: true, revoked: true)]), .unlinked)
    }
    func testPriorityGroupsDoNotDuplicatePinsAndDoNotRestoreStaleLiveState() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store=ConversationStore(key:"account",root:root)
        let service=DirectoryServiceStub()
        service.rows=[
            Conversation(["id":"a","title":"Approve","online":true,"next_seq":2,"task_state":"approval","task_state_remaining_ms":30000])!,
            Conversation(["id":"b","title":"Running","online":true,"next_seq":2,"task_state":"running","task_state_remaining_ms":30000])!,
            Conversation(["id":"c","title":"Unread","online":true,"next_seq":2])!,
            Conversation(["id":"d","title":"Idle","online":true])!]
        let model=ConversationDirectory(service:service,store:store);await model.refresh();model.togglePin("a")
        let now=Date()
        XCTAssertEqual(model.grouped(at:now).map(\.id),[.attention,.active,.unread,.earlier])
        XCTAssertEqual(model.grouped(at:now).flatMap(\.rows).map(\.id),["a","b","c","d"])
        for id in ["a","b","c"] { try store.markRead(through:2,for:id) }
        XCTAssertFalse(model.grouped(at:now).contains { $0.id == .unread })
        XCTAssertEqual(model.grouped(at:now.addingTimeInterval(31)).first?.id,.pinned)
        let offline=ConversationDirectory(service:service,store:store)
        XCTAssertFalse(offline.groups.contains { [.attention,.active].contains($0.id) })
        model.deviceID="missing";XCTAssertTrue(model.groups.isEmpty)
    }
    func testArchiveAndRestorePreservePinsAndSurviveOfflineReload() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub()
        let original = Conversation(["id": "a", "title": "Notes", "archived": false, "archive_revision": 0])!
        service.rows = [original]
        let model = ConversationDirectory(service: service, store: store); await model.refresh(); model.togglePin("a")
        let archived = await model.setArchived(original, to: true)
        XCTAssertTrue(archived); XCTAssertTrue(model.visible.isEmpty)
        XCTAssertEqual(model.pinnedIDs, ["a"])
        model.showingArchived = true; XCTAssertEqual(model.visible.map(\.id), ["a"])
        XCTAssertNotEqual(model.groups.first?.id, .pinned)
        let offline = ConversationDirectory(service: service, store: store); offline.showingArchived = true
        XCTAssertEqual(offline.visible.map(\.id), ["a"])
        let stale = await model.setArchived(original, to: false)
        XCTAssertFalse(stale); XCTAssertEqual(model.visible.count, 1)
        service.failArchive = true
        let failed = await model.setArchived(model.rows[0], to: false)
        XCTAssertFalse(failed); XCTAssertEqual(model.visible.count, 1)
        service.failArchive = false
        let restored = await model.setArchived(model.rows[0], to: false)
        XCTAssertTrue(restored); XCTAssertTrue(model.visible.isEmpty)
        model.showingArchived = false; XCTAssertEqual(model.groups.first?.id, .pinned)
        await model.refresh(); XCTAssertEqual(model.visible.map(\.id), ["a"])
    }
    func testArchiveResponseAfterLogoutCannotWriteBack() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub()
        let original = Conversation(["id": "a", "archived": false, "archive_revision": 0])!
        service.rows = [original]
        let model = ConversationDirectory(service: service, store: store); await model.refresh()
        let started = expectation(description: "Archive submitted"); service.archiveStarted = started
        let saving = Task { await model.setArchived(original, to: true) }
        await fulfillment(of: [started], timeout: 2)
        let calls = service.cursors.count
        await model.refresh(); XCTAssertEqual(service.cursors.count, calls)
        try store.invalidateAndClear()
        service.archiveResponse?.resume(returning: 1)
        let result = await saving.value
        XCTAssertFalse(result); XCTAssertEqual(model.rows.first?.archived, false)
        XCTAssertNil(try ConversationStore(key: "account", root: root).loadDirectory())
    }
    func testRenamePersistsSearchesAndRefreshesWithoutChangingMessageDate() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub()
        service.rows = [Conversation(["id": "a", "title": "Old", "title_revision": 0, "updated_at": 1000])!]
        let model = ConversationDirectory(service: service, store: store); await model.refresh()
        let renamed = await model.rename("a", to: " New title ")
        XCTAssertTrue(renamed); XCTAssertEqual(model.rows.first?.title, "New title")
        XCTAssertEqual(model.rows.first?.titleRevision, 1)
        model.search = "New"; XCTAssertEqual(model.visible.count, 1)
        await model.refresh(); XCTAssertEqual(model.rows.first?.title, "New title")
        let restored = ConversationDirectory(service: service, store: store)
        XCTAssertEqual(restored.rows.first?.title, "New title")
        XCTAssertEqual(restored.rows.first?.updatedAt, Date(timeIntervalSince1970: 1))
        let stale = await model.rename("a", to: "Old editor", expectedRevision: 0)
        XCTAssertFalse(stale); XCTAssertEqual(service.rows.first?.title, "New title")
        service.failRename = true
        let failed = await model.rename("a", to: "Not saved")
        XCTAssertFalse(failed); XCTAssertEqual(model.rows.first?.title, "New title")
        XCTAssertNotNil(model.error)
        store.invalidate()
        let invalidated = await model.rename("a", to: "Another")
        XCTAssertFalse(invalidated)
    }
    func testOldCachedConversationsRemainReadableWithoutRenameCapability() async throws {
        let data = Data(#"{"id":"a","title":"Old","device":"Mac","online":false,"deviceID":"mac"}"#.utf8)
        let cached = try JSONDecoder().decode(Conversation.self, from: data)
        XCTAssertNil(cached.titleRevision)
        let service = DirectoryServiceStub(); service.rows = [cached]
        let model = ConversationDirectory(service: service); await model.refresh()
        let renamed = await model.rename("a", to: "New")
        XCTAssertFalse(renamed); XCTAssertEqual(model.rows.first?.title, "Old")
    }
    func testPinsPersistAndRespectSearchDeviceAndRecentOrder() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub()
        service.rows = [row("a", title: "Login", device: "one", updated: 1000), row("b", title: "Notes", device: "two", updated: 2000), row("c", title: "Login fix", device: "one", updated: 3000)]
        let model = ConversationDirectory(service: service, store: store); await model.refresh()
        model.togglePin("a"); model.togglePin("b")
        XCTAssertEqual(model.groups.first?.id, .pinned)
        XCTAssertEqual(model.groups.first?.rows.map(\.id), ["b", "a"])
        XCTAssertEqual(model.groups.flatMap(\.rows).map(\.id), ["b", "a", "c"])
        let restored = ConversationDirectory(service: service, store: store)
        XCTAssertEqual(restored.pinnedIDs, ["a", "b"])
        restored.search = "Login"; restored.deviceID = "one"
        XCTAssertEqual(restored.groups.flatMap(\.rows).map(\.id), ["a", "c"])
        restored.togglePin("a")
        XCTAssertEqual(restored.groups.flatMap(\.rows).map(\.id), ["c", "a"])
        XCTAssertEqual(try store.loadPinnedConversations(), ["b"])
        XCTAssertTrue(try ConversationStore(key: "other", root: root).loadPinnedConversations().isEmpty)
        try store.invalidateAndClear()
        XCTAssertTrue(try ConversationStore(key: "account", root: root).loadPinnedConversations().isEmpty)
    }
    func testFailedPinSaveDoesNotPublishUnsavedState() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub(); service.rows = [row("a")]
        let model = ConversationDirectory(service: service, store: store); await model.refresh()
        model.togglePin("a")
        store.invalidate()
        model.togglePin("a")
        XCTAssertEqual(model.pinnedIDs, ["a"])
        XCTAssertNotNil(model.error)
    }
    func testDateGroupsUseLocalDaysAndKeepInputOrder() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let today = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 3, day: 9)))
        func dated(_ id: String, days: Int) -> Conversation {
            let date = calendar.date(byAdding: .day, value: days, to: today)!
            return row(id, updated: Int(date.timeIntervalSince1970 * 1000))
        }
        let undated = Conversation(["id": "unknown", "title": "Unknown"])!
        let groups = ConversationDateGroup.group([
            dated("future", days: 1), dated("today", days: 0), dated("yesterday", days: -1),
            dated("week", days: -7), dated("month", days: -30), dated("old", days: -31), undated
        ], now: today, calendar: calendar)
        XCTAssertEqual(groups.map(\.id), [.today, .yesterday, .week, .month, .earlier])
        XCTAssertEqual(groups.map { $0.rows.map(\.id) }, [["future", "today"], ["yesterday"], ["week"], ["month"], ["old", "unknown"]])
        XCTAssertTrue(ConversationDateGroup.group([], now: today, calendar: calendar).isEmpty)
    }
    private func row(_ id: String, title: String = "Conversation", device: String = "mac", name: String = "Mac", updated: Int = 0) -> Conversation {
        Conversation(["id": id, "title": title, "device_id": device, "device_name": name, "updated_at": updated, "online": true])!
    }
    func testAllPagesUseIDCursorButDisplayRecentActivityFirst() async {
        let service = DirectoryServiceStub()
        service.rows = (0..<205).map { row(String(format: "%03d", $0), updated: $0 * 1000) }
        let model = ConversationDirectory(service: service)
        await model.refresh()
        XCTAssertEqual(service.cursors, ["", "099", "199"])
        XCTAssertEqual(model.rows.count, 205)
        XCTAssertEqual(model.visible.first?.id, "204")
        XCTAssertEqual(model.visible.last?.id, "000")
    }
    func testSearchAndDeviceFilterIntersectAndUseStableIDs() async {
        let service = DirectoryServiceStub()
        service.rows = [row("a", title: "Login form", device: "one", name: "Mac"), row("b", title: "Login fix", device: "two", name: "Mac"), row("c", title: "Release notes", device: "three", name: "Windows")]
        let model = ConversationDirectory(service: service); await model.refresh()
        XCTAssertEqual(model.devices.count, 3)
        model.search = " login "; model.deviceID = "two"
        XCTAssertEqual(model.visible.map(\.id), ["b"])
        model.clearFilters(); model.search = "Windows"
        XCTAssertEqual(model.visible.map(\.id), ["c"])
        model.search = "absent"
        XCTAssertTrue(model.visible.isEmpty)
        XCTAssertTrue(model.filtered)
    }
    func testFailedRefreshKeepsExistingDirectory() async {
        let service = DirectoryServiceStub(); service.rows = [row("a", title: "Saved")]
        let model = ConversationDirectory(service: service); await model.refresh()
        service.rows = (0..<101).map { row(String(format: "%03d", $0)) }
        service.failureAfter = "099"
        await model.refresh()
        XCTAssertEqual(model.rows.map(\.id), ["a"])
        XCTAssertNotNil(model.error)
        XCTAssertFalse(model.loading)
        service.failureAfter = nil
        await model.refresh()
        XCTAssertEqual(model.rows.count, 101)
        XCTAssertNil(model.error)
    }
    func testFailedInitialPaginationDoesNotPublishPartialHistory() async {
        let service = DirectoryServiceStub()
        service.rows = (0..<101).map { row(String(format: "%03d", $0)) }
        service.failureAfter = "099"
        let model = ConversationDirectory(service: service)
        await model.refresh()
        XCTAssertTrue(model.rows.isEmpty)
        XCTAssertNotNil(model.error)
        service.failureAfter = nil
        await model.refresh()
        XCTAssertEqual(model.rows.count, 101)
    }
    func testInvalidatedAccountDoesNotStartDirectoryRequest() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub()
        store.invalidate()
        let model = ConversationDirectory(service: service, store: store)
        await model.refresh()
        XCTAssertTrue(service.cursors.isEmpty)
        XCTAssertTrue(model.rows.isEmpty)
    }
    func testRemovedDeviceFilterResetsOnlyAfterSuccessfulRefresh() async {
        let service = DirectoryServiceStub(); service.rows = [row("a", device: "old")]
        let model = ConversationDirectory(service: service); await model.refresh(); model.deviceID = "old"
        service.failureAfter = ""; await model.refresh()
        XCTAssertEqual(model.deviceID, "old")
        service.failureAfter = nil; service.rows = [row("b", device: "new")]; await model.refresh()
        XCTAssertNil(model.deviceID)
        XCTAssertEqual(model.visible.map(\.id), ["b"])
    }
    func testCachedDirectorySurvivesOutageAndReconcilesRemoval() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConversationStore(key: "account", root: root)
        let service = DirectoryServiceStub(); service.rows = [row("a", title: "Saved")]
        let first = ConversationDirectory(service: service, store: store); await first.refresh()
        let restored = ConversationDirectory(service: service, store: store)
        XCTAssertEqual(restored.rows.map(\.id), ["a"])
        XCTAssertTrue(restored.showingCache)
        service.failureAfter = ""; await restored.refresh()
        XCTAssertEqual(restored.rows.map(\.id), ["a"])
        service.failureAfter = nil; service.rows = []; await restored.refresh()
        XCTAssertFalse(restored.showingCache)
        XCTAssertTrue(restored.rows.isEmpty)
        XCTAssertTrue(try XCTUnwrap(store.loadDirectory()).rows.isEmpty)
    }

}
