import SwiftUI

/// Prompt includes cached input. Unknown cache values never count as zero hits.
struct ChatUsageSummary: Equatable {
    var input: Int64 = 0
    var output: Int64 = 0
    var cacheRead: Int64 = 0
    var cacheWrite: Int64? = nil
    var cacheKnownInput: Int64 = 0
    var confirmedCalls = 0
    var cacheKnownCalls = 0
    var unconfirmedCalls = 0
    var total: Int64 { input + output }
    var partialCache: Bool { cacheKnownCalls < confirmedCalls || unconfirmedCalls > 0 }
    var cachePercent: String? {
        guard cacheKnownInput > 0 else { return nil }
        // Preserve near-total hits without rounding a partial hit to 100%.
        var percent = Decimal(cacheRead) / Decimal(cacheKnownInput) * 100
        var rounded = Decimal(); var precision = 1
        NSDecimalRound(&rounded, &percent, precision, .plain)
        while cacheRead < cacheKnownInput && rounded == 100 && precision < 18 {
            precision += 1; NSDecimalRound(&rounded, &percent, precision, .plain)
        }
        let formatter = NumberFormatter(); formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 1; formatter.maximumFractionDigits = precision
        return (formatter.string(from: NSDecimalNumber(decimal: rounded)) ?? NSDecimalNumber(decimal: rounded).stringValue) + "%"
    }
    mutating func add(_ usage: MobileChatUsage) {
        input += usage.input; output += usage.output; confirmedCalls += 1
        if let cached = usage.cached { cacheRead += cached; cacheKnownInput += usage.input; cacheKnownCalls += 1 }
    }
    init() {}
    init(_ usage: MobileChatUsage) { self.init(); add(usage) }
    init(_ usage: RemoteTokenUsage) {
        input = usage.input + usage.cached; output = usage.output
        cacheRead = usage.cacheRead; cacheWrite = usage.cacheWrite
        cacheKnownInput = input; confirmedCalls = 1; cacheKnownCalls = 1
    }
}

struct ChatModelMenu: View {
    @Binding var modelID: String
    let options: [ChatModelOption]
    let busy: Bool
    let identifier: String
    var reasoningEffort: Binding<String> = .constant("off")
    @State private var showing = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private var current: ChatModelOption? { options.first { $0.id == modelID } }
    var body: some View {
        Button { showing = true } label: {
            Image(systemName: "slider.horizontal.3").font(.body).foregroundStyle(.secondary)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(busy || options.isEmpty)
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .accessibilityLabel("下一轮模型").accessibilityValue(current?.label ?? String(localized: "选择模型"))
            .accessibilityIdentifier(identifier)
            .sheet(isPresented: $showing) {
                NavigationStack {
                    List {
                        Section("当前模型") {
                            Text(verbatim: current?.label ?? String(localized: "所选模型暂不可用"))
                                .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier(identifier + "-current")
                        }
                        if let current, !current.reasoningEfforts.isEmpty {
                            Section("思考档位") {
                                ForEach(current.reasoningEfforts, id: \.self) { value in
                                    Button { if !busy { reasoningEffort.wrappedValue = value } } label: {
                                        HStack {
                                            Text(verbatim: value.capitalized)
                                            Spacer()
                                            if reasoningEffort.wrappedValue == value { Image(systemName: "checkmark") }
                                        }.contentShape(Rectangle())
                                    }.disabled(busy).accessibilityIdentifier(identifier + "-effort-" + value)
                                        .accessibilityAddTraits(reasoningEffort.wrappedValue == value ? .isSelected : [])
                                }
                            }
                        }
                        ForEach(ChatModelOption.grouped(options), id: \.provider) { group in
                            Section {
                                ForEach(group.models) { option in
                                    Button { if !busy { modelID = option.id; showing = false } } label: {
                                        HStack {
                                            Text(verbatim: option.label).fixedSize(horizontal: false, vertical: true)
                                            Spacer(minLength: 12)
                                            if option.id == modelID { Image(systemName: "checkmark") }
                                        }.contentShape(Rectangle())
                                    }.disabled(busy).accessibilityLabel(Text(verbatim: option.label))
                                        .accessibilityIdentifier(identifier + "-option-" + option.id)
                                        .accessibilityAddTraits(option.id == modelID ? .isSelected : [])
                                }
                            } header: { Text(verbatim: group.provider) }
                        }
                    }.scrollContentBackground(.hidden).background(Theme.surface)
                        .navigationTitle("模型").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showing = false } } }
                }.tint(Theme.accent)
                    .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
                    .presentationDragIndicator(.visible)
            }
    }
}

