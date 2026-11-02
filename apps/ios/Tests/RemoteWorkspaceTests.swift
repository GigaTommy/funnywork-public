import XCTest
@testable import FunnyWork
@MainActor final class RemoteWorkspaceTests: XCTestCase {
    func testBrowsingEditingAndGitUseSelectedWorkspaceAndClearReceipt() async throws {
        let service = WorkspaceFixtureService()
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let device = try await service.devices()[0]
        let model = RemoteWorkspaceModel(device: device, service: service, directory: service, defaults: defaults)
        await model.browse(); XCTAssertEqual(model.files.map(\.name), ["README.md"])
        await model.open(model.files[0]); XCTAssertEqual(model.editingPath, "README.md")
        model.content = "new content"; await model.save(); XCTAssertEqual(model.revision, String(repeating: "b", count: 64)); XCTAssertFalse(model.pending)
        await model.loadGit(); XCTAssertEqual(model.currentBranch, "main"); XCTAssertEqual(model.branches, ["main", "feature"])
        XCTAssertNil(defaults.string(forKey: service.workspaceCacheKey + "-device"))
    }
    func testRestoresPendingIdentityWithoutResubmission() async throws {
        let service = WorkspaceFixtureService()
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let id = UUID().uuidString
        defaults.set(id, forKey: service.workspaceCacheKey + "-device")
        let model = RemoteWorkspaceModel(device: try await service.devices()[0], service: service, directory: service, defaults: defaults)
        XCTAssertTrue(model.pending)
        let blocked = await model.perform("branchCreate", values: ["branch": "new"])
        XCTAssertNil(blocked)
        await model.checkPending(); XCTAssertFalse(model.pending)
    }
    func testOversizeEditorDoesNotSubmitOrClearDraft() async throws {
        let service = WorkspaceFixtureService()
        let model = RemoteWorkspaceModel(device: try await service.devices()[0], service: service, directory: service, defaults: UserDefaults(suiteName: UUID().uuidString)!)
        model.editingPath = "file"; model.content = String(repeating: "你", count: 22000); model.revision = "new"
        await model.save(); XCTAssertNotNil(model.error); XCTAssertFalse(model.pending); XCTAssertEqual(model.content.utf8.count, 66000)
    }
}
