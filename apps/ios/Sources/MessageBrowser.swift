import SwiftUI
import SafariServices

struct MessageWebLink: Identifiable, Equatable {
    let url: URL
    var id: String { url.absoluteString }
    init?(_ url: URL) {
        guard ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty else { return nil }
        self.url = url
    }
}

/// Created only after an explicit tap on a web link in a message.
struct MessageBrowser: UIViewControllerRepresentable {
    let link: MessageWebLink
    @Environment(\.dismiss) private var dismiss
    func makeCoordinator() -> Coordinator { Coordinator { dismiss() } }
    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: link.url)
        controller.dismissButtonStyle = .close
        controller.preferredControlTintColor = .label
        controller.delegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
    final class Coordinator: NSObject, SFSafariViewControllerDelegate {
        let close: () -> Void
        init(close: @escaping () -> Void) { self.close = close }
        func safariViewControllerDidFinish(_ controller: SFSafariViewController) { close() }
    }
}
