import Foundation
import Combine

/// Owns selection intent separately from a fixed version's asynchronously updated representations.
@MainActor final class ResultPreviewModel: ObservableObject {
    @Published private(set) var result: ResultVersion
    @Published private(set) var records: [ResultRecord] = []
    @Published private(set) var selectedKey = "original"
    @Published private(set) var refreshError: String?
    let download: AttachmentModel
    private let service: ResultService?
    private let authFailure: (Error) -> Void
    private var choseOriginal = false
    private var defaultKey = "original"
    private var refreshing = false
    init(result: ResultVersion, download: AttachmentModel, service: ResultService?, authFailure: @escaping (Error) -> Void = { _ in }) {
        self.result = result; self.download = download; self.service = service; self.authFailure = authFailure
    }
    private var preferred: (ResultFile, String) {
        if let file = result.variants.first(where: { $0.role == "workbook" }) { return (file.content, file.key) }
        if let file = result.variants.first(where: { $0.role == "pdf" }) { return (file.content, file.key) }
        return (result.original, "original")
    }
    func openDefault() async {
        guard !download.loading else { return }
        let (file, key) = preferred; defaultKey = key
        await open(file, key: key)
    }
    func openOriginal() async { choseOriginal = true; await open(result.original, key: "original") }
    func openVariant(_ key: String) async {
        guard let variant = result.variants.first(where: { $0.key == key }) else { return }
        await open(variant.content, key: key)
    }
    private func open(_ file: ResultFile, key: String) async {
        guard !download.loading, let reference = file.file else { return }
        await download.cancelAndWait()
        selectedKey = key; download.preview = nil
        await download.openReference(reference); await download.waitForDownload()
    }
    func selectVersion(_ value: ResultVersion) async {
        guard value.id == result.id, value.valid, !download.loading else { return }
        await download.cancelAndWait()
        result = value; choseOriginal = false; defaultKey = "original"; refreshError = nil
        await openDefault()
    }
    func refresh() async {
        guard let service, !refreshing else { return }
        refreshing = true; defer { refreshing = false }
        let identity = result.identity
        do {
            let values = try await service.versions(result.id); try Task.checkCancellation(); records = values
            guard result.identity == identity, let current = values.first(where: { $0.manifest.identity == identity }) else { return }
            result = current.manifest; refreshError = nil
            guard !download.loading, download.paused == nil else { return }
            if !choseOriginal && preferred.1 != defaultKey {
                await openDefault()
            } else if download.preview == nil, download.downloading == nil, current.transfers[selectedKey]?.complete == true {
                // An announced preview can become transferable after its declaration; retry the same reference.
                await download.retry(); await download.waitForDownload()
            }
        } catch {
            if !(error is CancellationError) {
                refreshError = ClientError.displayMessage(for: error)
                if error is AuthenticationError { authFailure(error) }
            }
        }
    }
}
