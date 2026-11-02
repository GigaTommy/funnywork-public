import SwiftUI
import AVFoundation

@MainActor final class CameraCapture: ObservableObject {
    @Published var presented = false
    @Published private(set) var requesting = false
    @Published var error: String?
    @Published private(set) var needsSettings = false
    let available: Bool
    private let authorize: () async -> Bool
    private var generation = UUID()
    private var captured: UIImage?

    init(available: Bool? = nil,
         authorize: @escaping () async -> Bool = {
             switch AVCaptureDevice.authorizationStatus(for: .video) {
             case .authorized: return true
             case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
             default: return false
             }
         }) {
        self.available = available ?? UIImagePickerController.isSourceTypeAvailable(.camera); self.authorize = authorize
    }
    func open() async {
        guard !requesting, !presented else { return }
        guard available else { error = String(localized: "这台设备无法使用相机。"); needsSettings = false; return }
        generation = UUID(); let attempt = generation
        requesting = true; error = nil; needsSettings = false
        let allowed = await authorize()
        guard generation == attempt else { return }
        requesting = false
        guard !Task.isCancelled else { return }
        if allowed { presented = true }
        else { needsSettings = true; error = String(localized: "请在设置中允许访问相机。"); }
    }
    func finish(_ image: UIImage?) {
        guard presented else { return }
        captured = image; presented = false
    }
    func takePhoto() -> UIImage? { defer { captured = nil }; return captured }
    func cancel() {
        generation = UUID(); requesting = false; presented = false; captured = nil
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    let finished: (UIImage?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(finished: finished) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = ["public.image"]
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let finished: (UIImage?) -> Void
        init(finished: @escaping (UIImage?) -> Void) { self.finished = finished }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { finished(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            finished(info[.originalImage] as? UIImage)
        }
    }
}
