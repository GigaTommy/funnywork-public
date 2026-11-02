import SwiftUI

/// Read-only inspection; opening or closing this page performs no draft mutation.
struct TaskContentPreviewView: View {
    let title: LocalizedStringKey
    let context: String
    let text: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(verbatim: context).font(.caption).foregroundStyle(.secondary)
                    Text(verbatim: text).frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled).accessibilityIdentifier("task-content-preview-text")
                }.padding()
            }.background(Theme.surface)
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("返回") { dismiss() }.accessibilityIdentifier("close-task-content-preview")
                    }
                }
        }
    }
}
