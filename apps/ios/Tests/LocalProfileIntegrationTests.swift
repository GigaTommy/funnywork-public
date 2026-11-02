import XCTest
import UIKit
import CryptoKit
@testable import FunnyWork

/// Explicit local opt-in. Exercises the real iOS Account and URLSession stack, without UI automation.
@MainActor final class LocalProfileIntegrationTests: XCTestCase {
    func testLocalGigiProfileRoundTrip() async throws {
        let environment = ProcessInfo.processInfo.environment
        try XCTSkipUnless(environment["FUNNYWORK_LOCAL_PROFILE_TEST"] == "1")
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let input = documents.appendingPathComponent("gigi-development-account.json")
        defer { try? FileManager.default.removeItem(at: input) }
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: input)) as? [String: String])
        XCTAssertEqual(json["server"], "https://localhost:8766")
        XCTAssertEqual(json["username"], "Gigi")
        guard json["server"] == "https://localhost:8766", json["username"] == "Gigi" else { XCTFail("Local development account required"); return }
        var saved: Credentials?
        let store = documents.appendingPathComponent("profile-check-conversations", isDirectory: true)
        let account = Account(loadCredentials: { saved }, saveCredentials: { saved = $0 }, clearCredentials: { saved = nil }, storeRoot: store, sessionChanged: { _ in })
        await account.login(server: "https://localhost:8766", username: "Gigi", password: try XCTUnwrap(json["password"]))
        XCTAssertNotNil(account.credentials)
        guard account.credentials != nil else { XCTFail("Development login did not complete"); return }
        let initial = account.avatarURL
        func hash(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
        if let expected = environment["FUNNYWORK_EXPECT_AVATAR_DIGEST"], !expected.isEmpty { XCTAssertEqual(hash(initial), expected) }
        let imageURL = documents.appendingPathComponent("selected-profile-image")
        if environment["FUNNYWORK_PROFILE_WRITE"] == "1" {
            let bytes = try Data(contentsOf: imageURL)
            XCTAssertLessThanOrEqual(bytes.count, 20 * 1024 * 1024)
            let image = try XCTUnwrap(UIImage(data: bytes))
            let ratio = 512 / max(image.size.width, image.size.height)
            let size = CGSize(width: max(1, floor(image.size.width * ratio)), height: max(1, floor(image.size.height * ratio)))
            let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
            let normalized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                UIColor.white.setFill(); UIRectFill(CGRect(origin: .zero, size: size)); image.draw(in: CGRect(origin: .zero, size: size))
            }
            let jpeg = try XCTUnwrap(normalized.jpegData(compressionQuality: 0.85)); XCTAssertLessThanOrEqual(jpeg.count, 256000)
            let picture = "data:image/jpeg;base64," + jpeg.base64EncodedString()
            try await account.saveAvatar(picture)
            XCTAssertEqual(account.avatarURL, picture)
        }
        await account.refreshProfile()
        let peer = Account(loadCredentials: { saved }, saveCredentials: { saved = $0 }, clearCredentials: {}, storeRoot: store.appendingPathComponent("peer"), sessionChanged: { _ in })
        await peer.restore()
        XCTAssertEqual(peer.avatarURL, account.avatarURL)
        let report: [String: Any] = ["environment": "local development", "account": "Gigi", "iosAccountReadPassed": true,
            "iosAccountWritePassed": environment["FUNNYWORK_PROFILE_WRITE"] == "1", "avatarDigest": hash(account.avatarURL), "initialDigest": hash(initial)]
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(to: documents.appendingPathComponent("local-profile-result.json"), options: .atomic)
        await account.logout()
        try? FileManager.default.removeItem(at: store)
    }
}
