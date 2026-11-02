import XCTest
@testable import FunnyWork

final class ModelProviderTests: XCTestCase {
    func testInterleavedModelsShareOneManufacturerSectionAndPreserveSelectionIDs() {
        let options = [ChatModelOption(id: "route-a", name: "GPT-6.1 Sol", model: "gpt-6.1-sol"),
                       ChatModelOption(id: "route-b", name: "DeepSeek Flash", model: "deepseek-chat"),
                       ChatModelOption(id: "route-c", name: "GPT-6 Luna", model: "openai/gpt-6-luna")]
        let groups = ChatModelOption.grouped(options)
        XCTAssertEqual(groups.map(\.provider), ["OpenAI", "DeepSeek"])
        XCTAssertEqual(groups[0].models.map(\.id), ["route-a", "route-c"])
        XCTAssertEqual(options[0].label, "GPT-6.1 Sol")
    }
    func testExplicitMetadataAndUnknownModelsRemainIndependentOfDisplayNames() {
        XCTAssertEqual(ChatModelOption(id: "a", name: "GPT-6", model: "unknown").provider, "Custom")
        XCTAssertEqual(ChatModelOption(id: "a", name: "Private", model: "unknown", providerName: "Enterprise").provider, "Enterprise")
        for (model, provider) in [("o3", "OpenAI"), ("claude-sonnet", "Anthropic"), ("gemini-pro", "Google"), ("qwen3", "Alibaba"), ("kimi-k2", "Moonshot"), ("grok-4", "xAI")] {
            XCTAssertEqual(ChatModelOption(id: "a", name: "Route", model: model).provider, provider)
        }
        XCTAssertTrue(ChatModelOption.grouped([]).isEmpty)
    }
}

@MainActor private final class ParallelStartupService: MobileChatService {
    let started = XCTestExpectation(description: "Both startup reads began")
    var resumes: [CheckedContinuation<Void, Never>] = []
    var released = false
    init() { started.expectedFulfillmentCount = 2 }
    private func gate() async {
        if released { return }
        await withCheckedContinuation { continuation in
            resumes.append(continuation); started.fulfill()
        }
    }
    func release() { released = true; let pending = resumes; resumes = []; pending.forEach { $0.resume() } }
    func models() async throws -> [ChatModelOption] { await gate(); return [ChatModelOption(id: "route", name: "GPT", model: "gpt-6")] }
    func list() async throws -> [MobileChatSummary] { await gate(); return [] }
    func snapshot(_ id: String) async throws -> MobileChatSnapshot? { nil }
    func prepare(_ send: MobileChatSend) async throws { XCTFail("Startup must not request inference") }
    func execute(_ send: MobileChatSend, delta: @escaping (String) -> Void) async throws { XCTFail("Startup must not execute inference") }
    func cancel(_ send: MobileChatSend) async throws {}
}

@MainActor final class MobileStartupTests: XCTestCase {
    func testCatalogAndHistoryStartTogetherWithoutInference() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let service = ParallelStartupService()
        let model = MobileChatModel(service: service, store: ConversationStore(key: "parallel", root: root))
        let refresh = Task { await model.refresh() }
        await fulfillment(of: [service.started], timeout: 3)
        service.release(); await refresh.value
        XCTAssertEqual(model.options.map(\.id), ["route"])
        XCTAssertEqual(model.modelID, "route")
        XCTAssertNil(model.error)
    }
}
