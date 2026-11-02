#if DEBUG
import SwiftUI
@MainActor private final class FixtureAllowanceService: ModelAllowanceService {
    private var claimed = false
    func fetch(claim: Bool) async throws -> ModelAllowance {
        if ProcessInfo.processInfo.arguments.contains("--allowance-offline") { throw URLError(.notConnectedToInternet) }
        if claim { claimed = true }
        let inactive = ProcessInfo.processInfo.arguments.contains("--allowance-inactive")
        let row: [String: Any] = ["configured": true, "enabled": !inactive, "remaining_micros": claimed ? 2000000 : 1000000,
            "held": 100000, "daily_limit": 5000000, "daily_spent": 500000, "expires_at": 1900000000000,
            "promotion": ["mode": "DAILY", "available_micros": claimed || inactive ? 0 : 1000000]]
        return try ModelAllowance(claim ? ["credited_micros": 1000000, "budget": row] : row, claim: claim)
    }
}
struct ModelAllowanceFixtureScreen: View {
    private let service = FixtureAllowanceService()
    @State private var showing = true
    var body: some View {
        Theme.surface.ignoresSafeArea().sheet(isPresented: $showing) {
            AccountSettingsView(name: "Tommy", allowanceService: service, logout: {})
        }
    }
}
#endif
