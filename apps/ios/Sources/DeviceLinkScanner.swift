import SwiftUI
import AVFoundation

struct DeviceLinkScanner: UIViewControllerRepresentable {
    let scanned: (String) -> Void
    let failed: () -> Void
    func makeUIViewController(context: Context) -> ScannerController {
        ScannerController(scanned: scanned, failed: failed)
    }
    func updateUIViewController(_ controller: ScannerController, context: Context) {}
    static func dismantleUIViewController(_ controller: ScannerController, coordinator: ()) { controller.stop() }
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let capture = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.funnyworker.ios.connection-camera")
    private var active = true // Accessed only on the capture queue.
    private var delivered = false // Metadata callbacks run on the main queue.
    private var preview: AVCaptureVideoPreviewLayer?
    private let scanned: (String) -> Void
    private let failed: () -> Void
    init(scanned: @escaping (String) -> Void, failed: @escaping () -> Void) {
        self.scanned = scanned; self.failed = failed
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: capture)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer); preview = layer
        queue.async { [self] in
            guard active else { return }
            guard let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera), capture.canAddInput(input) else { fail(); return }
            capture.beginConfiguration()
            capture.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard capture.canAddOutput(output) else { capture.commitConfiguration(); fail(); return }
            capture.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            guard output.availableMetadataObjectTypes.contains(.qr) else { capture.commitConfiguration(); fail(); return }
            output.metadataObjectTypes = [.qr]
            capture.commitConfiguration()
            guard active else { return }
            capture.startRunning()
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        if let connection = preview?.connection {
            let angle: CGFloat
            switch view.window?.windowScene?.interfaceOrientation {
            case .landscapeLeft: angle = 0
            case .landscapeRight: angle = 180
            case .portraitUpsideDown: angle = 270
            default: angle = 90
            }
            if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
        }
    }
    func stop() {
        delivered = true
        queue.async { [self] in active = false; capture.stopRunning() }
    }
    private func fail() { DispatchQueue.main.async { [weak self] in
        guard let self, !delivered else { return }
        delivered = true; failed()
    } }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !delivered, let code = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        stop(); scanned(code)
    }
}
