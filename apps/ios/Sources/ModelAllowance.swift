import SwiftUI
import CoreFoundation

struct ModelAllowance: Equatable {
    let configured: Bool
    let enabled: Bool
    let remaining: Int64
    let held: Int64
    let dailyRemaining: Int64
    let expiresAt: Date?
    let mode: String
    let claimable: Int64
    let credited: Int64
    init(_ value: Any, claim: Bool = false) throws {
        guard let envelope = value as? [String: Any], let row = (claim ? envelope["budget"] : value) as? [String: Any],
              let configured = row["configured"] as? Bool, let enabled = row["enabled"] as? Bool,
              let promo = row["promotion"] as? [String: Any], let mode = promo["mode"] as? String,
              ["DISABLED", "WELCOME", "DAILY"].contains(mode) else { throw ClientError.invalidResponse }
        func amount(_ source: [String: Any], _ key: String) throws -> Int64 {
            guard let n = source[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue >= 0,
                  n.doubleValue <= 9007199254740991, n.doubleValue == Double(n.int64Value) else { throw ClientError.invalidResponse }
            return n.int64Value
        }
        self.configured = configured; self.enabled = enabled; self.mode = mode
        remaining = try amount(row, "remaining_micros")
        held = configured ? try amount(row, "held") : 0
        dailyRemaining = configured ? max(0, try amount(row, "daily_limit") - amount(row, "daily_spent") - held) : 0
        expiresAt = configured ? Date(timeIntervalSince1970: Double(try amount(row, "expires_at")) / 1000) : nil
        claimable = try amount(promo, "available_micros")
        credited = claim ? try amount(envelope, "credited_micros") : 0
    }
    static func money(_ micros: Int64) -> String { String(format: "¥%.6f", Double(micros) / 1000000) }
    var active: Bool { configured && enabled && (expiresAt ?? .distantPast) > Date() }
}

@MainActor protocol ModelAllowanceService {
    func fetch(claim: Bool) async throws -> ModelAllowance
}
@MainActor struct HTTPModelAllowanceService: ModelAllowanceService {
    let api: API
    func fetch(claim: Bool) async throws -> ModelAllowance {
        let value = try await api.request(claim ? "inference/budget/claim" : "inference/budget", body: claim ? [:] : nil)
        return try ModelAllowance(value, claim: claim)
    }
}
@MainActor final class ModelAllowanceModel: ObservableObject {
    @Published private(set) var allowance: ModelAllowance?
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    private let service: any ModelAllowanceService
    private let authFailure: (Error) -> Void
    private var generation = UUID()
    init(service: any ModelAllowanceService, authFailure: @escaping (Error) -> Void) { self.service = service; self.authFailure = authFailure }
    func refresh(claim: Bool = false) async {
        guard !busy else { return }
        let attempt = UUID(); generation = attempt; busy = true; error = nil
        defer { if generation == attempt { busy = false } }
        do {
            let value = try await service.fetch(claim: claim)
            try Task.checkCancellation()
            guard generation == attempt else { return }
            allowance = value
        } catch {
            guard generation == attempt, !(error is CancellationError), (error as? URLError)?.code != .cancelled else { return }
            allowance = nil; self.error = ClientError.displayMessage(for: error)
            if error is AuthenticationError { authFailure(error) }
        }
    }
    func deactivate() { generation = UUID(); busy = false }
}

struct ModelAllowanceSection: View {
    @StateObject private var model: ModelAllowanceModel
    init(service: any ModelAllowanceService, authFailure: @escaping (Error) -> Void) {
        _model = StateObject(wrappedValue: ModelAllowanceModel(service: service, authFailure: authFailure))
    }
    var body: some View {
        Section {
            if let wallet = model.allowance {
                LabeledContent("可用余额", value: ModelAllowance.money(wallet.remaining)).accessibilityIdentifier("allowance-remaining")
                LabeledContent("已预留", value: ModelAllowance.money(wallet.held))
                LabeledContent("今日可用", value: ModelAllowance.money(wallet.dailyRemaining))
                if let expires = wallet.expiresAt { LabeledContent("有效期") { Text(expires, style: .date) } }
                if !wallet.active { Text("额度未开通、已停用或过期，请联系管理员。").font(.caption).foregroundStyle(.secondary) }
                else if wallet.remaining == 0 { Text("额度已用完，请联系管理员充值。").font(.caption).foregroundStyle(.secondary) }
                if wallet.credited > 0 { LabeledContent("本次已领取", value: ModelAllowance.money(wallet.credited)) }
                if wallet.claimable > 0 {
                    Button("领取额度") { Task { await model.refresh(claim: true) } }.disabled(model.busy).accessibilityIdentifier("allowance-claim")
                }
            }
            if model.busy { ProgressView("正在读取额度…") }
            if let error = model.error { Text(error).font(.caption).foregroundStyle(.secondary) }
            Button("刷新额度") { Task { await model.refresh() } }.disabled(model.busy).accessibilityIdentifier("allowance-refresh")
        } header: { Text("模型额度") }
        footer: { Text("额度由管理员充值。推理会先预留费用，完成后结算。") }
        .listRowBackground(Theme.input)
        .task { await model.refresh() }
        .onDisappear { model.deactivate() }
    }
}

/// Server error codes carry no provider diagnostics or credentials into UI copy.
enum ModelAllowanceFailure {
    static func message(_ code: String) -> String? {
        switch code {
        case "USER_MODEL_BUDGET_EXHAUSTED": return String(localized: "可用额度不足，请查看余额或联系管理员充值。")
        case "USER_MODEL_FUNDS_RESERVED", "GLOBAL_MODEL_FUNDS_RESERVED": return String(localized: "费用已由未完成任务预留，请稍后重试。")
        case "MODEL_BUDGET_REQUIRED": return String(localized: "尚未开通模型额度，请领取额度或联系管理员。")
        case "MODEL_BUDGET_EXPIRED": return String(localized: "模型额度已停用或过期，请联系管理员。")
        case "GLOBAL_MODEL_BUDGET_EXHAUSTED", "MODEL_BUDGET_PAUSED", "MODEL_PRICE_REQUIRED": return String(localized: "模型服务暂时不可用，请联系管理员。")
        case "USER_OUTPUT_LIMIT": return String(localized: "单次输出超过账号限额，请刷新模型后重试。")
        default: return nil
        }
    }
    static func error(_ data: Data) -> ClientError? {
        guard let row = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = row["code"] as? String, let message = message(code) else { return nil }
        return ClientError(message: message)
    }
}
