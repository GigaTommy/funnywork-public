import Foundation
import CoreFoundation

private func metricNumber(_ row: [String: Any], _ key: String) -> Double? {
    guard let n = row[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(),
          n.doubleValue.isFinite, n.doubleValue >= 0, n.doubleValue <= 9007199254740991 else { return nil }
    return n.doubleValue
}

struct ChatPerformanceReading: Codable, Equatable {
    let queue: Double
    let dispatch: Double
    let request: Double
    let total: Double
    let firstOutput: Double?
    let decode: Double?
    let decodeTokens: Double?
    init?(_ row: [String: Any]) {
        guard let queue = metricNumber(row, "queue_ms"), let dispatch = metricNumber(row, "dispatch_ms"),
              let request = metricNumber(row, "request_ms"), let total = metricNumber(row, "total_ms"),
              abs(total - queue - dispatch - request) < 0.5 else { return nil }
        let first = metricNumber(row, "first_output_ms"), decode = metricNumber(row, "decode_ms"), tokens = metricNumber(row, "decode_tokens")
        if row["first_output_ms"] != nil && first == nil { return nil }
        if let first, first > request { return nil }
        if row["decode_ms"] != nil || row["decode_tokens"] != nil {
            guard let decode, decode > 0, let tokens, tokens.rounded() == tokens, let first,
                  abs(first + decode - request) < 0.5 else { return nil }
        }
        self.queue = queue; self.dispatch = dispatch; self.request = request; self.total = total
        firstOutput = first; self.decode = decode; decodeTokens = tokens
    }
}

struct ChatPerformanceSummary: Codable, Equatable {
    var queue: Double? = nil
    var dispatch: Double? = nil
    var request: Double? = nil
    var total: Double? = nil
    var tool: Double? = nil
    var firstOutputSum: Double = 0
    var firstOutputCalls: Int = 0
    var decodeMS: Double = 0
    var decodeTokens: Double = 0
    var decodeCalls: Int? = 0
    var measuredCalls = 0
    var unmeasuredCalls = 0
    var desktop = false
    var firstOutput: Double? { firstOutputCalls > 0 ? firstOutputSum / Double(firstOutputCalls) : nil }
    var speed: Double? { decodeMS > 0 ? decodeTokens * 1000 / decodeMS : nil }
    var available: Bool { measuredCalls > 0 }
    mutating func add(_ reading: ChatPerformanceReading) {
        measuredCalls += 1; queue = (queue ?? 0) + reading.queue; dispatch = (dispatch ?? 0) + reading.dispatch
        request = (request ?? 0) + reading.request; total = (total ?? 0) + reading.total
        if let first = reading.firstOutput { firstOutputSum += first; firstOutputCalls += 1 }
        if let ms = reading.decode, let tokens = reading.decodeTokens { decodeMS += ms; decodeTokens += tokens; decodeCalls = (decodeCalls ?? 0) + 1 }
    }
    init() {}
    init(_ reading: ChatPerformanceReading) { self.init(); add(reading) }
    init?(remote row: [String: Any]) {
        guard let steps = metricNumber(row, "steps"), steps > 0, steps.rounded() == steps,
              let request = metricNumber(row, "llmMs"), let tool = metricNumber(row, "toolMs"),
              let ttft = metricNumber(row, "ttftMs"), let ttftSteps = metricNumber(row, "ttftSteps"),
              ttftSteps.rounded() == ttftSteps, ttftSteps <= steps,
              let decode = metricNumber(row, "decodeMs"), let tokens = metricNumber(row, "decodeTokens"), tokens.rounded() == tokens else { return nil }
        self.request = request; self.tool = tool; firstOutputSum = ttft; firstOutputCalls = Int(ttftSteps)
        decodeMS = decode; decodeTokens = tokens; measuredCalls = Int(steps); decodeCalls = nil; desktop = true
    }
}
