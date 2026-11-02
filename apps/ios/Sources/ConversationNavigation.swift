import SwiftUI

struct ConversationMessageLocation: Equatable {
    let requestID = UUID()
    let sessionID: String
    let sequence: Int
}
private struct ConversationMessageLocationKey: EnvironmentKey {
    static let defaultValue: Binding<ConversationMessageLocation?> = .constant(nil)
}
extension EnvironmentValues {
    var conversationMessageLocation: Binding<ConversationMessageLocation?> {
        get { self[ConversationMessageLocationKey.self] }
        set { self[ConversationMessageLocationKey.self] = newValue }
    }
}


private struct OpenConversationKey: EnvironmentKey {
    static let defaultValue: ((Conversation) -> Void)? = nil
}
private struct DesktopTaskLocationKey: EnvironmentKey {
    static let defaultValue: Binding<Conversation?> = .constant(nil)
}
extension EnvironmentValues {
    var desktopTaskLocation: Binding<Conversation?> {
        get { self[DesktopTaskLocationKey.self] }
        set { self[DesktopTaskLocationKey.self] = newValue }
    }
    var openConversation: ((Conversation) -> Void)? {
        get { self[OpenConversationKey.self] }
        set { self[OpenConversationKey.self] = newValue }
    }
}
