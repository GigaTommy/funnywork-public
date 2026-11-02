import XCTest
import CoreImage
import UIKit
@testable import FunnyWork

@MainActor private final class DeviceLinkStub: DeviceLinkService {
    let linkServer = URL(string: "https://example.com")!
    var inspections = 0
    var confirmations = 0
    var confirmed = false
    var confirmationFailure: Error?
    var wait = false
    var waitConfirm = false
    var applyConfirmation = true
    var epoch = "one"
    var didInspect: (() -> Void)?
    var continuation: CheckedContinuation<DeviceLinkPreview, Error>?
    func value() throws -> DeviceLinkPreview {
        try DeviceLinkPreview(["id": "code", "deviceId": "mac", "name": "My Mac", "platform": "darwin", "epoch": epoch, "expiresIn": 120000, "confirmed": confirmed])
    }
    func inspectLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview {
        inspections += 1; didInspect?()
        if wait { return try await withCheckedThrowingContinuation { continuation = $0 } }
        return try value()
    }
    func confirmLink(_ code: DeviceLinkCode) async throws -> DeviceLinkPreview {
        confirmations += 1; confirmed = applyConfirmation
        if waitConfirm { return try await withCheckedThrowingContinuation { continuation = $0 } }
        if let confirmationFailure { throw confirmationFailure }
        return try value()
    }
}
@MainActor final class DeviceLinkTests: XCTestCase {
    private let code = "funnywork://connect?v=1&server=https%3A%2F%2Fexample.com&id=code&secret=" + String(repeating: "a", count: 64)
    func testParserAcceptsOnlySameHTTPSOriginWithoutCredentialsOrDuplicateFields() throws {
        let server = URL(string: "https://example.com:443/")!
        XCTAssertEqual(try DeviceLinkCode(code, server: server).id, "code")
        for invalid in [code + "&id=other", code + "#fragment", code.replacingOccurrences(of: "v=1", with: "v=2"), code.replacingOccurrences(of: "https%3A", with: "http%3A"), code.replacingOccurrences(of: "example.com", with: "example.com.evil.test"), code.replacingOccurrences(of: "example.com", with: "user%3Apass%40example.com"), code.replacingOccurrences(of: "id=code", with: "id=../other"), code.replacingOccurrences(of: "example.com", with: "example.com%3A444"), "https://example.com", code + "&extra=1"] {
            XCTAssertThrowsError(try DeviceLinkCode(invalid, server: server))
        }
    }
    func testMismatchedOriginNeverMakesAnAuthenticatedRequest() async {
        let service = DeviceLinkStub()
        let actual = DeviceLinkModel(service: service)
        await actual.scan(code.replacingOccurrences(of: "example.com", with: "other.example.com"))
        XCTAssertEqual(service.inspections, 0); XCTAssertNotNil(actual.error); XCTAssertFalse(actual.canConfirm)
    }
    func testScanRequiresExplicitConfirmationAndLostResponseIsReadBack() async {
        let service = DeviceLinkStub(); let model = DeviceLinkModel(service: service)
        await model.scan(code)
        XCTAssertTrue(model.canConfirm); XCTAssertEqual(service.confirmations, 0); XCTAssertNil(model.connectedID)
        service.confirmationFailure = URLError(.networkConnectionLost)
        await model.confirm()
        XCTAssertEqual(model.connectedID, "mac"); XCTAssertEqual(service.confirmations, 1); XCTAssertEqual(service.inspections, 2)
        await model.confirm(); XCTAssertEqual(service.confirmations, 1)
    }
    func testExpiryAndLeavingDiscardLateResponses() async throws {
        var time: TimeInterval = 100
        let service = DeviceLinkStub(); let model = DeviceLinkModel(service: service, now: { time })
        await model.scan(code); time += 121; model.expire()
        XCTAssertNil(model.preview); XCTAssertFalse(model.canConfirm); XCTAssertNotNil(model.error)
        await model.confirm(); XCTAssertEqual(service.confirmations, 0)
        service.wait = true
        let scanning = Task { await model.scan(code) }
        while service.continuation == nil { await Task.yield() }
        model.clear(); service.continuation?.resume(returning: try service.value()); await scanning.value
        XCTAssertNil(model.preview); XCTAssertNil(model.error); XCTAssertFalse(model.busy)
    }
    func testUnconfirmedReadbackDoesNotConnectOrReplay() async {
        let service = DeviceLinkStub(); let model = DeviceLinkModel(service: service)
        await model.scan(code)
        service.applyConfirmation = false; service.confirmationFailure = URLError(.networkConnectionLost)
        await model.confirm()
        XCTAssertNil(model.connectedID); XCTAssertNotNil(model.error); XCTAssertEqual(service.confirmations, 1)
        XCTAssertEqual(service.inspections, 2)
    }
    func testChangedRuntimeIsRejectedAfterConfirmation() async {
        let service = DeviceLinkStub(); let model = DeviceLinkModel(service: service)
        await model.scan(code); service.epoch = "two"
        await model.confirm(); XCTAssertNil(model.connectedID); XCTAssertNotNil(model.error)
    }
    func testLeavingDuringConfirmationDiscardsLateResult() async throws {
        let service = DeviceLinkStub(); let model = DeviceLinkModel(service: service)
        await model.scan(code); service.waitConfirm = true
        let confirmation = Task { await model.confirm() }
        while service.continuation == nil { await Task.yield() }
        model.clear(); service.continuation?.resume(returning: try service.value()); await confirmation.value
        XCTAssertNil(model.connectedID); XCTAssertNil(model.preview); XCTAssertFalse(model.busy)
    }
    func testInspectionLatencyCannotExtendCodeDeadline() async {
        var time: TimeInterval = 100
        let service = DeviceLinkStub(); let model = DeviceLinkModel(service: service, now: { time })
        service.didInspect = { time += 121 }
        await model.scan(code)
        XCTAssertFalse(model.canConfirm); XCTAssertNil(model.preview); XCTAssertNotNil(model.error)
    }
    func testMalformedResponseRejectsBooleanDeadlineAndNumericConfirmation() throws {
        let row: [String: Any] = ["id": "code", "deviceId": "mac", "name": "My Mac", "platform": "darwin", "epoch": "one", "expiresIn": 120000, "confirmed": false]
        for changes: [String: Any] in [["expiresIn": true], ["expiresIn": 120001], ["expiresIn": 0], ["expiresIn": 0.5], ["confirmed": 1], ["epoch": ""], ["id": "../a"]] {
            XCTAssertThrowsError(try DeviceLinkPreview(row.merging(changes) { _, b in b }))
        }
    }
    func testDesktopQRPNGDecodesToSameConnectionURI() throws {
        // Fixture is generated by the desktop's QR encoder, never contains a real connection secret.
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "device-link", withExtension: "png"))
        let image = try XCTUnwrap(CIImage(contentsOf: url))
        let detector = try XCTUnwrap(CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(options: [.useSoftwareRenderer: true]), options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        XCTAssertEqual(detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first, code)
    }
}
