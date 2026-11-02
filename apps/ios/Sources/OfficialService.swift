import Foundation

/// Configured official entries share one account authority. Custom services remain isolated.
enum OfficialService {
    static let international = URL(string: "https://funnywork.funnypixelsapp.com")!
    static let mainland: URL = {
        let value = Bundle.main.object(forInfoDictionaryKey: "FunnyWorkRegionalOrigin") as? String ?? ""
        guard !value.isEmpty, !value.hasPrefix("$(") else { return international }
        guard let url = URL(string: value), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.port == nil,
              url.path.isEmpty, url.query == nil, url.fragment == nil,
              url.absoluteString == "https://" + url.host!.lowercased() else {
            preconditionFailure("Invalid regional HTTPS origin")
        }
        return url
    }()
    static var entries: [URL] { mainland == international ? [international] : [mainland, international] }
    static func identity(_ url: URL) -> URL {
        guard url.scheme == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443 else { return url }
        if entries.map(\.host).contains(url.host?.lowercased()) {
            return international
        }
        return url
    }
    static func managed(_ url: URL) -> Bool { identity(url) == international }
}

private final class EntryProbeDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

/// Readiness probes carry no credentials. Failed writes are never replayed.
actor OfficialEntryRouter {
    static let shared: OfficialEntryRouter = {
        #if DEBUG
        // Live UI acceptance pins one real official entry; production races configured entries.
        if ProcessInfo.processInfo.arguments.contains("--live-ui"),
           let value = ProcessInfo.processInfo.environment["FUNNYWORK_LIVE_UI_ORIGIN"],
           let entry = URL(string: value),
           OfficialService.entries.contains(entry) {
            return OfficialEntryRouter(fixedEntry: entry)
        }
        #endif
        return OfficialEntryRouter()
    }()
    private let fixedEntry: URL?
    private var active: URL?
    private var selecting: Task<URL, Error>?
    private var unavailable: [URL: Date] = [:]

    init(fixedEntry: URL? = nil) {
        precondition(fixedEntry == nil || OfficialService.entries.contains(fixedEntry!))
        self.fixedEntry = fixedEntry
    }

    func endpoint(for server: URL) async throws -> URL {
        guard OfficialService.managed(server) else { return server }
        if let active { return active }
        if let selecting { return try await selecting.value }
        let all = fixedEntry.map { [$0] } ?? OfficialService.entries
        let available = all.filter { (unavailable[$0] ?? .distantPast) <= Date() }
        let candidates = available.isEmpty ? all : available
        let selection = Task<URL, Error> {
            let found: URL? = await withTaskGroup(of: URL?.self) { group in
                for entry in candidates {
                    group.addTask {
                        let configuration = URLSessionConfiguration.ephemeral
                        configuration.timeoutIntervalForResource = 5
                        let session = URLSession(configuration: configuration,
                                                 delegate: EntryProbeDelegate(), delegateQueue: nil)
                        defer { session.invalidateAndCancel() }
                        var request = URLRequest(url: entry.appendingPathComponent("api/account/session"),
                                                 cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 4)
                        request.setValue("FunnyWork-entry-check/1.0", forHTTPHeaderField: "User-Agent")
                        do {
                            let (_, response) = try await session.data(for: request)
                            guard let http = response as? HTTPURLResponse, http.statusCode == 401 else { return nil }
                            return entry
                        } catch { return nil }
                    }
                }
                for await value in group {
                    if let value { group.cancelAll(); return value }
                }
                return nil
            }
            guard let found else { throw URLError(.cannotConnectToHost) }
            return found
        }
        selecting = selection
        do {
            let entry = try await selection.value
            active = entry; selecting = nil
            return entry
        } catch {
            selecting = nil
            throw error
        }
    }

    func failed(_ endpoint: URL) {
        guard OfficialService.managed(endpoint) else { return }
        let entry = endpoint.host == OfficialService.mainland.host ? OfficialService.mainland : OfficialService.international
        unavailable[entry] = Date().addingTimeInterval(30)
        if active == entry { active = nil }
    }
}
