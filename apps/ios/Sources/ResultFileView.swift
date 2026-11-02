import SwiftUI
import PDFKit
import QuickLook

struct ResultFileCard: View {
    let result: ResultVersion
    let service: AttachmentService
    let resultService: ResultService?
    let store: ConversationStore
    let sessionID: String
    let authFailure: (Error) -> Void
    let feedback: (String) -> Void
    @State private var opened = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let thumbnail = result.variants.first(where: { $0.role == "thumbnail" })?.content.file {
                MessageAttachmentCard(file: thumbnail, service: service, store: store, sessionID: sessionID, authFailure: authFailure) { opened = true }
            }
            Button { opened = true } label: {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "doc.richtext")
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: result.name).lineLimit(2)
                        Text(String(localized: "版本") + " \(result.version) · " + (result.original.file?.sizeLabel ?? "")).font(.caption).foregroundStyle(.secondary)
                        Text(result.previewState == "pending" ? String(localized: "预览正在准备") : result.previewState == "failed" ? String(localized: "预览未完成，可查看原文件") : String(localized: "查看结果"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(); Image(systemName: "chevron.right").font(.caption)
                }.padding(12).background(Theme.input, in: RoundedRectangle(cornerRadius: 16))
            }.buttonStyle(.plain).accessibilityIdentifier("result-file-" + result.identity)
        }.sheet(isPresented: $opened) {
            ResultFileView(result: result, service: service, resultService: resultService, store: store, sessionID: sessionID, authFailure: authFailure, feedback: feedback)
        }
    }
}

