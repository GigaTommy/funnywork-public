#if DEBUG
import SwiftUI

@MainActor private struct DeviceLinkFixtureService: DeviceLinkService {
    let linkServer = URL(string: "https://example.com")!
    func inspectLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview { try result(code, confirmed: false) }
    func confirmLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview { try result(code, confirmed: true) }
    private func result(_ code: DeviceLinkCode, confirmed: Bool) throws -> DeviceLinkPreview {
        if ProcessInfo.processInfo.arguments.contains("--ui-test-link-expired") { throw ClientError(message: String(localized: "连接码已失效或设备离线，请在桌面刷新后重新扫描。")) }
        return try DeviceLinkPreview(["id": code.id, "deviceId": "mac", "name": "Tommy 的 Mac", "platform": "darwin", "epoch": "one", "expiresIn": 120000, "confirmed": confirmed])
    }
}
struct DeviceLinkFixtureScreen: View {
    @State private var connectedID: String?
    var body: some View {
        if let connectedID { Text(connectedID).accessibilityIdentifier("linked-device") }
        else {
            DeviceLinkView(service: DeviceLinkFixtureService(), fixtureCode: "funnywork://connect?v=1&server=https%3A%2F%2Fexample.com&id=code&secret=" + String(repeating: "a", count: 64)) { connectedID = $0 }
        }
    }
}
#endif
