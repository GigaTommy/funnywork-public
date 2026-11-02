import SwiftUI
import UserNotifications

struct PushSettingsView: View {
    @ObservedObject var model: PushNotifications
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        Form {
            Section {
                Toggle("需要批准", isOn: preference(\.approvals)).accessibilityIdentifier("notification-approvals")
                Toggle("需要回答", isOn: preference(\.questions)).accessibilityIdentifier("notification-questions")
                Toggle("任务结束", isOn: preference(\.turnEnded)).accessibilityIdentifier("notification-turn-ended")
            } footer: { Text("提醒不会显示对话内容。") }
            .disabled(model.busy).tint(Theme.switchAccent).listRowBackground(Theme.input)
            Section {
                if model.busy { HStack { ProgressView(); Text("正在更新…") } }
                else if model.status == .denied {
                    Text("系统通知已关闭").foregroundStyle(.secondary)
                    Button("打开系统设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                } else {
                    LabeledContent("状态", value: model.connected ? String(localized: "已开启") : model.preferences.enabled ? String(localized: "等待连接") : String(localized: "未开启"))
                }
                if let error = model.error { Text(error).font(.callout).foregroundStyle(.secondary) }
                Button("重试") { Task { await model.refresh(requestPermission: true) } }.disabled(model.busy)
            }.listRowBackground(Theme.input)
        }.scrollContentBackground(.hidden).background(Theme.surface)
            .navigationTitle("提醒").navigationBarTitleDisplayMode(.inline)
            .task(id: scenePhase) { if scenePhase == .active { await model.refresh() } }
    }
    private func preference(_ key: WritableKeyPath<PushPreferences, Bool>) -> Binding<Bool> {
        Binding(get: { model.preferences[keyPath: key] }, set: { enabled in
            var value = model.preferences; value[keyPath: key] = enabled
            Task { await model.update(value) }
        })
    }
}
