import Foundation

/// Runs inside an iOS Simulator. Never loads credentials or follows redirects.
final class ProbeRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@main struct IOSServiceProbe {
    static func main() async {
        guard CommandLine.arguments.count == 2,
              let origin = URL(string: CommandLine.arguments[1]),
              origin.scheme == "https", let host = origin.host, !host.isEmpty,
              origin.user == nil, origin.password == nil, origin.query == nil, origin.fragment == nil,
              origin.path.isEmpty || origin.path == "/" else {
            print("Provide an HTTPS origin without credentials, paths, queries, or fragments.")
            exit(2)
        }
        let session = URLSession(configuration: .ephemeral, delegate: ProbeRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var failed = false
        for path in ["session", "sync/sessions?limit=1"] {
            guard let url = URL(string: "api/account/" + path, relativeTo: origin)?.absoluteURL else { exit(2) }
            let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
            do {
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    print("FAIL \(path): non-HTTP response"); failed = true; continue
                }
                let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
                let noStore = http.value(forHTTPHeaderField: "Cache-Control")?.lowercased().contains("no-store") == true
                let valid = http.statusCode == 401 && payload?["code"] as? String == "UNAUTHENTICATED" && noStore
                print("\(valid ? "PASS" : "FAIL") \(path): HTTP \(http.statusCode), account response=\(payload?["code"] as? String == "UNAUTHENTICATED"), no-store=\(noStore)")
                failed = failed || !valid
            } catch {
                // Do not print response bodies, headers, or arbitrary server error text.
                print("FAIL \(path): TLS/network error \((error as NSError).code)")
                failed = true
            }
        }
        exit(failed ? 1 : 0)
    }
}