struct ResultFileView: View {
    @StateObject private var selection: ResultPreviewModel
    private var result: ResultVersion { selection.result }
    @StateObject private var download: AttachmentModel
    private let resultService: ResultService?
    private let feedback: (String) -> Void
    @State private var resultImage: UIImage?
    private var records: [ResultRecord] { selection.records }
    private var selectedKey: String { selection.selectedKey }
    @State private var quickLook: URL?
    @State private var workbook: WorkbookIndex?
    @State private var tile: WorkbookTile?
    @State private var sheetID = ""
    @State private var tileKey = ""
    @State private var columnStart = 0
    @State private var page = 1
    @State private var comment = ""
    @State private var location = ""
    @State private var showingFeedback = false
    private var refreshError: String? { selection.refreshError }
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    init(result: ResultVersion, service: AttachmentService, resultService: ResultService?, store: ConversationStore, sessionID: String, authFailure: @escaping (Error) -> Void, feedback: @escaping (String) -> Void) {
        self.resultService = resultService; self.feedback = feedback
        let loader = AttachmentModel(service: service, store: store, sessionID: sessionID, authFailure: authFailure)
        _download = StateObject(wrappedValue: loader)
        _selection = StateObject(wrappedValue: ResultPreviewModel(result: result, download: loader, service: resultService, authFailure: authFailure))
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                HStack {
                    Text(String(localized: "版本") + " \(result.version)").font(.caption).accessibilityIdentifier("result-version-current")
                    Spacer()
                    if records.count > 1 {
                        Menu("版本记录") {
                            ForEach(records) { record in Button(String(localized: "版本") + " \(record.manifest.version)") {
                                workbook = nil; tile = nil; sheetID = ""; page = 1
                                Task { await selection.selectVersion(record.manifest); loadJSON(download.preview) }
                            } }
                        }.disabled(download.loading || download.downloading != nil).accessibilityIdentifier("result-version-menu")
                    }
                    Button("刷新") { Task { await selection.refresh() } }.accessibilityIdentifier("result-version-refresh")
                }.padding(.horizontal)
                if let error = refreshError ?? download.error {
                    HStack { Text(error).font(.caption); Button("重试") { Task { await download.retry(); await selection.refresh() } } }.padding(.horizontal)
                }
                if result.previewState == "pending" { Text("预览正在准备，原文件同步完成后可下载").font(.caption).foregroundStyle(.secondary) }
                if result.previewState == "failed" {
                    HStack {
                        Text("预览未完成，原文件仍可下载").font(.caption)
                        Button("重试预览") {
                            feedback(String(localized: "请重新生成这个结果的预览：") + "\nresultId=" + result.id + "\nversion=\(result.version)\n" + String(localized: "调用 prepare_result_preview，并保持原文件不变。")); dismiss()
                        }
                    }
                }
                if result.warnings.contains("missing-fonts") { Text("部分字体不可用，预览排版可能与原文件不同").font(.caption).foregroundStyle(.secondary) }
                if download.downloading != nil {
                    HStack { ProgressView(value: download.progress); Button("暂停") { download.pauseDownload() } }.padding(.horizontal)
                }
                if download.paused != nil {
                    Button("继续下载") { Task { await download.resumeDownload() } }.accessibilityIdentifier("resume-result-download")
                }
                preview.frame(maxWidth: .infinity, maxHeight: .infinity)
                HStack {
                    Button("下载原文件") { Task { workbook = nil; tile = nil; await selection.openOriginal() } }.disabled(download.loading || download.downloading != nil)
                    if let url = download.preview, selectedKey == "original" {
                        ShareLink(item: url) { Text("分享原文件") }
                    } else if let url = download.preview, selectedKey == "pdf" {
                        ShareLink(item: url) { Text("分享 PDF 预览") }
                    }
                    Spacer()
                    Button("提出修改") { location = selectedKey == "pdf" ? String(localized: "页码") + " \(page)" : sheetID.isEmpty ? "" : sheetID; showingFeedback = true }
                }.font(.callout).padding()
            }.background(Theme.surface)
            .navigationTitle(result.name).navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { download.cancel(); dismiss() } }
            .quickLookPreview($quickLook)
            .task { await selection.openDefault(); loadJSON(download.preview) }
            .task(id: phase) {
                guard phase == .active, resultService != nil else { return }
                repeat { await selection.refresh(); do { try await Task.sleep(for: .seconds(5)) } catch { return } } while !Task.isCancelled
            }
            .onChange(of: selectedKey) { _, key in if key == "original" || key == "pdf" { workbook = nil; tile = nil; sheetID = "" } }
            .onChange(of: download.preview) { _, url in loadJSON(url) }
            .task(id: download.preview) {
                resultImage = nil
                guard let url = download.preview, url.pathExtension.lowercased() == "png" else { return }
                let image = await ImageThumbnail.load(url, maxPixels: 4096)
                guard !Task.isCancelled, download.preview == url else { return }; resultImage = image
            }
            .onDisappear { download.cancel() }
            .sheet(isPresented: $showingFeedback) {
                NavigationStack {
                    Form {
                        Text(verbatim: result.name + " · " + String(localized: "版本") + " \(result.version)")
                        TextField("位置（页码、单元格或图片区域）", text: $location)
                        TextField("想修改什么？", text: $comment, axis: .vertical).lineLimit(4...10).accessibilityIdentifier("result-feedback-comment")
                        Button("加入对话草稿") { feedback(result.feedback(comment, location: location)); showingFeedback = false; dismiss() }
                            .disabled(comment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || comment.utf16.count > 12000)
                    }.navigationTitle("提出修改").toolbar { Button("取消") { showingFeedback = false } }
                }
            }
        }
    }
    @ViewBuilder private var preview: some View {
        if let workbook { workbookView(workbook).disabled(download.loading || download.downloading != nil) }
        else if let url = download.preview {
            if selectedKey == "pdf" { ResultPDFView(url: url, page: $page) }
            else if url.pathExtension.lowercased() == "png", let image = resultImage {
                ZoomableMessageImage(image: image, name: result.name, move: { _ in }).id(url)
            } else { Button("打开原文件") { quickLook = url }.frame(maxWidth: .infinity, maxHeight: .infinity) }
        } else if download.paused != nil {
            ContentUnavailableView("下载已暂停", systemImage: "pause.circle", description: Text("继续下载后可查看预览"))
        } else if download.loading { ProgressView() }
        else { ContentUnavailableView("文件尚未同步", systemImage: "icloud.and.arrow.down", description: Text("同步完成后可以查看，已同步文件不需要电脑在线")) }
    }
    private func loadJSON(_ url: URL?) {
        guard let url, url.pathExtension == "json" else { return }
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 2 * 1024 * 1024, let data = try? Data(contentsOf: url) else { download.error = ClientError.displayMessage(for: ClientError.invalidResponse); return }
        if selectedKey == "workbook", workbook != nil { return }
        if selectedKey == "workbook", let value = try? JSONDecoder().decode(WorkbookIndex.self, from: data), value.schema == 1, value.sheets.count <= 100, value.sheets.allSatisfy({ $0.rows >= 0 && $0.columns >= 0 && $0.tiles.count <= 250 && $0.rows <= 250000 && $0.columns <= 16384 && $0.widths.count <= 16384 && $0.widths.allSatisfy { $0.isFinite && $0 >= 0 && $0 <= 255 } }) {
            workbook = value; sheetID = value.sheets.first?.id ?? ""; loadSheet()
        } else if let value = try? JSONDecoder().decode(WorkbookTile.self, from: data) {
            // An earlier sheet/window may finish after the user has moved on.
            guard value.sheetId == sheetID, selectedKey == tileKey else { return }
            guard value.schema == 1, value.cells.count <= 250000, value.firstRow >= 0,
                  let sheet = workbook?.sheets.first(where: { $0.id == sheetID }), value.firstRow <= sheet.rows,
                  value.cells.allSatisfy({ $0.row >= value.firstRow && $0.row < min(sheet.rows, value.firstRow + 100) && $0.column >= 0 && $0.column < sheet.columns && ($0.mergeRows ?? 1) > 0 && ($0.mergeRows ?? 1) <= sheet.rows && ($0.mergeColumns ?? 1) > 0 && ($0.mergeColumns ?? 1) <= sheet.columns }) else { download.error = ClientError.displayMessage(for: ClientError.invalidResponse); return }
            tile = value
        }
        else { download.error = ClientError.displayMessage(for: ClientError.invalidResponse) }
    }
    private func loadSheet() {
        tile = nil; tileKey = ""; columnStart = 0
        guard let sheet = workbook?.sheets.first(where: { $0.id == sheetID }), let key = sheet.tiles.first else { return }
        tileKey = key; loadTile()
    }
    private func loadTile() {
        guard !tileKey.isEmpty else { return }
        guard let value = result.variants.first(where: { $0.key == tileKey }) else { download.error = ClientError.displayMessage(for: ClientError.invalidResponse); return }
        let identity = result.identity, requestedSheet = sheetID
        Task {
            await selection.openVariant(value.key)
            guard result.identity == identity, sheetID == requestedSheet, tileKey == value.key, selectedKey == value.key else { return }
            // A cached URL may be identical across repeated opens; completion must decode it explicitly.
            loadJSON(download.preview)
        }
    }
    private func workbookView(_ workbook: WorkbookIndex) -> some View {
        VStack(spacing: 8) {
            Picker("工作表", selection: $sheetID) { ForEach(workbook.sheets) { Text(verbatim: $0.name).tag($0.id) } }.onChange(of: sheetID) { _, _ in loadSheet() }.accessibilityIdentifier("workbook-sheets")
            Text("工作表预览不显示图表、图片和条件格式；完整内容请打开原文件").font(.caption).foregroundStyle(.secondary).padding(.horizontal)
            if workbook.missingResults > 0 { Text("部分公式未保存计算结果").font(.caption).foregroundStyle(.secondary) }
            if let sheet = workbook.sheets.first(where: { $0.id == sheetID }) {
                HStack {
                    Picker("行范围", selection: $tileKey) { ForEach(sheet.tiles, id: \.self) { Text(verbatim: rowWindowLabel($0)).tag($0) } }.onChange(of: tileKey) { _, _ in loadTile() }.accessibilityIdentifier("workbook-row-windows")
                    Button("前一组列") { columnStart = max(0, columnStart - 12) }.disabled(columnStart == 0)
                    Button("后一组列") { columnStart += 12 }.disabled(columnStart + 12 >= sheet.columns).accessibilityIdentifier("workbook-next-columns")
                }.font(.caption).padding(.horizontal)
                if sheet.tiles.isEmpty {
                    Spacer(); Text("此范围没有已保存的值").foregroundStyle(.secondary); Spacer()
                } else if let tile {
                    let cells = Dictionary(tile.cells.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
                    ScrollView([.horizontal, .vertical]) {
                        LazyVStack(alignment: .leading, spacing: 1) {
                            HStack(spacing: 1) {
                                Text(" ").frame(width: 40, height: 30)
                                ForEach(columnStart..<min(sheet.columns, columnStart + 12), id: \.self) { column in
                                    Text(verbatim: columnName(column)).frame(width: columnWidth(sheet, column), height: 30).background(Theme.input)
                                }
                            }
                            ForEach(tile.firstRow..<min(sheet.rows, tile.firstRow + 100), id: \.self) { row in
                                HStack(spacing: 1) {
                                    Text(verbatim: String(row + 1)).frame(width: 40, height: 36).background(Theme.input)
                                    ForEach(columnStart..<min(sheet.columns, columnStart + 12), id: \.self) { column in
                                        let cell = cells["\(row):\(column)"]
                                        Color.clear.frame(width: columnWidth(sheet, column), height: 36)
                                            .overlay(alignment: .topLeading) {
                                                let end = min(sheet.columns, columnStart + 12, column + (cell?.mergeColumns ?? 1))
                                                let width = (column..<end).reduce(0.0) { $0 + columnWidth(sheet, $1) + 1 } - 1
                                                Text(verbatim: cell?.missingResult == true ? String(localized: "结果未保存") : cell?.text ?? "")
                                                    .fontWeight(cell?.bold == true ? .bold : .regular)
                                                    .italic(cell?.italic == true)
                                                    .lineLimit(1).padding(.horizontal, 4)
                                                    .frame(width: width, height: Double(min(cell?.mergeRows ?? 1, tile.firstRow + 100 - row)) * 37 - 1, alignment: cell?.align == "right" ? .trailing : cell?.align == "center" ? .center : .leading)
                                                    .background(Theme.input)
                                                    .contextMenu { Button("引用这个单元格") { location = sheet.name + " · " + columnName(column) + String(row + 1); showingFeedback = true } }
                                            }.zIndex(cell?.mergeRows != nil ? 1 : 0)
                                    }
                                }
                            }
                        }.font(.caption)
                    }
                } else { Spacer(); ProgressView(); Spacer() }
            }
        }
    }
    private func rowWindowLabel(_ key: String) -> String {
        let first = (Int(key.split(separator: "-").last ?? "0") ?? 0) * 100 + 1
        return "\(first)–\(first + 99)"
    }
    private func columnWidth(_ sheet: WorkbookIndex.Sheet, _ column: Int) -> Double {
        let value = column < sheet.widths.count ? sheet.widths[column] : 10
        return min(240, max(60, value * 7 + 12))
    }
    private func columnName(_ column: Int) -> String { var value = column + 1, name = ""; while value > 0 { value -= 1; name = String(UnicodeScalar(65 + value % 26)!) + name; value /= 26 }; return name }
}

struct ResultPDFView: UIViewRepresentable {
    let url: URL
    @Binding var page: Int
    func makeCoordinator() -> Coordinator { Coordinator(page: $page) }
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView(); view.autoScales = true; view.displayMode = .singlePageContinuous; view.document = PDFDocument(url: url)
        context.coordinator.token = NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: view, queue: .main) { [weak view, weak coordinator = context.coordinator] _ in
            guard let view, let current = view.currentPage, let document = view.document else { return }; coordinator?.page.wrappedValue = document.index(for: current) + 1
        }
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) { if view.document?.documentURL != url { view.document = PDFDocument(url: url) } }
    final class Coordinator { var page: Binding<Int>; var token: NSObjectProtocol?; init(page: Binding<Int>) { self.page = page }; deinit { if let token { NotificationCenter.default.removeObserver(token) } } }
}
