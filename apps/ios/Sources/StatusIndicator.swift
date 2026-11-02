import SwiftUI

/// Color is supplementary: every status keeps a readable label and a distinct glyph.
enum StatusStyle {
    case online, offline, checking, unknown, unlinked, disabled, failed, waiting, running, completed, stopped, neutral
    var color: Color {
        switch self {
        case .online, .completed: return Theme.onlineStatus
        case .checking, .waiting: return Theme.warningStatus
        case .failed: return Theme.errorStatus
        case .running: return .primary
        default: return .secondary
        }
    }
    var symbol: String {
        switch self {
        case .online: return "circle.fill"
        case .offline: return "circle"
        case .checking, .running: return "arrow.triangle.2.circlepath"
        case .unknown: return "questionmark.circle"
        case .unlinked: return "link"
        case .disabled: return "nosign"
        case .failed: return "exclamationmark.circle.fill"
        case .waiting: return "clock"
        case .completed: return "checkmark.circle.fill"
        case .stopped: return "stop.circle"
        case .neutral: return "minus"
        }
    }
}
struct StatusIndicator: View {
    let title: String
    let style: StatusStyle
    var identifier: String? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        HStack(spacing: 4) {
            if (style == .checking || style == .running) && !reduceMotion {
                ProgressView().controlSize(.mini).tint(style.color)
                    .frame(width: 12, height: 12).accessibilityHidden(true)
            } else {
                Image(systemName: style.symbol).font(.system(size: style == .online || style == .offline ? 8 : 11))
                    .foregroundStyle(style.color).accessibilityHidden(true)
            }
            if let identifier {
                Text(verbatim: title).foregroundStyle(Color.primary).accessibilityIdentifier(identifier)
            } else {
                Text(verbatim: title).foregroundStyle(Color.primary)
            }
        }
    }
}
