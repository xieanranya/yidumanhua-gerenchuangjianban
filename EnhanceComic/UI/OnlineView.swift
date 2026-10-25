import SwiftUI

/// 在线源首页：列出所有源和当前线路（直连 / 需 VPN）
struct OnlineSourceView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var sources: [[String: Any]] = []
    @State private var routes: [String: [String: Any]] = [:]
    @State private var loading = true
    @State private var error: String?
    @State private var accountTarget: AccountTarget?

    var body: some View {
        NavigationStack {
            Group {
                if loading && sources.isEmpty {
                    ProgressView("正在启动引擎…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        Section {
                            ForEach(sources.indices, id: \.self) { i in
                                let s = sources[i]
                                let key = s["key"] as? String ?? ""
                                let name = s["name"] as? String ?? key
                                let route = routes[key]
                                let canLogin = (s["canLogin"] as? Bool) == true
                                let logged = (s["logged"] as? Bool) == true
                                HStack(spacing: 8) {
                                    NavigationLink {
                                        BrowseView(key: key, title: name)
                                    } label: {
                                        SourceCell(
                                            name: name,
                                            host: route?["host"] as? String ?? "",
                                            line: route?["line"] as? String ?? ""
                                        )
                                    }
                                    if canLogin {
                                        Button {
                                            accountTarget = AccountTarget(key: key, name: name)
                                        } label: {
                                            Text(logged ? "已登录" : "登录")
                                                .font(.system(size: 11))
                                                .foregroundStyle(logged ? Color.green : Color.blue)
                                                .padding(.horizontal, 9).padding(.vertical, 4)
                                                .background((logged ? Color.green : Color.blue).opacity(0.12),
                                                            in: RoundedRectangle(cornerRadius: 6))
                                        }
                                        .buttonStyle(.borderless)
                                    }
                                }
                            }
                        } footer: {
                            Text("能用直连的走直连；标「需 VPN」的来源在打开代理后自动可用。" +
                                 "点书名进这个源；要登录点右边的「登录」，里面有账号信息、退出登录。")
                        }
                    }
                }
            }
            .navigationTitle("在线漫画源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { dismiss() }
                }
            }
            .task { await bootstrap() }
            .navigationDestination(isPresented: Binding(
                get: { accountTarget != nil },
                set: { shown in
                    if !shown {
                        accountTarget = nil
                        Task { await reload() }
                    }
                }
            )) {
                AccountView(key: accountTarget?.key ?? "",
                            fallbackName: accountTarget?.name ?? "")
            }
        }
    }

    private func bootstrap() async {
        _ = await SourceEngine.shared.start()
        await reload()
        loading = false
        await SourceEngine.shared.probeRoutes()
        await reload()
    }

    private func reload() async {
        let srcs = await SourceEngine.shared.sources()
        let st = await SourceEngine.shared.routeStatus()
        var map: [String: [String: Any]] = [:]
        for r in st {
            if let k = r["key"] as? String { map[k] = r }
        }
        await MainActor.run {
            sources = srcs
            routes = map
        }
    }
}

/// 从源列表直接跳账号页时带的参数
struct AccountTarget: Identifiable, Equatable {
    let id = UUID()
    let key: String
    let name: String
}

/// 独立账号页：登录 / 退出 / 看账号信息。照着 PicACG 那种「先登录再进内容」的习惯做的。
struct AccountView: View {
    let key: String
    let fallbackName: String

    @Environment(\.openURL) private var openURL
    @State private var info: [String: Any] = [:]
    @State private var route: [String: Any] = [:]
    @State private var loading = true
    @State private var loadError: String?
    @State private var user = ""
    @State private var pass = ""
    @State private var busy = false
    @State private var loginError: String?
    @State private var notice: String?

