import PhotosUI
import CoreTransferable
import UniformTypeIdentifiers
import ImageIO
import SwiftUI

struct PhotoAttachment: Transferable {
    let file: OutgoingAttachment
    let data: Data
    static func captured(_ image: UIImage) async throws -> Self {
        let task = Task.detached(priority: .userInitiated) { () throws -> Self in
            try Task.checkCancellation()
            guard let data = image.jpegData(compressionQuality: 0.9) else {
                throw ClientError(message: String(localized: "无法读取这张照片，请重新选择。"))
            }
            try Task.checkCancellation()
            let file = try OutgoingAttachment(data: data, name: "photo-\(UUID().uuidString.prefix(8)).jpg", mediaType: "image/jpeg")
            return Self(file: file, data: data)
        }
        return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            // Consume the temporary representation before its import callback returns.
            try await read(received.file)
        }
    }

    static func read(_ url: URL) async throws -> Self {
        let (original, data) = try await OutgoingAttachment.read(url)
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let identifier = CGImageSourceGetType(source), let type = UTType(identifier as String),
              type.conforms(to: .image), CGImageSourceGetCount(source) > 0 else {
            throw ClientError(message: String(localized: "无法读取这张照片，请重新选择。"))
        }
        let extensionType = UTType(filenameExtension: url.pathExtension)
        let name = extensionType?.conforms(to: .image) == true ? original.name
            : url.deletingPathExtension().lastPathComponent + "." + (type.preferredFilenameExtension ?? "img")
        let file = try OutgoingAttachment(data: data, name: name,
            mediaType: type.preferredMIMEType ?? original.mediaType)
        return Self(file: file, data: data)
    }
}

struct OutgoingAttachmentIcon: View {
    let file: OutgoingAttachment
    let store: ConversationStore
    let sessionID: String
    @State private var thumbnail: UIImage?

    var body: some View {
        Group {
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFill()
                    .frame(width: 44, height: 44).clipped().clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                Image(systemName: file.mediaType.hasPrefix("image/") ? "photo" : "doc")
            }
        }.accessibilityHidden(true)
            .task(id: file.hash) {
                guard file.mediaType.hasPrefix("image/"), let data = try? store.loadUpload(file, sessionID: sessionID) else { return }
                let task = Task.detached(priority: .utility) { () -> UIImage? in
                    guard !Task.isCancelled,
                          let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                            kCGImageSourceCreateThumbnailFromImageAlways: true,
                            kCGImageSourceCreateThumbnailWithTransform: true,
                            kCGImageSourceThumbnailMaxPixelSize: 160
                          ] as CFDictionary), !Task.isCancelled else { return nil }
                    return UIImage(cgImage: image)
                }
                let image = await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
                if !Task.isCancelled { thumbnail = image }
            }
    }
}
