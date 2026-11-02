import XCTest
@testable import FunnyWork

/// Explicitly enabled with a private configuration file; never replay an execute request.
@MainActor final class LiveMobileModelTests: XCTestCase {
    func testPublishedModelsAndThinkingThroughNativeService() async throws {
        guard let path = ProcessInfo.processInfo.environment["FUNNYWORK_MOBILE_MODEL_CONFIG"] else { throw XCTSkip("Live mobile model checks are opt-in") }
        let config = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as? [String: String])
        let origin = try API.serverURL(try XCTUnwrap(config["origin"]))
        XCTAssertTrue(OfficialService.managed(origin))
        let anonymous = API(Credentials(server: origin, token: ""))
        let response = try await anonymous.request("login", body: ["username": try XCTUnwrap(config["username"]), "password": try XCTUnwrap(config["password"]), "client": "ios", "device": "ios-model-" + UUID().uuidString])
        let login = try XCTUnwrap(response as? [String: Any])
        let api = API(Credentials(server: origin, token: try XCTUnwrap(login["token"] as? String)))
        let service = HTTPMobileChatService(api: api)
        let output = URL(fileURLWithPath: try XCTUnwrap(config["report"]))
        var records: [[String: Any]] = []
        func save() throws { try JSONSerialization.data(withJSONObject: ["platform": "ios", "records": records], options: [.prettyPrinted, .sortedKeys]).write(to: output, options: .atomic) }
        do {
            let models = try await service.models().filter { !$0.reasoningEfforts.isEmpty }
            XCTAssertGreaterThanOrEqual(models.count, 4)
            for model in models {
                for effort in model.reasoningEfforts {
                    let send = MobileChatSend(chatID: UUID().uuidString, turnID: UUID().uuidString, text: "Reply only FW_OK.", modelID: model.id, revision: 0, reasoningEffort: effort, maxOutputTokens: 128)
                    records.append(["chat": send.chatID, "turn": send.turnID, "model": model.model ?? model.id, "route": model.id, "effort": effort, "executeAttempted": false]); try save()
                    try await service.prepare(send)
                    var snapshot = try await service.snapshot(send.chatID)
                    for _ in 0..<60 where snapshot?.ticketState == "WAITING" { try await Task.sleep(for: .seconds(1)); snapshot = try await service.snapshot(send.chatID) }
                    XCTAssertEqual(snapshot?.ticketState, "GRANTED")
                    guard snapshot?.ticketState == "GRANTED" else { try await service.cancel(send); throw ClientError.invalidResponse }
                    records[records.count - 1]["executeAttempted"] = true; try save()
                    var text = ""; try await service.execute(send) { text += $0 }
                    for _ in 0..<30 {
                        snapshot = try await service.snapshot(send.chatID)
                        if snapshot?.ticketState == "SUCCEEDED" { break }
                        try await Task.sleep(for: .milliseconds(200))
                    }
                    XCTAssertEqual(snapshot?.ticketState, "SUCCEEDED")
                    XCTAssertEqual(snapshot?.turns.first?.state, "SUCCEEDED")
                    XCTAssertFalse(text.isEmpty); XCTAssertEqual(text, snapshot?.turns.first?.reply)
                    records[records.count - 1]["ticketState"] = snapshot?.ticketState
                    records[records.count - 1]["streamCharacters"] = text.count; try save()
                }
            }
            _ = try await api.request("logout", body: [:])
        } catch {
            _ = try? await api.request("logout", body: [:]); throw error
        }
    }
}
