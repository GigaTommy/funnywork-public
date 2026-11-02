import XCTest
import UIKit
@testable import FunnyWork

@MainActor final class CameraCaptureTests: XCTestCase {
    func testPermissionDenialAndUnavailableDeviceDoNotPresentCamera() async {
        var requests = 0
        let unavailable = CameraCapture(available: false, authorize: { requests += 1; return true })
        await unavailable.open()
        XCTAssertEqual(requests, 0); XCTAssertFalse(unavailable.presented)
        XCTAssertFalse(unavailable.needsSettings); XCTAssertNotNil(unavailable.error)
        let denied = CameraCapture(available: true, authorize: { false })
        await denied.open()
        XCTAssertFalse(denied.presented); XCTAssertTrue(denied.needsSettings); XCTAssertNotNil(denied.error)
    }
    func testPermissionResponseAfterCancellationCannotOpenCamera() async {
        let started = expectation(description: "Permission requested")
        var response: CheckedContinuation<Bool, Never>?
        let camera = CameraCapture(available: true, authorize: {
            await withCheckedContinuation { response = $0; started.fulfill() }
        })
        let opening = Task { await camera.open() }
        await fulfillment(of: [started], timeout: 2)
        camera.cancel(); response?.resume(returning: true); await opening.value
        XCTAssertFalse(camera.presented); XCTAssertFalse(camera.requesting)
        XCTAssertNil(camera.takePhoto())
    }
    func testCapturedPhotoIsConsumedOnceAndCancellationDropsLateImages() async throws {
        let camera = CameraCapture(available: true, authorize: { true })
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 12)).image { context in
            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 8, height: 12))
        }
        await camera.open(); XCTAssertTrue(camera.presented)
        camera.finish(image); XCTAssertFalse(camera.presented)
        let captured = try XCTUnwrap(camera.takePhoto()); XCTAssertNil(camera.takePhoto())
        let photo = try await PhotoAttachment.captured(captured)
        XCTAssertEqual(photo.file.mediaType, "image/jpeg")
        XCTAssertTrue(photo.file.name.hasSuffix(".jpg"))
        XCTAssertEqual(photo.file.bytes, photo.data.count)
        let decoded = try XCTUnwrap(UIImage(data: photo.data))
        XCTAssertEqual(decoded.cgImage?.width, captured.cgImage?.width)
        XCTAssertEqual(decoded.cgImage?.height, captured.cgImage?.height)
        let rotated = UIImage(cgImage: try XCTUnwrap(captured.cgImage), scale: 1, orientation: .right)
        let rotatedPhoto = try await PhotoAttachment.captured(rotated)
        let rotatedDecoded = try XCTUnwrap(UIImage(data: rotatedPhoto.data))
        XCTAssertEqual(rotatedDecoded.imageOrientation, .right)
        await camera.open(); camera.cancel(); camera.finish(image)
        XCTAssertNil(camera.takePhoto()); XCTAssertFalse(camera.presented)
        await camera.open(); camera.finish(nil); XCTAssertNil(camera.takePhoto())
    }
}
