import XCTest
@testable import FunnyWork

final class BrandIdentityTests: XCTestCase {
    func testBinaryAndSupportedLocalizationsUseFunnyWork() throws {
        let bundle = Bundle(for: Account.self)
        XCTAssertEqual(bundle.infoDictionary?["CFBundleDisplayName"] as? String, "FunnyWork")
        XCTAssertEqual(bundle.infoDictionary?["CFBundleName"] as? String, "FunnyWork")
        XCTAssertEqual(bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String, "FunnyWork")
        XCTAssertEqual(bundle.object(forInfoDictionaryKey: "CFBundleIdentifier") as? String, "com.funnyworker.ios")
        for language in ["en", "zh-Hans"] {
            let path = try XCTUnwrap(bundle.path(forResource: language, ofType: "lproj"))
            let localized = try XCTUnwrap(Bundle(path: path))
            XCTAssertEqual(localized.localizedString(forKey: "CFBundleDisplayName", value: nil, table: "InfoPlist"), "FunnyWork")
            XCTAssertEqual(localized.localizedString(forKey: "CFBundleName", value: nil, table: "InfoPlist"), "FunnyWork")
        }
    }
}
