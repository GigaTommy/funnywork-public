import SwiftUI
import AVFoundation

struct DeviceLinkView: View {
    @StateObject private var model: DeviceLinkModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var scanning = false
    @State private var requestingCamera = false
    @State private var cameraError: String?
    @State private var actionTask: Task<Void, Never>?
    @State private var cameraGeneration = UUID()
    let connected: (String) -> Void
    #if DEBUG
    private let fixtureCode: String?
    #endif
    init(service: DeviceLinkService, authFailure: @escaping (Error) -> Void, connected: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: DeviceLinkModel(service: service, authFailure: authFailure))
        self.connected = connected
        #if DEBUG
        fixtureCode = nil
        #endif
    }
    #if DEBUG
    init(service: DeviceLinkService, fixtureCode: String, connected: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: DeviceLinkModel(service: service)); self.fixtureCode = fixtureCode; self.connected = connected
    }
    #endif
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    if let device = model.preview {
                        Image(systemName: "desktopcomputer").font(.system(size: 48)).accessibilityHidden(true)
                        Text(device.name).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                            .accessibilityIdentifier("connection-device")
                        Text(device.platform == "darwin" ? "macOS" : device.platform == "win32" ? "Windows" : device.platform).font(.subheadline).foregroundStyle(.secondary)
                        Text("确认连接这台设备？").font(.body)
                        Button("连接") { actionTask = Task { await model.confirm() } }
                            .buttonStyle(PrimaryActionStyle()).disabled(!model.canConfirm).accessibilityIdentifier("confirm-device-connection")
                        Button("重新扫描") { model.clear(); startCamera() }.disabled(model.busy)
                    } else {
                        Image(systemName: "qrcode.viewfinder").font(.system(size: 64)).accessibilityHidden(true)
                        Text("扫描桌面上的二维码").font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                        Text("在桌面菜单中选择“连接手机”。").font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("扫描二维码") { startCamera() }.buttonStyle(PrimaryActionStyle())
                            .disabled(model.busy || requestingCamera).accessibilityIdentifier("scan-device-code")
                    }
                    if model.busy || requestingCamera { ProgressView() }
                    if let error = model.error { Text(error).font(.callout).multilineTextAlignment(.center).accessibilityIdentifier("connection-error") }
                    if let cameraError {
                        Text(cameraError).font(.callout).multilineTextAlignment(.center)
                        if AVCaptureDevice.authorizationStatus(for: .video) == .denied || AVCaptureDevice.authorizationStatus(for: .video) == .restricted {
                            Button("打开设置") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                        }
                    }
                }.frame(maxWidth: 480).padding(28).frame(maxWidth: .infinity)
            }.background(Theme.surface)
                .navigationTitle("连接设备").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("取消") { dismiss() } }
                .fullScreenCover(isPresented: $scanning) {
                    NavigationStack {
                        DeviceLinkScanner { code in
                            scanning = false
                            actionTask = Task { await model.scan(code) }
                        } failed: { scanning = false; cameraError = String(localized: "无法打开相机，请稍后重试。") }
                        .ignoresSafeArea(edges: .bottom)
                        .navigationTitle("扫描二维码").navigationBarTitleDisplayMode(.inline)
                        .toolbar { Button("取消") { scanning = false } }
                    }
                }
                .onChange(of: model.connectedID) { _, id in if let id { connected(id); dismiss() } }
                .task(id: scenePhase) {
                    guard scenePhase == .active else {
                        if scenePhase == .inactive && requestingCamera { return }
                        cameraGeneration = UUID(); requestingCamera = false; scanning = false
                        actionTask?.cancel(); model.clear(); return
                    }
                    repeat {
                        model.expire()
                        do { try await Task.sleep(for: .seconds(1)) } catch { return }
                    } while !Task.isCancelled
                }
        }.onDisappear { cameraGeneration = UUID(); actionTask?.cancel(); model.clear() }
    }
    private func startCamera() {
        cameraError = nil
        #if DEBUG
        if let fixtureCode { actionTask = Task { await model.scan(fixtureCode) }; return }
        #endif
        guard !requestingCamera else { return }
        let attempt = UUID(); cameraGeneration = attempt
        requestingCamera = true
        actionTask = Task {
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard !Task.isCancelled, cameraGeneration == attempt, scenePhase != .background else { return }
            requestingCamera = false
            if allowed { scanning = true }
            else { cameraError = String(localized: "请允许相机访问，以扫描二维码。") }
        }
    }
}
