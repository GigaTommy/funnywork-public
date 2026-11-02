import SwiftUI
import CoreFoundation

struct MobileChatSearchMatch: Codable, Equatable {
    let turnID: String?
    let field: String
    let excerpt: String
    let date: Date
    init(_ row: [String: Any]) throws {
        guard let field = row["field"] as? String, ["title", "user", "reply"].contains(field),
              let excerpt = row["excerpt"] as? String, excerpt.utf16.count <= 500,
              let timestamp = row["created_at"] as? NSNumber, CFGetTypeID(timestamp) != CFBooleanGetTypeID(),
              timestamp.doubleValue.isFinite, timestamp.doubleValue >= 0, timestamp.doubleValue <= 253402300799000 else { throw ClientError.invalidResponse }
        let turn = row["turnId"] as? String
        guard field == "title" ? turn == nil : turn.map(CreationDevice.validID) == true else { throw ClientError.invalidResponse }
        turnID = turn; self.field = field; self.excerpt = excerpt; date = Date(timeIntervalSince1970: timestamp.doubleValue / 1000)
    }
    var label: String {
        switch field {
        case "user": return String(localized: "你的消息")
        case "reply": return String(localized: "回复")
        default: return String(localized: "标题")
        }
    }
}

struct MobileChatSearchLocation: Equatable, Hashable {
    let chatID: String
    let turnID: String?
    let field: String
    let query: String
    let requestID = UUID()
}

struct SearchMatchText: View {
    let text: String
    let query: String
    var body: some View { Text(highlighted).lineLimit(3) }
    private var highlighted: AttributedString {
        var result = AttributedString(text)
        guard !query.isEmpty else { return result }
        var remaining = text.startIndex..<text.endIndex
        while let range = text.range(of: query, options: [.caseInsensitive], range: remaining) {
            if let start = AttributedString.Index(range.lowerBound, within: result), let end = AttributedString.Index(range.upperBound, within: result) {
                result[start..<end].font = .callout.bold()
            }
            guard range.upperBound > remaining.lowerBound else { break }
            remaining = range.upperBound..<text.endIndex
        }
        return result
    }
}
