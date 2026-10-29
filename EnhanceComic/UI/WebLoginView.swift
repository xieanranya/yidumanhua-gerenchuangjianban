import SwiftUI
import WebKit

/// 网页登录：真 WKWebView 打开站点的登录页，滑块 / 验证码都由用户自己完成，
/// 点「已完成」时把 WebView 里的 Cookie 交给引擎存起来。
///
/// 绅士漫画（wnacg）现在必须这么登 —— 账号密码接口会被滑块拦死，
/// 外部 Safari 登录又拿不回 Cookie。
struct WebLoginView: View {

    let key: String
    let url: String
    let sourceName: String
    let onDone: () -> Void
    let onCancel: () -> Void

    @Environment(\.appColors) private var c
    @StateObject private var box = WebBox()
    @State private var busy = false
    @State private var message: String?

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "网页登录 · \(sourceName)",
                subtitle: nil,
                onBack: { onCancel() },
                trailing: AnyView(
                    Button {
                        submit()
                    } label: {
                        Text(busy ? "校验中…" : "已完成")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(busy ? c.fg3 : c.accent)
                    }
                    .buttonStyle(.plain)
                    .disabled(busy)
                )
            )
            HLine()

            Text("在下面把网页登录走完（有滑块就拖一下），看到自己的账号后再点右上角「已完成」。")
                .font(.system(size: 11))
                .foregroundColor(c.fg2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

            if let message {
                Text(message)
                    .font(.system(size: 11))
                    .foregroundColor(c.bad)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 6)
            }

            WebView(url: url, box: box)
                .background(Color.white)
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private func submit() {
        guard !busy else { return }
        busy = true
        message = nil
        guard let web = box.web else {
            busy = false
            message = "网页还没加载好，稍等一下再点"
            return
        }
        // 把 www / 裸域 / 父域 的 Cookie 都读出来合并：登录页在 www.x.com，
        // 源请求的可能是 x.com，只读一个 host 会漏掉站点写在父域上的 Cookie。
        let host = URL(string: url)?.host ?? ""
        var domains = Set<String>()
        if !host.isEmpty {
            domains.insert(host)
            if host.hasPrefix("www.") { domains.insert(String(host.dropFirst(4))) }
            let parts = host.split(separator: ".")
            if parts.count >= 2 { domains.insert(parts.suffix(2).joined(separator: ".")) }
        }

        let store = web.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies in
            let picked = cookies.filter { ck in
                let d = ck.domain.hasPrefix(".") ? String(ck.domain.dropFirst()) : ck.domain
                return domains.isEmpty || domains.contains(d) || domains.contains(where: { d.hasSuffix($0) })
            }
            var seen = Set<String>()
            var parts: [String] = []
            for ck in picked where seen.insert(ck.name).inserted {
                parts.append("\(ck.name)=\(ck.value)")
            }
            let header = parts.joined(separator: "; ")
            guard !header.isEmpty else {
                DispatchQueue.main.async {
                    busy = false
                    message = "没读到 Cookie：请确认网页里已经登录成功"
                }
                return
            }
            DispatchQueue.main.async { send(header) }
        }
    }

    private func send(_ header: String) {
        Task {
            let r = await SourceEngine.shared.loginWithCookieString(
                key: key, url: url, cookie: header
            )
            await MainActor.run {
                busy = false
                if (r["ok"] as? Bool) == true {
                    onDone()
                } else {
                    message = r["error"] as? String ?? "登录失败"
                }
            }
        }
    }
}

/// 让 SwiftUI 拿到同一个 WKWebView 实例（点「已完成」时要从它身上读 Cookie）。
final class WebBox: ObservableObject {
    weak var web: WKWebView?
}

private struct WebView: UIViewRepresentable {
    let url: String
    let box: WebBox

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        let w = WKWebView(frame: .zero, configuration: cfg)
        w.allowsBackForwardNavigationGestures = true
        w.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        box.web = w
        if let u = URL(string: url) { w.load(URLRequest(url: u)) }
        return w
    }

    func updateUIView(_ uiView: WKWebView, context: Context) { }
}