    private var name: String {
        let n = info["name"] as? String ?? ""
        return n.isEmpty ? fallbackName : n
    }
    private var logged: Bool { (info["logged"] as? Bool) == true }
    private var canLogin: Bool { (info["canLogin"] as? Bool) == true }
    private var canWeb: Bool { (info["canWebLogin"] as? Bool) == true }
    private var registerUrl: String { info["registerUrl"] as? String ?? "" }
    private var webUrl: String { info["loginWebUrl"] as? String ?? "" }
    private var savedAccount: String { info["savedAccount"] as? String ?? "" }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 15).fill(Color.accentColor.opacity(0.15))
                        Text(String(name.prefix(1)))
                            .font(.system(size: 24, weight: .bold))
                            .foregroundStyle(.tint)
                    }
                    .frame(width: 58, height: 58)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(name).font(.system(size: 17, weight: .semibold))
                        Text(routeLine)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Text(statusText)
                        .font(.system(size: 11))
                        .foregroundStyle(statusColor)
                        .padding(.horizontal, 9).padding(.vertical, 4)
                        .background(statusColor.opacity(0.12),
                                    in: RoundedRectangle(cornerRadius: 6))
                }
                .padding(.vertical, 4)
            }

            if loading {
                Section { ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24) }
            } else if canLogin {
                if logged {
                    Section("已登录账号") {
                        Text(savedAccount.isEmpty ? "（该源未回传账号名）" : savedAccount)
                            .font(.system(size: 15, weight: .medium))
                        infoRows
                    }
                    if let notice {
                        Section {
                            Text(notice).font(.system(size: 12)).foregroundStyle(.green)
                        }
                    }
                    Section {
                        Button(role: .destructive) {
                            Task { await logout() }
                        } label: {
                            HStack {
                                Text("退出登录")
                                if busy { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(busy)
                    } footer: {
                        Text("退出后这个源要重新登录才能搜索和看图。")
                    }
                } else {
                    Section {
                        TextField("账号 / 邮箱", text: $user)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.emailAddress)
                        SecureField("密码", text: $pass)
                    } header: {
                        Text("登录 \(name)")
                    } footer: {
                        Text("账号密码只存在本机，不会上传。登录后这个源才能搜索和看图。")
                    }
                    if let loginError {
                        Section {
                            Text(loginError).font(.system(size: 12)).foregroundStyle(.red)
                        }
                    }
                    Section {
                        Button {
                            Task { await login() }
                        } label: {
                            HStack {
                                Text(busy ? "登录中…" : "登录")
                                if busy { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(busy || user.isEmpty || pass.isEmpty)
                        if !registerUrl.isEmpty {
                            Button("还没有账号？去 \(name) 注册") {
                                if let u = URL(string: registerUrl) { openURL(u) }
                            }
                        }
                    }
                }
            } else if canWeb {
                Section {
                    Text("这个源不支持账号密码，登录是在网页里完成的（靠 Cookie）。" +
                         "App 内暂时没法替你填，公开内容不登录也能看。")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                Section {
                    if !webUrl.isEmpty {
                        Button("打开登录网页") {
                            if let u = URL(string: webUrl) { openURL(u) }
                        }
                    }
                    if !registerUrl.isEmpty {
                        Button("去 \(name) 注册") {
                            if let u = URL(string: registerUrl) { openURL(u) }
                        }
                    }
                }
            } else {
                Section {
                    Text("这个源不用登录，直接搜索就行。")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
            }

            if let loadError {
                Section {
                    Text(loadError).font(.system(size: 12)).foregroundStyle(.red)
                }
            }

            Section {
            } footer: {
                Text("登录凭证保存在本机，不上传任何服务器；换设备要重新登录。")
            }
        }
        .navigationTitle("账号")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
    }

    private var routeLine: String {
        var parts: [String] = []
        let host = route["host"] as? String ?? ""
        if !host.isEmpty { parts.append(host) }
        switch route["line"] as? String ?? "" {
        case "direct": parts.append("直连")
        case "proxy": parts.append("需 VPN")
        default: break
        }
        return parts.isEmpty ? "线路未检测" : parts.joined(separator: " · ")
    }

    private var statusText: String {
        if logged { return "已登录" }
        if canLogin || canWeb { return canLogin ? "未登录" : "网页登录" }
        return "该源不用登录"
    }

    private var statusColor: Color {
        if logged { return .green }
        if canLogin || canWeb { return .orange }
        return .secondary
    }

    @ViewBuilder
    private var infoRows: some View {
        let items = info["items"] as? [[String: Any]] ?? []
        let shown = items.filter {
            let t = $0["title"] as? String ?? ""
            let v = $0["value"] as? String ?? ""
            let a = $0["action"] as? String ?? ""
            return !t.isEmpty && !(v.isEmpty && a.isEmpty)
        }
        if shown.isEmpty {
            Text("这个源没有回传账号信息。如果功能还是用不了，先退出再登录一次。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        } else {
            ForEach(shown.indices, id: \.self) { i in
                HStack(alignment: .top) {
                    Text(shown[i]["title"] as? String ?? "")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .frame(width: 78, alignment: .leading)
                    let v = shown[i]["value"] as? String ?? ""
                    Text(v.isEmpty ? "需在源设置里填（App 暂不支持）" : v)
                        .font(.system(size: 12))
                    Spacer(minLength: 0)
                }
            }
        }
    }

    private func refresh() async {
        let r = await SourceEngine.shared.account(key: key)
        await MainActor.run {
            if (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] {
                info = d
                loadError = nil
                let saved = d["savedAccount"] as? String ?? ""
                if !saved.isEmpty && user.isEmpty { user = saved }
            } else {
                loadError = r["error"] as? String ?? "账号信息读取失败"
            }
            loading = false
        }
        let st = await SourceEngine.shared.routeStatus()
        for s in st where (s["key"] as? String) == key {
            await MainActor.run { route = s }
            break
        }
    }

    private func login() async {
        busy = true
        loginError = nil
        notice = nil
        let r = await SourceEngine.shared.login(key: key, user: user.trimmingCharacters(in: .whitespaces),
                                                password: pass)
        busy = false
        if (r["ok"] as? Bool) == true {
            pass = ""
            notice = "登录成功"
            await refresh()
        } else {
            loginError = "登录失败：" + (r["error"] as? String ?? "账号或密码不对")
        }
    }

    private func logout() async {
        busy = true
        _ = await SourceEngine.shared.logout(key: key)
        busy = false
        pass = ""
        notice = "已退出登录"
        await refresh()
    }
}

private struct SourceCell: View {
    let name: String
    let host: String
    let line: String

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(Color.accentColor.opacity(0.15))
                Text(String(name.prefix(1))).font(.system(size: 17, weight: .bold)).foregroundStyle(.tint)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.system(size: 15, weight: .medium))
                Text(host.isEmpty ? "未检测" : host)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if !line.isEmpty {
                let direct = line == "direct"
                Text(direct ? "直连" : "需 VPN")
                    .font(.system(size: 11))
                    .foregroundStyle(direct ? Color.green : Color.orange)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(
                        (direct ? Color.green : Color.orange).opacity(0.12),
                        in: RoundedRectangle(cornerRadius: 6)
                    )
            }
        }
        .padding(.vertical, 2)
    }
}

// MARK: - 搜索 / 探索

struct ExploreSectionModel: Identifiable {
    let id = UUID()
    let title: String
    let comics: [ComicItem]

    static func parse(_ d: [String: Any]) -> [ExploreSectionModel] {
        let arr = d["sections"] as? [[String: Any]] ?? []
        return arr.compactMap { s in
            let cs = (s["comics"] as? [[String: Any]] ?? []).compactMap { o -> ComicItem? in
                guard let id = o["id"] as? String, !id.isEmpty else { return nil }
                return ComicItem(id: id, title: o["title"] as? String ?? "",
                                 cover: o["cover"] as? String ?? "")
            }
            return cs.isEmpty ? nil : ExploreSectionModel(title: s["title"] as? String ?? "", comics: cs)
        }
    }
}

struct BrowseView: View {
    let key: String
    let title: String

    @State private var keyword = ""
    @State private var sections: [ExploreSectionModel] = []
    @State private var busy = true
    @State private var error: String?
    @State private var searched = false
    @State private var logged = false
    @State private var canLogin = true

    private let columns = [GridItem(.adaptive(minimum: 100), spacing: 10)]

    var body: some View {
        ScrollView {
            if busy {
                ProgressView().padding(28)
            } else if sections.allSatisfy({ $0.comics.isEmpty }) {
                emptyState
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(sections) { sec in
                        VStack(alignment: .leading, spacing: 6) {
                            if !sec.title.isEmpty {
                                Text(sec.title)
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.tint)
                            }
                            LazyVGrid(columns: columns, spacing: 12) {
                                ForEach(sec.comics) { c in
                                    NavigationLink {
                                        DetailView(sourceKey: key, comicId: c.id, fallbackTitle: c.title)
                                    } label: {
                                        ComicCell(item: c)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                .padding(12)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $keyword, prompt: "搜索关键词")
        .onSubmit(of: .search) { Task { await search(keyword) } }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink {
                    AccountView(key: key, fallbackName: title)
                } label: {
                    Text(logged ? "已登录" : "账号").font(.system(size: 13))
                }
            }
        }
        .task { await reload() }
        .onAppear {
            // 从账号页回来时只刷登录态，不重新拉列表（否则搜索/滚动位置全丢）
            Task { await refreshAccount() }
        }
    }

    /// 引擎的网络错误太生硬（"HTTP 0"），换成用户能照做的说法
    private func friendlyError(_ e: String) -> String {
        let low = e.lowercased()
        if low.contains("status code: 0") || e.contains("HTTP 0")
            || low.contains("请求失败: 0") || low.contains("timeout") {
            return "连不上这个源。它多半在墙外，先把 VPN 打开再点「重新加载」。"
        }
        return e
    }

    private func refreshAccount() async {
        let a = await SourceEngine.shared.account(key: key)
        guard (a["ok"] as? Bool) == true, let d = a["data"] as? [String: Any] else { return }
        logged = (d["logged"] as? Bool) == true
        canLogin = (d["canLogin"] as? Bool) != false
    }

    /// 空状态：说清为什么空、下一步点哪，别只留一个搜索框
    @ViewBuilder
    private var emptyState: some View {
        let e = error ?? ""
        let loginLikely = e.contains("登录") || e.lowercased().contains("login")
        VStack(spacing: 10) {
            Image(systemName: loginLikely ? "person.crop.circle.badge.exclamationmark" : "magnifyingglass")
                .font(.system(size: 40))
                .foregroundStyle(.secondary.opacity(0.55))
            Text(loginLikely ? "这个源要先登录"
                 : (searched ? "没有结果" : (e.isEmpty ? "这个源没有首页" : "没能取到内容")))
                .font(.system(size: 15, weight: .medium))
            Text(loginLikely ? "填这个站的账号，登录后就能搜索和看图。"
                 : (e.isEmpty ? "直接在搜索框里输关键词试试。" : friendlyError(e)))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if canLogin && !logged {
                NavigationLink {
                    AccountView(key: key, fallbackName: title)
                } label: {
                    Text("去登录").frame(maxWidth: .infinity).padding(.vertical, 3)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 6)
            }
            Button("重新加载") { Task { await reload() } }
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 44)
        .frame(maxWidth: .infinity)
    }

    private func reload() async {
        error = nil
        busy = true

        let a = await SourceEngine.shared.account(key: key)
        if (a["ok"] as? Bool) == true, let d = a["data"] as? [String: Any] {
            logged = (d["logged"] as? Bool) == true
            canLogin = (d["canLogin"] as? Bool) != false
        }

        let r = await SourceEngine.shared.explore(key: key)
        busy = false
        searched = false
        if (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] {
            sections = ExploreSectionModel.parse(d)
            if sections.isEmpty { error = nil }
        } else {
            sections = []
            error = r["error"] as? String ?? "加载失败"
        }
    }

    private func search(_ kw: String) async {
        let q = kw.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        busy = true; error = nil
        let r = await SourceEngine.shared.search(key: key, keyword: q, options: "[]", page: 1)
        busy = false
        searched = true
        if (r["ok"] as? Bool) == true, let data = r["data"] as? [String: Any] {
            let list = ComicItem.parseSearch(data)
            sections = list.isEmpty ? [] : [ExploreSectionModel(title: "", comics: list)]
            if list.isEmpty { error = "没有找到「\(q)」" }
        } else {
            sections = []
            error = r["error"] as? String ?? "搜索失败"
        }
    }
}


private struct ComicCell: View {
    let item: ComicItem
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Color.clear
                .aspectRatio(0.72, contentMode: .fit)
                .overlay {
                    AsyncImage(url: URL(string: item.cover)) { phase in
                        if let img = phase.image {
                            img.resizable().scaledToFill()
                        } else {
                            Rectangle().fill(Color.secondary.opacity(0.12))
                                .overlay(ProgressView().scaleEffect(0.6))
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
            Text(item.title)
                .font(.system(size: 11))
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
    }
}

// MARK: - 详情

struct DetailView: View {
    let sourceKey: String
    let comicId: String
    let fallbackTitle: String

    @State private var info: [String: Any] = [:]
    @State private var groups: [(title: String, items: [(id: String, title: String)])] = []
    @State private var busy = true
    @State private var error: String?
    @State private var reading: ReadingPayload?

    var body: some View {
        Group {
            if busy {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error {
                Text(error).foregroundStyle(.red).padding()
            } else {
                List {
                    Section {
                        HStack(alignment: .top, spacing: 12) {
                            AsyncImage(url: URL(string: info["cover"] as? String ?? "")) { phase in
                                if let img = phase.image {
                                    img.resizable().scaledToFill()
                                } else {
                                    Rectangle().fill(Color.secondary.opacity(0.12))
                                }
                            }
                            .frame(width: 92, height: 128)
                            .clipShape(RoundedRectangle(cornerRadius: 8))

                            VStack(alignment: .leading, spacing: 6) {
                                Text(info["title"] as? String ?? fallbackTitle)
                                    .font(.system(size: 15, weight: .semibold))
                                Text((info["description"] as? String ?? "")
                                    .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression))
                                    .font(.system(size: 11))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(6)
                            }
                        }
                    }
                    ForEach(groups.indices, id: \.self) { gi in
                        Section(groups[gi].title) {
                            ForEach(groups[gi].items.indices, id: \.self) { ci in
                                let ch = groups[gi].items[ci]
                                Button {
                                    Task { await open(ch.id, ch.title) }
                                } label: {
                                    Text(ch.title).font(.system(size: 13))
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(info["title"] as? String ?? fallbackTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink {
                    AccountView(key: sourceKey, fallbackName: fallbackTitle)
                } label: {
                    Text("账号").font(.system(size: 13))
                }
            }
        }
        .task { await load() }
        .sheet(item: $reading) { payload in
            ReaderSheet(payload: payload)
        }
    }

    private func load() async {
        busy = true
        let r = await SourceEngine.shared.detail(key: sourceKey, id: comicId)
        busy = false
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            error = r["error"] as? String ?? "加载失败"
            return
        }
        info = d
        var gs: [(String, [(String, String)])] = []
        for g in (d["groups"] as? [[String: Any]] ?? []) {
            let t = g["title"] as? String ?? ""
            let items = (g["items"] as? [[String: Any]] ?? []).compactMap { o -> (String, String)? in
                guard let id = o["id"] as? String else { return nil }
                return (id, o["title"] as? String ?? id)
            }
            gs.append((t, items))
        }
        groups = gs
    }

    private func open(_ epId: String, _ epTitle: String) async {
        busy = true
        let r = await SourceEngine.shared.pages(key: sourceKey, comicId: comicId, epId: epId)
        busy = false
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            error = r["error"] as? String ?? "取图失败"
            return
        }
        // 惰性源（E-Hentai 这类一页一次 API）只回页数，真地址进阅读器再逐页解
        if (d["lazy"] as? Bool) == true {
            let n = d["count"] as? Int ?? 0
            guard n > 0 else { error = "这一话没有图片（可能需要登录）"; return }
            reading = ReadingPayload(
                title: epTitle, urls: [],
                lazy: LazyRef(key: sourceKey, comicId: comicId, epId: epId, count: n))
            return
        }
        let urls = d["images"] as? [String] ?? []
        guard !urls.isEmpty else {
            error = "这一话没有图片（可能需要登录）"
            return
        }
        reading = ReadingPayload(title: epTitle, urls: urls)
    }
}

/// 惰性取图的引用信息
struct LazyRef {
    let key: String
    let comicId: String
    let epId: String
    let count: Int
}

struct ReadingPayload: Identifiable {
    let id = UUID()
    let title: String
    let urls: [String]
    var lazy: LazyRef? = nil
}

/// 在线章节阅读：竖向连续，跟本地阅读器的习惯一致
struct ReaderSheet: View {
    let payload: ReadingPayload

    var body: some View {
        if let lz = payload.lazy {
            LazyReaderSheet(payload: payload, ref: lz)
        } else {
            PlainReaderSheet(payload: payload)
        }
    }
}

/// 惰性阅读：翻到哪解到哪，另加一页预取
struct LazyReaderSheet: View {
    let payload: ReadingPayload
    let ref: LazyRef
    @Environment(\.dismiss) private var dismiss
    @State private var urls: [Int: String] = [:]
    @State private var fails: [Int: String] = [:]
    @State private var loading: Set<Int> = []
    @State private var showBar = true

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(0..<ref.count, id: \.self) { i in
                        Group {
                            if let u = urls[i] {
                                AsyncImage(url: URL(string: u)) { phase in
                                    if let img = phase.image {
                                        img.resizable().scaledToFit()
                                    } else if phase.error != nil {
                                        retryText(i, phase.error?.localizedDescription ?? "加载失败")
                                    } else {
                                        Color.black.frame(height: 360)
                                            .overlay(ProgressView().tint(.white))
                                    }
                                }
                            } else if let f = fails[i] {
                                retryText(i, f)
                            } else {
                                Color.black.frame(height: 360)
                                    .overlay(ProgressView().tint(.white))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .task {
                            await load(i)
                            await load(i + 1)   // 预取下一页
                        }
                    }
                }
            }
            .ignoresSafeArea()

            if showBar {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left").foregroundStyle(.white)
                    }
                    Text(payload.title).foregroundStyle(.white).font(.system(size: 13)).lineLimit(1)
                    Spacer()
                    Text("\(urls.count)/\(ref.count) 页")
                        .foregroundStyle(.white.opacity(0.7)).font(.system(size: 12))
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.black.opacity(0.55))
            }
        }
        .statusBarHidden()
        .onTapGesture { showBar.toggle() }
    }

    private func retryText(_ i: Int, _ msg: String) -> some View {
        Text("第 \(i + 1) 页没取到：\(msg)\n点这里重试")
            .font(.system(size: 12))
            .multilineTextAlignment(.center)
            .foregroundStyle(.white.opacity(0.85))
            .frame(maxWidth: .infinity)
            .frame(height: 200)
            .contentShape(Rectangle())
            .onTapGesture {
                fails[i] = nil
                Task { await load(i) }
            }
    }

    @MainActor
    private func load(_ i: Int) async {
        if i < 0 || i >= ref.count { return }
        if urls[i] != nil || fails[i] != nil || loading.contains(i) { return }
        loading.insert(i)
        let r = await SourceEngine.shared.pageImage(
            key: ref.key, comicId: ref.comicId, epId: ref.epId, index: i)
        loading.remove(i)
        if (r["ok"] as? Bool) == true,
           let d = r["data"] as? [String: Any],
           let u = d["url"] as? String, !u.isEmpty {
            urls[i] = u
        } else {
            fails[i] = r["error"] as? String ?? "取图失败"
        }
    }
}

struct PlainReaderSheet: View {
    let payload: ReadingPayload
    @Environment(\.dismiss) private var dismiss
    @State private var showBar = true

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(payload.urls.indices, id: \.self) { i in
                        AsyncImage(url: URL(string: payload.urls[i])) { phase in
                            if let img = phase.image {
                                img.resizable().scaledToFit()
                            } else {
                                Color.black.frame(height: 360)
                                    .overlay(ProgressView().tint(.white))
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .ignoresSafeArea()

            if showBar {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left").foregroundStyle(.white)
                    }
                    Text(payload.title).foregroundStyle(.white).font(.system(size: 13)).lineLimit(1)
                    Spacer()
                    Text("\(payload.urls.count) 页").foregroundStyle(.white.opacity(0.7)).font(.system(size: 12))
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.black.opacity(0.55))
            }
        }
        .statusBarHidden()
        .onTapGesture { showBar.toggle() }
    }
}

// MARK: - 数据

struct ComicItem: Identifiable {
    let id: String
    let title: String
    let cover: String

    static func parseSearch(_ d: [String: Any]) -> [ComicItem] {
        ((d["comics"] as? [[String: Any]]) ?? []).compactMap {
            guard let id = $0["id"] as? String else { return nil }
            return ComicItem(id: id, title: $0["title"] as? String ?? "", cover: $0["cover"] as? String ?? "")
        }
    }

    /// 探索页返回 {分区: [comic]}，取第一个非空分区
    static func parseExplore(_ d: [String: Any]) -> [ComicItem] {
        for (_, v) in d {
            if let arr = v as? [[String: Any]], !arr.isEmpty {
                return arr.compactMap {
                    guard let id = $0["id"] as? String else { return nil }
                    return ComicItem(id: id, title: $0["title"] as? String ?? "", cover: $0["cover"] as? String ?? "")
                }
            }
        }
        return []
    }
}
