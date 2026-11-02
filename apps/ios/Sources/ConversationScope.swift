import SwiftUI

enum ConversationScope: String, CaseIterable {
    case mobile, desktop
    var title: String { self == .mobile ? String(localized: "聊天") : String(localized: "远程") }
}
struct ConversationScopeNavigation {
    let scope: ConversationScope
    let select: (ConversationScope) -> Void
}
private struct ConversationScopeKey: EnvironmentKey {
    static let defaultValue: ConversationScopeNavigation? = nil
}
extension EnvironmentValues {
    var conversationScope: ConversationScopeNavigation? {
        get { self[ConversationScopeKey.self] }
        set { self[ConversationScopeKey.self] = newValue }
    }
}
enum RemoteConnectionState {
    case checking, online, offline, unlinked, unknown, failed, disabled
    var title: String {
        switch self {
        case .failed: return String(localized: "连接失败")
        case .disabled: return String(localized: "已停用")
        case .checking: return String(localized: "检查中")
        case .online: return String(localized: "在线")
        case .offline: return String(localized: "离线")
        case .unlinked: return String(localized: "未连接")
        case .unknown: return String(localized: "状态未知")
        }
    }
    var style: StatusStyle {
        switch self {
        case .checking: return .checking
        case .online: return .online
        case .offline: return .offline
        case .unlinked: return .unlinked
        case .unknown: return .unknown
        case .failed: return .failed
        case .disabled: return .disabled
        }
    }
    static func device(_ device: CreationDevice, fresh: Bool, checking: Bool = false, failed: Bool = false) -> Self {
        if device.revoked { return .disabled }
        if fresh { return device.online ? .online : .offline }
        if failed { return .failed }
        return checking ? .checking : .unknown
    }
    static func resolve(_ devices: [CreationDevice]) -> Self {
        let enabled = devices.filter { !$0.revoked }
        if enabled.isEmpty { return .unlinked }
        return enabled.contains { $0.online } ? .online : .offline
    }
}
@MainActor final class RemoteConnectionMonitor: ObservableObject {
    @Published private(set) var state: RemoteConnectionState = .checking
    private var generation = UUID()
    private let service: (any NewConversationService)?
    private let authFailure: (Error) -> Void
    init(service: (any NewConversationService)?, authFailure: @escaping (Error) -> Void) {
        self.service = service; self.authFailure = authFailure
    }
    func deactivate() { generation = UUID(); state = .unknown }
    func refresh(userInitiated: Bool = false) async {
        guard let service else { state = .unknown; return }
        let attempt = UUID(); generation = attempt
        let previous = state
        if userInitiated || previous == .unknown { state = .checking }
        do {
            let devices = try await service.devices()
            try Task.checkCancellation()
            guard generation == attempt else { return }
            state = .resolve(devices)
        } catch {
            guard generation == attempt else { return }
            if Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled { state = previous; return }
            state = .failed
            if error is AuthenticationError { authFailure(error) }
        }
    }
}
private struct RemoteConnectionKey: EnvironmentKey {
    static let defaultValue: RemoteConnectionState = .unknown
}
extension EnvironmentValues {
    var remoteConnection: RemoteConnectionState {
        get { self[RemoteConnectionKey.self] }
        set { self[RemoteConnectionKey.self] = newValue }
    }
}
struct ConversationScopePicker: View {
    @Environment(\.conversationScope) private var navigation
    @Environment(\.remoteConnection) private var remote
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var body: some View {
        if let navigation {
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: 4)) : AnyLayout(HStackLayout(spacing: 4))
            layout {
                ForEach(ConversationScope.allCases, id: \.self) { scope in
                    Button { navigation.select(scope) } label: {
                        let layout = dynamicTypeSize.isAccessibilitySize
                            ? AnyLayout(VStackLayout(spacing: 2)) : AnyLayout(HStackLayout(spacing: 6))
                        layout {
                            Text(verbatim: scope.title).lineLimit(1)
                            if scope == .desktop {
                                StatusIndicator(title: remote.title, style: remote.style, identifier: "remote-connection-status")
                                    .font(.caption.weight(.medium)).fixedSize(horizontal: false, vertical: true)
                            }
                        }.font(.subheadline.weight(navigation.scope == scope ? .semibold : .regular))
                            .frame(maxWidth: .infinity, minHeight: dynamicTypeSize.isAccessibilitySize ? 52 : 44)
                            .background(navigation.scope == scope ? Theme.line : Color.clear, in: RoundedRectangle(cornerRadius: 10))
                    }.buttonStyle(.plain).accessibilityIdentifier("scope-" + scope.rawValue)
                        .accessibilityAddTraits(navigation.scope == scope ? .isSelected : [])
                        .accessibilityHint(scope == .desktop && remote == .failed ? String(localized: "重试连接") : "")
                }
            }.fixedSize(horizontal: false, vertical: true)
                .padding(4).background(Theme.input, in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 16).padding(.bottom, 8)
        }
    }
}

/// Strip only an exact appended device identifier; preserve legitimate hostname suffixes.
func remoteHostName(_ name: String, deviceID: String) -> String {
    guard !deviceID.isEmpty else { return name }
    for suffix in [" (" + deviceID + ")", " · " + deviceID, " - " + deviceID, "_" + deviceID, "-" + deviceID] {
        if name.hasSuffix(suffix) {
            let host = String(name.dropLast(suffix.count)).trimmingCharacters(in: .whitespaces)
            if !host.isEmpty { return host }
        }
    }
    return name
}
