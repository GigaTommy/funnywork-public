import SwiftUI

struct VoiceInputView: View {
    @StateObject private var model: VoiceInput
    @State private var recordingTask: Task<Void, Never>?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let insert: (String) -> Void
    init(driver: VoiceInputDriver? = nil, insert: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: VoiceInput(driver: driver)); self.insert = insert
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                TextEditor(text: $model.text)
                    .scrollContentBackground(.hidden).padding(12)
                    .background(Theme.input, in: RoundedRectangle(cornerRadius: 18))
                    .disabled(model.recording || model.busy)
                    .accessibilityLabel("识别文字").accessibilityIdentifier("voice-transcript")
                if let error = model.error { Text(error).font(.callout).foregroundStyle(.secondary) }
                if model.busy { ProgressView() }
                if model.recording {
                    Button("结束录音") { model.finish() }.buttonStyle(PrimaryActionStyle())
                } else {
                    Button(model.text.isEmpty ? String(localized: "开始录音") : String(localized: "重新录音")) {
                        recordingTask?.cancel()
                        recordingTask = Task { guard !Task.isCancelled else { return }; await model.start() }
                    }.buttonStyle(.bordered).disabled(model.busy)
                }
                Button("使用文字") {
                    let text = model.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.stop(); insert(text); dismiss()
                }.buttonStyle(PrimaryActionStyle())
                    .disabled(model.recording || model.busy || model.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }.padding().background(Theme.surface)
                .navigationTitle("语音输入").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("取消") { dismiss() } }
        }
        .onDisappear { recordingTask?.cancel(); model.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background || (phase != .active && model.recording) { recordingTask?.cancel(); model.stop() }
        }
    }
}
