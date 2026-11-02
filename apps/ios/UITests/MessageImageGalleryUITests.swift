import XCTest
import UIKit

final class MessageImageGalleryUITests: XCTestCase {
    func testCopyImageFeedbackKeepsZoomAndReturnsToConversationInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-message-image", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let thumbnail = app.images.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-image-")).firstMatch
            XCTAssertTrue(thumbnail.waitForExistence(timeout: 10)); thumbnail.tap()
            let image = app.images["gallery-image"]
            XCTAssertTrue(image.waitForExistence(timeout: 10)); image.doubleTap()
            let zoom = image.value as? String
            XCTAssertNotEqual(zoom, "100%")
            XCTAssertTrue(app.buttons["gallery-copy"].exists); XCTAssertTrue(app.buttons["gallery-save"].exists)
            XCTAssertTrue(app.buttons["gallery-share"].exists)
            app.buttons["gallery-copy"].tap()
            XCTAssertTrue(app.staticTexts["gallery-copied"].waitForExistence(timeout: 5))
            XCTAssertEqual(image.value as? String, zoom)
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "copy-image-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            app.buttons["完成"].tap()
            XCTAssertTrue(app.textFields["conversation-composer"].waitForExistence(timeout: 5))
            app.terminate()
        }
    }
    func testDirectPreviewZoomPagingAndShareInBothAppearances() {
        let app = XCUIApplication()
        for appearance in ["light", "dark"] {
            app.launchArguments = ["--ui-test-conversation", "--ui-test-message-image", "--ui-test-gallery", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)", "-appearance", appearance]
            app.launch(); app.buttons["示例对话"].tap()
            let thumbnail = app.images.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-image-")).firstMatch
            XCTAssertTrue(thumbnail.waitForExistence(timeout: 10)); thumbnail.tap()
            let image = app.images["gallery-image"]
            XCTAssertTrue(image.waitForExistence(timeout: 10)); XCTAssertEqual(image.value as? String, "100%")
            XCTAssertEqual(app.staticTexts["gallery-position"].label, "1 / 2")
            XCTAssertGreaterThan(image.frame.height, app.frame.height * 0.5)
            let screenshot = app.screenshot()
            XCTAssertTrue(fullImageVisible(screenshot.image), "The 2:1 image must render at its fitted height")
            let shot = XCTAttachment(screenshot: screenshot); shot.name = "image-gallery-\(appearance)"; shot.lifetime = .keepAlways; add(shot)
            image.doubleTap()
            XCTAssertTrue(NSPredicate(format: "value != %@", "100%").evaluate(with: image))
            image.doubleTap()
            XCTAssertTrue(NSPredicate(format: "value == %@", "100%").evaluate(with: image))
            XCTAssertTrue(fullImageVisible(app.screenshot().image), "Resetting zoom must restore the complete image")
            image.swipeLeft()
            XCTAssertTrue(app.staticTexts["gallery-position"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["gallery-position"].label, "2 / 2")
            XCTAssertTrue(image.waitForExistence(timeout: 10)); XCTAssertEqual(image.value as? String, "100%")
            app.buttons["gallery-previous"].tap()
            XCTAssertEqual(app.staticTexts["gallery-position"].label, "1 / 2")
            XCTAssertTrue(image.waitForExistence(timeout: 10))
            app.buttons["gallery-share"].tap()
            XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5) || app.buttons["关闭"].exists)
            app.terminate()
        }
    }
    func testClosingReturnsToConversationAndOfflineReopenUsesVerifiedCache() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-message-image", "--ui-test-reset", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        let thumbnail = app.images.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-image-")).firstMatch
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 10)); thumbnail.tap()
        XCTAssertTrue(app.images["gallery-image"].waitForExistence(timeout: 10))
        app.buttons["完成"].tap(); XCTAssertTrue(app.textFields["conversation-composer"].waitForExistence(timeout: 5))
        app.terminate()
        app.launchArguments = ["--ui-test-conversation", "--ui-test-message-image", "--ui-test-offline", "-AppleLanguages", "(zh-Hans)"]
        app.launch(); app.buttons["示例对话"].tap()
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 10)); thumbnail.tap()
        XCTAssertTrue(app.images["gallery-image"].waitForExistence(timeout: 10))
    }
    private func fullImageVisible(_ image: UIImage) -> Bool {
        guard let source = image.cgImage else { return false }
        let width = source.width, height = source.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = bytes.withUnsafeMutableBytes { data -> Bool in
            guard let context = CGContext(data: data.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height)); return true
        }
        guard drawn else { return false }
        // The fixture is a 2:1 neutral gray image. Sample away from its text to detect cropped rendering.
        let column = width * 9 / 10
        let grayRows = (0..<height).filter { row in
            let offset = (row * width + column) * 4
            return (195...220).contains(Int(bytes[offset])) && abs(Int(bytes[offset]) - Int(bytes[offset + 1])) < 3
                && abs(Int(bytes[offset]) - Int(bytes[offset + 2])) < 3
        }.count
        return Double(grayRows) >= Double(width) * 0.45
    }

}
