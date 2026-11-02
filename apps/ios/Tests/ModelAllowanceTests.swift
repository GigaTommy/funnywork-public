import XCTest
@testable import FunnyWork

private func wallet(_ remaining: Any = 1000000, claimable: Int = 1000000) -> [String: Any] {
    ["configured": true, "enabled": true, "remaining_micros": remaining, "held": 200000,
     "daily_limit": 1000000, "daily_spent": 300000, "expires_at": 1900000000000,
     "promotion": ["mode": "DAILY", "available_micros": claimable]]
}
@MainActor private final class AllowanceServiceFixture: ModelAllowanceService {
    var calls: [Bool] = []
    var failure: Error?
    var continuation: CheckedContinuation<ModelAllowance, Error>?
    var deferResult = false
    func fetch(claim: Bool) async throws -> ModelAllowance {
        calls.append(claim)
        if let failure { throw failure }
        if deferResult { return try await withCheckedThrowingContinuation { continuation = $0 } }
        return try ModelAllowance(claim ? ["credited_micros": 1000000, "budget": wallet(2000000, claimable: 0)] : wallet(), claim: claim)
    }
}
@MainActor final class ModelAllowanceTests: XCTestCase {
    func testWalletAndClaimKeepReservedMoneyAndDailySpendingSeparate() async throws {
        let service = AllowanceServiceFixture(), model = ModelAllowanceModel(service: service, authFailure: { _ in XCTFail("not expired") })
        await model.refresh(); XCTAssertEqual(model.allowance?.remaining, 1000000); XCTAssertEqual(model.allowance?.dailyRemaining, 500000)
        await model.refresh(claim: true); XCTAssertEqual(model.allowance?.remaining, 2000000); XCTAssertEqual(model.allowance?.credited, 1000000)
        XCTAssertEqual(model.allowance?.claimable, 0); XCTAssertEqual(service.calls, [false, true])
    }
    func testRejectsUnsafeMoneyAndHandlesUnconfiguredWallet() throws {
        for value: Any in [-1, 0.5, true, "1000000", 9007199254740992] { XCTAssertThrowsError(try ModelAllowance(wallet(value))) }
        let empty: [String: Any] = ["configured": false, "enabled": false, "remaining_micros": 0, "promotion": ["mode": "WELCOME", "available_micros": 1000000]]
        let value = try ModelAllowance(empty); XCTAssertFalse(value.active); XCTAssertEqual(value.held, 0); XCTAssertEqual(value.claimable, 1000000)
    }
    func testLateResultAfterClosingCannotRestoreAllowance() async throws {
        let service = AllowanceServiceFixture(); service.deferResult = true
        let model = ModelAllowanceModel(service: service, authFailure: { _ in XCTFail("not expired") })
        let task = Task { await model.refresh(claim: true) }
        while service.continuation == nil { await Task.yield() }
        model.deactivate(); service.continuation?.resume(returning: try ModelAllowance(wallet()))
        await task.value; XCTAssertNil(model.allowance); XCTAssertFalse(model.busy)
    }
    func testReservedFundsHaveDistinctRecoveryCopy() {
        for code in ["USER_MODEL_FUNDS_RESERVED", "GLOBAL_MODEL_FUNDS_RESERVED"] {
            XCTAssertNotNil(ModelAllowanceFailure.message(code))
            XCTAssertNotEqual(ModelAllowanceFailure.message(code), ModelAllowanceFailure.message("USER_MODEL_BUDGET_EXHAUSTED"))
        }
    }
    func testQuotaFailurePreservesAccountWhileAuthenticationFailureNotifiesOwner() async {
        let service = AllowanceServiceFixture(); var authenticationFailures = 0
        let model = ModelAllowanceModel(service: service, authFailure: { _ in authenticationFailures += 1 })
        service.failure = ClientError(message: ModelAllowanceFailure.message("USER_MODEL_BUDGET_EXHAUSTED")!)
        await model.refresh(); XCTAssertNotNil(model.error); XCTAssertEqual(authenticationFailures, 0)
        service.failure = AuthenticationError(); await model.refresh(); XCTAssertEqual(authenticationFailures, 1)
    }
}
