import AVFoundation
import NaturalLanguage
import Combine

@MainActor protocol ReadingDriver: AnyObject {
    var finished: ((UUID) -> Void)? { get set }
    var interrupted: (() -> Void)? { get set }
    func start(_ text: String, token: UUID) throws
    func pause() -> Bool
    func resume() -> Bool
    func stop()
}

@MainActor final class MessageReading: ObservableObject {
    @Published private(set) var messageID: Int?
    @Published private(set) var paused = false
    @Published var error: String?
    private var token: UUID?
    private let driver: ReadingDriver
    init(driver: ReadingDriver? = nil) {
        let driver = driver ?? SystemReadingDriver()
        self.driver = driver
        driver.finished = { [weak self] token in
            guard self?.token == token else { return }
            self?.stop()
        }
        driver.interrupted = { [weak self] in self?.stop() }
    }
    func read(_ text: String, messageID: Int) {
        stop(); error = nil
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let token = UUID(); self.token = token; self.messageID = messageID
        do { try driver.start(text, token: token) }
        catch { stop(); self.error = String(localized: "暂时无法朗读，请重试。") }
    }
    func togglePause() {
        guard messageID != nil else { return }
        if paused { if driver.resume() { paused = false } }
        else if driver.pause() { paused = true }
    }
    func stop() {
        token = nil; messageID = nil; paused = false
        driver.stop()
    }
}

@MainActor final class SystemReadingDriver: NSObject, ReadingDriver, AVSpeechSynthesizerDelegate {
    var finished: ((UUID) -> Void)?
    var interrupted: (() -> Void)?
    private let synthesizer = AVSpeechSynthesizer()
    private var utterance: AVSpeechUtterance?
    private var token: UUID?
    private var observation: NSObjectProtocol?
    private var audioActive = false
    override init() {
        super.init()
        synthesizer.delegate = self
        observation = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.interrupted?() }
        }
    }
    deinit {
        if let observation { NotificationCenter.default.removeObserver(observation) }
    }
    func start(_ text: String, token: UUID) throws {
        stop()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
        try session.setActive(true); audioActive = true
        let value = AVSpeechUtterance(string: text)
        let recognizer = NLLanguageRecognizer(); recognizer.processString(text)
        if let language = recognizer.dominantLanguage { value.voice = AVSpeechSynthesisVoice(language: language.rawValue) }
        value.rate = AVSpeechUtteranceDefaultSpeechRate
        self.token = token; utterance = value
        synthesizer.speak(value)
    }
    func pause() -> Bool { synthesizer.pauseSpeaking(at: .immediate) }
    func resume() -> Bool { synthesizer.continueSpeaking() }
    func stop() {
        token = nil; utterance = nil
        synthesizer.stopSpeaking(at: .immediate)
        if audioActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            audioActive = false
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in self?.complete(utterance) }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in self?.complete(utterance) }
    }
    private func complete(_ value: AVSpeechUtterance) {
        guard value === utterance, let token else { return }
        finished?(token)
    }
}
