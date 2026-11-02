import AVFoundation
import Speech
import Combine

@MainActor protocol VoiceInputDriver: AnyObject {
    var result: ((UUID, String?, Bool, Error?) -> Void)? { get set }
    func start(token: UUID) async throws
    func finish()
    func stop()
}

@MainActor final class VoiceInput: ObservableObject {
    @Published var text = ""
    @Published private(set) var recording = false
    @Published private(set) var busy = false
    @Published var error: String?
    private let driver: VoiceInputDriver
    private var token: UUID?
    private var timeout: Task<Void, Never>?
    init(driver: VoiceInputDriver? = nil) {
        self.driver = driver ?? SystemVoiceInputDriver()
        self.driver.result = { [weak self] token, text, final, error in
            guard let self, self.token == token else { return }
            if let text { self.text = text }
            if let error { self.stop(); self.error = ClientError.displayMessage(for: error) }
            else if final { self.stop() }
        }
    }
    func start() async {
        guard !Task.isCancelled else { return }
        stop(); text = ""; error = nil
        let current = UUID(); token = current; busy = true
        do {
            try await driver.start(token: current)
            try Task.checkCancellation()
            guard token == current else { return }
            recording = true; busy = false
        } catch {
            guard token == current else { return }
            stop()
            if !(error is CancellationError) { self.error = ClientError.displayMessage(for: error) }
        }
    }
    func finish() {
        guard recording else { return }
        recording = false; busy = true
        driver.finish()
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(8)) } catch { return }
            guard let self, self.busy else { return }
            self.stop(); self.error = String(localized: "识别未完成，请检查文字后继续。")
        }
    }
    func stop() {
        token = nil; recording = false; busy = false
        timeout?.cancel(); timeout = nil; driver.stop()
    }
}

@MainActor final class SystemVoiceInputDriver: VoiceInputDriver {
    var result: ((UUID, String?, Bool, Error?) -> Void)?
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var token: UUID?
    private var tapped = false
    private var audioActive = false
    func start(token: UUID) async throws {
        stop()
        let status = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        try Task.checkCancellation()
        guard status == .authorized else { throw ClientError(message: String(localized: "请在系统设置中允许语音识别。")) }
        let allowed = await AVAudioApplication.requestRecordPermission()
        try Task.checkCancellation()
        guard allowed else { throw ClientError(message: String(localized: "请在系统设置中允许麦克风访问。")) }
        guard let recognizer = SFSpeechRecognizer(locale: .current), recognizer.isAvailable else {
            throw ClientError(message: String(localized: "语音识别暂不可用，请稍后重试。"))
        }
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.record, mode: .measurement, options: .duckOthers)
        try audio.setActive(true); audioActive = true
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            stop(); throw ClientError(message: String(localized: "未找到可用的麦克风。"))
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.request = request; self.token = token
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
        tapped = true
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let final = result?.isFinal == true
            Task { @MainActor in
                guard let self, self.token == token else { return }
                self.result?(token, text, final, error)
            }
        }
        do { engine.prepare(); try engine.start() }
        catch { stop(); throw error }
    }
    func finish() {
        engine.stop()
        if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
        request?.endAudio()
    }
    func stop() {
        token = nil; finish(); task?.cancel(); task = nil; request = nil
        if audioActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            audioActive = false
        }
    }
}
