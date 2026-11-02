import SwiftUI
import PhotosUI

enum AppAppearance: String, CaseIterable, Identifiable {
    case system, light, dark
    static let storageKey = "appearance"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .system: return String(localized: "跟随系统")
        case .light: return String(localized: "浅色")
        case .dark: return String(localized: "深色")
        }
    }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

struct AccountSettingsView: View {
    let name: String
    var store: ConversationStore? = nil
    var push: PushNotifications? = nil
    var allowanceService: (any ModelAllowanceService)? = nil
    var authFailure: (Error) -> Void = { _ in }
    let logout: () -> Void
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppAppearance.storageKey) private var appearance = AppAppearance.system.rawValue
    @AppStorage(ChatInputPreferences.keepKeyboardKey) private var keepKeyboard = false
    @EnvironmentObject private var account: Account
    @State private var picture: PhotosPickerItem?
    @State private var pictureBusy = false
    @State private var pictureError: String?
    @State private var confirmLogout = false
    @State private var confirmClearDownloads = false
    @State private var downloadedBytes: Int?
    @State private var downloadedCount = 0
    @State private var storageError: String?
    private var selection: Binding<AppAppearance> {
        Binding(get: { AppAppearance(rawValue: appearance) ?? .system }, set: { appearance = $0.rawValue })
    }
    private var version: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        return build.map { "\(version) (\($0))" } ?? version
    }
    var body: some View {
        NavigationStack {
            Form {
                if !name.isEmpty {
                    Section {
                        HStack(spacing: 12) {
                            PhotosPicker(selection: $picture, matching: .images, photoLibrary: .shared()) {
                                AccountPicture(name: name)
                                    .overlay { if pictureBusy { ProgressView() } }
                            }.buttonStyle(.plain).disabled(pictureBusy)
                                .accessibilityLabel("更换头像").accessibilityIdentifier("change-avatar")
                            LabeledContent("账号", value: name)
                        }
                        if let pictureError { Text(pictureError).foregroundStyle(.secondary) }

                    }.listRowBackground(Theme.input)
                }
                if let allowanceService { ModelAllowanceSection(service: allowanceService, authFailure: authFailure) }
                Section {
                    Picker("外观", selection: selection) {
                        ForEach(AppAppearance.allCases) { value in Text(value.title).tag(value) }
                    }.pickerStyle(.navigationLink).accessibilityIdentifier("appearance-setting")
                    Toggle("发送后保留键盘", isOn: $keepKeyboard).tint(Theme.switchAccent)
                        .accessibilityIdentifier("keep-chat-keyboard")
                } header: { Text("使用偏好") }.listRowBackground(Theme.input)
                if let push {
                    Section { NavigationLink("提醒") { PushSettingsView(model: push) }.accessibilityIdentifier("notification-settings") }.listRowBackground(Theme.input)
                }
                Section {
                    LabeledContent("版本", value: version).accessibilityIdentifier("app-version")
                    NavigationLink("使用帮助") {
                        List {
                            Section("聊天") { Text("聊天不需要电脑在线。回复期间可以写草稿，回复结束后再发送。") }
                            Section("远程") { Text("远程任务在所选电脑上运行。电脑离线时，可查看已经同步的记录。") }
                            Section("结果待确认") { Text("操作结果未确认时，先核对原操作的结果，避免重复提交。") }
                        }.scrollContentBackground(.hidden).background(Theme.surface).navigationTitle("使用帮助")
                    }
                } header: { Text("帮助与关于") }.listRowBackground(Theme.input)
                if store != nil {
                    Section {
                        LabeledContent("下载文件", value: downloadedBytes.map { ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .file) } ?? "—")
                            .accessibilityIdentifier("download-storage-usage")
                        Button("清除下载文件", role: .destructive) { confirmClearDownloads = true }
                            .disabled(downloadedCount == 0).accessibilityIdentifier("clear-downloads")
                        if let storageError {
                            Text(storageError).font(.caption).foregroundStyle(.secondary)
                            Button("重试") { refreshStorage() }
                        }
                    } header: { Text("存储空间") }
                    footer: { Text("清理后可重新下载，草稿和消息会保留。") }
                    .listRowBackground(Theme.input)
                }
                Section {
                    Button("退出登录", role: .destructive) { confirmLogout = true }
                }.listRowBackground(Theme.input)
            }
            .scrollContentBackground(.hidden).background(Theme.surface)
            .navigationTitle("设置").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { dismiss() } }
            .alert("退出登录？", isPresented: $confirmLogout) {
                Button("取消", role: .cancel) { }
                Button("退出登录", role: .destructive) { dismiss(); logout() }
            } message: { Text("退出会清理这个账号的本地草稿和缓存。已开始的任务不会因此停止。") }
            .alert("清除下载文件？", isPresented: $confirmClearDownloads) {
                Button("取消", role: .cancel) { }
                Button("清除", role: .destructive) {
                    do { try store?.clearDownloadedFiles(); refreshStorage() }
                    catch { storageError = ClientError.displayMessage(for: error); refreshStorage(keepingError: true) }
                }
            } message: { Text("清理后可重新下载，草稿和消息会保留。") }
            .task { refreshStorage() }
            .task(id: picture) {
                guard let picture else { return }
                pictureBusy = true; pictureError = nil
                defer { pictureBusy = false; self.picture = nil }
                do {
                    guard let data = try await picture.loadTransferable(type: Data.self), data.count <= 20 * 1024 * 1024,
                          let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { throw ClientError.invalidResponse }
                    let side: CGFloat = 512
                    let scale = side / max(image.size.width, image.size.height)
                    let size = CGSize(width: max(1, floor(image.size.width * scale)), height: max(1, floor(image.size.height * scale)))
                    let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
                    let normalized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                        UIColor.white.setFill(); UIRectFill(CGRect(origin: .zero, size: size)); image.draw(in: CGRect(origin: .zero, size: size))
                    }
                    guard let jpeg = normalized.jpegData(compressionQuality: 0.85), jpeg.count <= 256000 else { throw ClientError.invalidResponse }
                    try await account.saveAvatar("data:image/jpeg;base64," + jpeg.base64EncodedString())
                } catch { pictureError = String(localized: "头像未保存，请选择图片后重试。"); authFailure(error) }
            }
        }
        .preferredColorScheme((AppAppearance(rawValue: appearance) ?? .system).colorScheme)
    }
    private func refreshStorage(keepingError: Bool = false) {
        do {
            guard let usage = try store?.downloadedFileUsage() else { return }
            downloadedBytes = usage.bytes; downloadedCount = usage.count
            if !keepingError { storageError = nil }
        } catch { downloadedBytes = nil; downloadedCount = 0; storageError = ClientError.displayMessage(for: error) }
    }
}


struct AccountPicture: View {
    @EnvironmentObject private var account: Account
    let name: String
    var body: some View {
        Group {
            if let encoded = account.avatarURL.split(separator: ",", maxSplits: 1).last,
               account.avatarURL.hasPrefix("data:image/"), let data = Data(base64Encoded: String(encoded)), let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Text(name.first.map { String($0).uppercased() } ?? "F").font(.subheadline.weight(.medium))
            }
        }.frame(width: 36, height: 36).background(Theme.input).clipShape(Circle()).accessibilityHidden(true)
    }
}