struct ChatUsageMenu: View {
    let summary: ChatUsageSummary
    let model: String
    var performance = ChatPerformanceSummary()
    var title: String = String(localized: "会话用量")
    var identifier = "mobile-chat-usage-menu"
    @State private var showingDetails = false
    var body: some View {
        Button { showingDetails = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "chart.bar")
                if title == String(localized: "本轮用量") { Text("本轮用量") }
            }.font(.caption).foregroundStyle(.secondary)
                .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).dynamicTypeSize(...DynamicTypeSize.xxxLarge).accessibilityLabel(title).accessibilityIdentifier(identifier)
            .sheet(isPresented: $showingDetails) {
                ChatUsageDetails(summary: summary, model: model, title: title, performance: performance)
                    .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            }
    }
}

struct ChatUsageDetails: View {
    let summary: ChatUsageSummary
    let model: String
    let title: String
    var performance = ChatPerformanceSummary()
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if !model.isEmpty { Section("实际使用模型") { Text(verbatim: model).textSelection(.enabled).accessibilityIdentifier("usage-actual-model") } }
                Section("Token 明细") {
                    metric("总用量", summary.confirmedCalls > 0 ? summary.total.formatted() : String(localized: "待更新"))
                    metric("输入总量（含缓存）", summary.confirmedCalls > 0 ? summary.input.formatted() : "—")
                    metric("输出", summary.confirmedCalls > 0 ? summary.output.formatted() : "—")
                    metric("缓存读取", summary.cacheKnownCalls > 0 ? summary.cacheRead.formatted() : String(localized: "未提供"))
                    if let written = summary.cacheWrite { metric("缓存写入", written.formatted()) }
                    if !summary.partialCache && summary.cacheKnownCalls > 0 {
                        metric("未命中输入", (summary.input - summary.cacheRead - (summary.cacheWrite ?? 0)).formatted())
                    }
                    metric(summary.partialCache ? "已知调用缓存命中率" : "缓存命中率", summary.cachePercent ?? "—")
                }
                Section("耗时与速度") {
                    if let ms = performance.queue { metric("排队耗时", duration(ms)) }
                    if let ms = performance.dispatch { metric("获准后提交耗时", duration(ms)) }
                    if let ms = performance.request { metric("模型请求耗时", duration(ms)) }
                    if let ms = performance.total { metric("调用总耗时", duration(ms)) }
                    if let ms = performance.tool { metric("工具耗时", duration(ms)) }
                    if let ms = performance.firstOutput { metric("平均首个输出延迟", duration(ms)) }
                    if let speed = performance.speed { metric("平均输出速度", speed.formatted(.number.precision(.fractionLength(1))) + " tok/s") }
                    if performance.firstOutputCalls > 0 { metric("首个输出样本数", performance.firstOutputCalls.formatted()) }
                    if let calls = performance.decodeCalls, calls > 0 { metric("输出速度样本数", calls.formatted()) }
                    if !performance.available { Text("性能数据暂不可用") }
                    if performance.unmeasuredCalls > 0 { Text("部分调用暂无性能数据") }
                    Text("延迟与速度仅统计有效样本；速度按生成时间加权。")
                    Text(performance.desktop ? "性能数据来自电脑 Harness 的完整会话统计。" : "性能数据为服务端观测值；首个输出包含推理内容，不等同于 GPU 纯解码速度。")
                }
                Section("数据状态") {
                    if summary.unconfirmedCalls > 0 { Text("部分用量尚未确认") }
                    if summary.partialCache {
                        Text("缓存指标仅统计已提供缓存数据的调用。")
                        Text("缓存数据覆盖：\(summary.cacheKnownCalls)/\(summary.confirmedCalls) 次已确认调用")
                    }
                    if summary.confirmedCalls == 0 { Text("用量待更新") }
                    Text("缓存已包含在输入中，不重复计入总用量。")
                }
            }.scrollContentBackground(.hidden).background(Theme.surface)
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() }.accessibilityIdentifier("usage-details-close") } }
                .accessibilityIdentifier("usage-details")
        }.tint(Theme.accent)
    }
    private func duration(_ ms: Double) -> String {
        (ms / 1000).formatted(.number.precision(.fractionLength(2))) + " s"
    }
    private func metric(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) { Text(LocalizedStringKey(label)); Spacer(); Text(verbatim: value).multilineTextAlignment(.trailing).monospacedDigit() }
    }
}
