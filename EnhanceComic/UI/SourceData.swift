import SwiftUI

// =========================================================================== 源的界面状态

/// 一个源在界面上的状态。
///
/// 只有登录与开关——**没有线路这个概念**。域名打不开会自动换别名重试一次，
/// 用户不需要知道「直连还是 VPN」，就像普通聚合软件那样。
struct SourceRow: Identifiable, Equatable {
    let key: String
    let name: String
    let canLogin: Bool
    let logged: Bool
    let needLogin: Bool
    /// 源自己暴露了签到方法（dailyCheckIn / checkin）
    let canCheckin: Bool
    /// 签到方法名（invoke 时用）
    var checkinMethod: String = ""
    /// 用户是否把它算进「全部源」的搜索与首页推荐里
    var enabled: Bool = true

    var id: String { key }

    /// 套上用户的开关与顺序：`order` 里列出的按它排，没列到的（引擎新增的）保持原顺序跟在后面。
    static func build(sources: [[String: Any]], off: Set<String> = [], order: [String] = []) -> [SourceRow] {
        let rows = sources.compactMap { s -> SourceRow? in
            guard let key = s["key"] as? String, !key.isEmpty else { return nil }
            return SourceRow(
                key: key,
                name: s["name"] as? String ?? key,
                canLogin: (s["canLogin"] as? Bool) == true,
                logged: (s["logged"] as? Bool) == true,
                needLogin: (s["needLogin"] as? Bool) == true,
                canCheckin: (s["canCheckin"] as? Bool) == true,
                checkinMethod: s["checkinMethod"] as? String ?? ""
            )
        }
        return rows
            .enumerated()
            .sorted { a, b in
                let ra = order.firstIndex(of: a.element.key) ?? Int.max
                let rb = order.firstIndex(of: b.element.key) ?? Int.max
                // 都没列到的保持引擎原顺序（用原下标兜底），排序才是稳定的
                return ra == rb ? a.offset < b.offset : ra < rb
            }
            .map { $0.element }
            .map { row in
                var r = row
                r.enabled = !off.contains(row.key)
                return r
            }
    }
}

/// 源小结：只说数量与开关，不提线路（已经不存在这个概念）。
func sourceSummary(_ rows: [SourceRow]) -> String {
    if rows.isEmpty { return "正在读取源列表…" }
    let logged = rows.filter { $0.logged }.count
    let on = rows.filter { $0.enabled }.count
    var s = "\(on)/\(rows.count) 个源在用"
    if logged > 0 { s += " · \(logged) 个已登录" }
    return s
}

/// 源抛出来的网络错误太生硬（"HTTP 0"），换成用户能照做的说法。
func friendlyError(_ e: String) -> String {
    let low = e.lowercased()
    if low.contains("status code: 0") || e.contains("HTTP 0")
        || low.contains("请求失败: 0") || low.contains("timed out") || low.contains("timeout") {
        return "连不上这个源。先确认网站本身能打开；E-Hentai / 绅士漫画 / PicACG 这类站点国内要开代理。"
    }
    return e
}

// =============================================================================== 作品与聚合

/// 一条列表项（一个源里的一本书）。
struct ComicRow: Identifiable, Hashable {
    let sourceKey: String
    let sourceName: String
    let comicId: String
    let title: String
    let cover: String
    var sub: String = ""

    var id: String { sourceKey + "|" + comicId }
}

struct ExploreSection: Identifiable {
    let id = UUID()
    let title: String
    let comics: [ComicRow]

    static func parse(_ d: [String: Any], sourceKey: String, sourceName: String) -> [ExploreSection] {
        let arr = d["sections"] as? [[String: Any]] ?? []
        return arr.compactMap { s in
            let cs = (s["comics"] as? [[String: Any]] ?? []).compactMap { o -> ComicRow? in
                guard let cid = o["id"] as? String, !cid.isEmpty else { return nil }
                return ComicRow(
                    sourceKey: sourceKey,
                    sourceName: sourceName,
                    comicId: cid,
                    title: o["title"] as? String ?? "",
                    cover: o["cover"] as? String ?? "",
                    sub: o["subTitle"] as? String ?? ""
                )
            }
            if cs.isEmpty { return nil }
            return ExploreSection(title: s["title"] as? String ?? "", comics: cs)
        }
    }
}

func parseSearchComics(_ d: [String: Any], sourceKey: String, sourceName: String) -> [ComicRow] {
    let arr = d["comics"] as? [[String: Any]] ?? []
    return arr.compactMap { o in
        guard let cid = o["id"] as? String, !cid.isEmpty else { return nil }
        return ComicRow(
            sourceKey: sourceKey,
            sourceName: sourceName,
            comicId: cid,
            title: o["title"] as? String ?? "",
            cover: o["cover"] as? String ?? "",
            sub: o["subTitle"] as? String ?? ""
        )
    }
}

/// 同一本书在某个源里的入口。
struct WorkEntry: Identifiable, Hashable {
    let sourceKey: String
    let sourceName: String
    let comicId: String
    let title: String
    let cover: String

    var id: String { sourceKey + "|" + comicId }
}

/// 聚合后的「一部作品」。entries 按源顺序排列，第一个用来展示。
struct Work: Identifiable {
    let norm: String
    var title: String
    var cover: String
    var entries: [WorkEntry]

    var id: String { norm }
    var sourceCount: Int { entries.count }
    var badge: String { entries.count > 1 ? "\(entries.count) 源" : "" }
    var sourceNames: String {
        var seen: [String] = []
        for e in entries where !seen.contains(e.sourceName) { seen.append(e.sourceName) }
        return seen.joined(separator: " / ")
    }
    var primary: WorkEntry { entries[0] }
}

/// 标题指纹：去掉空白、标点、全角括号，统一小写，只留文字和数字。
///
/// **只做「完全一致才合并」**。各源没有互通的 ID，模糊匹配会把不同的书串成一本，
/// 那种错误比不聚合难受得多，所以宁可少合不可错合。
func normalizeTitle(_ raw: String) -> String {
    var out = ""
    out.reserveCapacity(raw.count)
    for ch in raw.lowercased() {
        if ch.isLetter || ch.isNumber {
            out.append(ch)
        }
    }
    return out
}

/// 把多个源的列表按标题指纹聚合。顺序按「多少源有这本书」降序、其次首次出现顺序。
/// 跨源聚合。`sort = false` 时不做「多源优先」重排，只按首次出现顺序摆——
/// 发现页是流式上屏的，每来一个源都重排会让整片格子跳。
func groupWorks(_ comics: [ComicRow], sort: Bool = true) -> [Work] {
    var index: [String: Int] = [:]
    var out: [Work] = []
    for c in comics {
        let norm = normalizeTitle(c.title)
        guard !norm.isEmpty else { continue }
        let entry = WorkEntry(
            sourceKey: c.sourceKey,
            sourceName: c.sourceName,
            comicId: c.comicId,
            title: c.title,
            cover: c.cover
        )
        if let i = index[norm] {
            if out[i].entries.contains(where: { $0.id == entry.id }) { continue }
            out[i].entries.append(entry)
            // 封面优先用第一个源，没封面时由后面的源补上
            if out[i].cover.isEmpty && !c.cover.isEmpty { out[i].cover = c.cover }
        } else {
            index[norm] = out.count
            out.append(Work(norm: norm, title: c.title, cover: c.cover, entries: [entry]))
        }
    }
    if !sort { return out }
    // 稳定排序：多源优先，其余保持原顺序
    var multi: [Work] = []
    var single: [Work] = []
    for w in out {
        if w.entries.count > 1 { multi.append(w) } else { single.append(w) }
    }
    multi.sort { $0.entries.count > $1.entries.count }
    return multi + single
}

// ============================================================================ 源的共享状态

/// 源列表。首页、发现、我的三个页面共用一份，避免每次切 Tab 都重新读一遍。
///
/// 源的开关与顺序也存在这里：它是这份列表的 owner，改完 `@Published` 一响，
/// 三个页面自己就刷新了，不用再往下传一层。
final class SourceHub: ObservableObject {
    @Published private(set) var rows: [SourceRow] = []
    @Published private(set) var loading = false
    /// 关掉的源（存「关掉的」而不是「开着的」：以后引擎加源默认是开的）
    @Published private(set) var disabled: Set<String>
    /// 用户拖出来的顺序；没列到的按引擎原顺序排在这些之后
    @Published private(set) var order: [String]

    static let shared = SourceHub()

    private enum Keys {
        static let off = "src.off"
        static let order = "src.order"
    }

    private init() {
        let d = UserDefaults.standard
        disabled = Set(d.stringArray(forKey: Keys.off) ?? [])
        order = d.stringArray(forKey: Keys.order) ?? []
    }

    /// 只有开着的源参与「全部源」的搜索、发现的源 chips、首页推荐。
    var enabledRows: [SourceRow] { rows.filter { $0.enabled } }

    func load(force: Bool = false) async {
        if !rows.isEmpty && !force { return }
        if loading { return }
        await MainActor.run { loading = true }
        _ = await SourceEngine.shared.start()
        await refreshRows()
        await MainActor.run { loading = false }
    }

    /// 登录后刷新「已登录」标记
    func refreshRows() async {
        let srcs = await SourceEngine.shared.sources()
        let off = disabled
        let ord = order
        let built = SourceRow.build(sources: srcs, off: off, order: ord)
        await MainActor.run { rows = built }
    }

    func setEnabled(_ key: String, _ on: Bool) {
        var next = disabled
        if on { next.remove(key) } else { next.insert(key) }
        disabled = next
        UserDefaults.standard.set(Array(next), forKey: Keys.off)
        rows = rows.map { r in
            var r = r
            r.enabled = !next.contains(r.key)
            return r
        }
        // 源集合变了，首页/发现页那份「刚拉过的结果」就不对应当前这批源了
        ExploreCache.clear()
    }

    /// 把某个源上移（-1）或下移（+1）。
    func move(_ key: String, by delta: Int) {
        let keys = rows.map { $0.key }
        guard let from = keys.firstIndex(of: key) else { return }
        let to = from + delta
        guard to >= 0, to < keys.count else { return }
        var next = keys
        next.swapAt(from, to)
        order = next
        UserDefaults.standard.set(next, forKey: Keys.order)
        let byKey = Dictionary(uniqueKeysWithValues: rows.map { ($0.key, $0) })
        rows = next.compactMap { byKey[$0] }
        ExploreCache.clear()
    }

    // ================================================================ 自动签到（所有支持的源）
    //
    // 触发时机：App 启动 / 每次回前台。逐个源检查三道闸（开关、登录态、今天没签过），
    // 都直接读 UserDefaults；源里的签到方法自带当日去重，重复触发安全。
    // 失败不弹提示，下次回前台自然再试。签到状态统一记在 d_<key>_lastCheckInDate。

    /// 最近一次签到的结果提示（源管理页显示）
    @Published private(set) var checkinNote: String? = nil

    func maybeAutoCheckin() async {
        await load()
        for row in rows where row.canCheckin && row.enabled {
            guard autoCheckinOn(row.key) else { continue }
            guard row.logged else { continue }
            guard lastCheckinDate(row.key) != Self.checkinToday() else { continue }
            let args: [Any] = row.checkinMethod == "dailyCheckIn" ? [true] : []
            let r = await SourceEngine.shared.invoke(
                key: row.key, method: row.checkinMethod, args: args, timeout: 40)
            if (r["ok"] as? Bool) == true {
                markCheckedIn(row.key)
                await MainActor.run { checkinNote = "\(row.name) 已自动签到" }
            }
        }
    }

    /// 立即签到（源管理页按钮）。返回成功与否 + 文案。
    func checkinNow(_ row: SourceRow) async -> (Bool, String) {
        let args: [Any] = row.checkinMethod == "dailyCheckIn" ? [false] : []
        let r = await SourceEngine.shared.invoke(
            key: row.key, method: row.checkinMethod, args: args, timeout: 40)
        let ok = (r["ok"] as? Bool) == true
        var msg = ok
            ? ((r["value"] as? String) ?? "签到成功")
            : ((r["error"] as? String) ?? "签到失败")
        if ok {
            markCheckedIn(row.key)
        }
        await MainActor.run { checkinNote = "\(row.name)：\(msg)" }
        return (ok, ok ? msg : friendlyError(msg))
    }

    // ---- 签到状态与开关的存储（d_<key>_lastCheckInDate / s_<key>_autoCheckin）----

    func lastCheckinDate(_ key: String) -> String? {
        UserDefaults.standard.string(forKey: "d_\(key)_lastCheckInDate")
    }

    /// 签到成功后由宿主统一落状态（jm 源里自己也写，同 key 同格式，重复写无害）
    private func markCheckedIn(_ key: String) {
        UserDefaults.standard.set(Self.checkinToday(), forKey: "d_\(key)_lastCheckInDate")
    }

    func checkedToday(_ key: String) -> Bool {
        lastCheckinDate(key) == Self.checkinToday()
    }

    /// 跟源里 toLocaleDateString('zh-CN') 同格式：2026/11/8（无前导零）
    static func checkinToday() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy/M/d"
        return f.string(from: Date())
    }

    func autoCheckinOn(_ key: String) -> Bool {
        let d = UserDefaults.standard
        // 旧版禁漫用的开关名是源内 setting（dailyCheckInTask），兼容读一次
        if key == "jm", d.object(forKey: "s_jm_autoCheckin") == nil {
            return (d.object(forKey: "s_jm_dailyCheckInTask") as? Bool) == true
        }
        return (d.object(forKey: "s_\(key)_autoCheckin") as? Bool) == true
    }

    func setAutoCheckin(_ key: String, _ on: Bool) {
        UserDefaults.standard.set(on, forKey: "s_\(key)_autoCheckin")
        if key == "jm" {
            // 新机制接管后，把源内那份关掉，避免签到跑两遍
            UserDefaults.standard.set(false, forKey: "s_jm_dailyCheckInTask")
        }
    }

    // ================================================================ 线路选择（多线路源）
    //
    // 宿主侧声明每源可选线路，写入源 setting（s_<key>_<settingKey>），
    // 源自己的 loadSetting 就能读到。direct = 该线路国内可直连（不用 VPN），
    // 直连的在列表里排前面并标绿——「有直连优先直连」。
    // 清单来源：禁漫的 API/图片源全部取自官方 App 的「线路与图源」设置页，
    // 每条都做过直连探测；没有任何一条是凭空写的。面板里还能现场测速复核。

    struct Line {
        let label: String
        let value: String
        let direct: Bool
        /// 非 nil = 选择面板里会对这个域名现场测速（显示延迟/不通）
        var probeHost: String? = nil

        init(_ label: String, _ value: String, _ direct: Bool, probeHost: String? = nil) {
            self.label = label
            self.value = value
            self.direct = direct
            self.probeHost = probeHost
        }
    }

    struct LineGroup: Identifiable {
        let settingKey: String
        let title: String
        let lines: [Line]
        /// false = 该源只有这条线路，只展示 + 测速，不提供选择
        var writable: Bool = true
        var id: String { settingKey }

        init(settingKey: String, title: String, writable: Bool = true, lines: [Line]) {
            self.settingKey = settingKey
            self.title = title
            self.lines = lines
            self.writable = writable
        }
    }

    /// 全部源的线路清单。每个源至少一行「API 域名」：
    /// 可改的（源里有对应 setting）能选；单域名的只展示 + 现场测速。
    /// 清单全部来自源代码与官方 App 设置页，没有编造的域名。
    static func lineGroups(for key: String) -> [LineGroup] {
        switch key {
        case "jm":
            // 清单对齐禁漫官方 App 的「线路与图源」设置页（全部直连可用）
            return [
                LineGroup(
                    settingKey: "apiDomain", title: "API 线路",
                    lines: [
                        Line("www.cdngwc.club", "1", true, probeHost: "www.cdngwc.club"),
                        Line("www.cdngwc.net", "2", true, probeHost: "www.cdngwc.net"),
                        Line("www.cdnutc.cc", "3", true, probeHost: "www.cdnutc.cc"),
                        Line("www.cdnbtd.cc", "4", true, probeHost: "www.cdnbtd.cc"),
                        Line("www.cdnhjk.net", "5", true, probeHost: "www.cdnhjk.net"),
                        Line("www.cdngwc.cc", "6", true, probeHost: "www.cdngwc.cc"),
                        Line("www.cdnutc.me", "7", true, probeHost: "www.cdnutc.me"),
                    ]
                ),
                LineGroup(
                    settingKey: "imgDomain", title: "图片源",
                    lines: [
                        Line("跟随服务配置", "follow", true),
                        Line("cdn-msp3.jmapiproxy1.cc", "cdn-msp3.jmapiproxy1.cc", true, probeHost: "cdn-msp3.jmapiproxy1.cc"),
                        Line("cdn-msp.jmapiproxy1.cc", "cdn-msp.jmapiproxy1.cc", true, probeHost: "cdn-msp.jmapiproxy1.cc"),
                        Line("cdn-msp.jmapiproxy2.cc", "cdn-msp.jmapiproxy2.cc", true, probeHost: "cdn-msp.jmapiproxy2.cc"),
                        Line("cdn-msp2.jmapiproxy2.cc", "cdn-msp2.jmapiproxy2.cc", true, probeHost: "cdn-msp2.jmapiproxy2.cc"),
                        Line("cdn-msp3.jmapiproxy2.cc", "cdn-msp3.jmapiproxy2.cc", true, probeHost: "cdn-msp3.jmapiproxy2.cc"),
                    ]
                ),
            ]
        case "copy_manga":
            return [
                LineGroup(
                    settingKey: "region", title: "CDN 线路",
                    lines: [
                        Line("大陆（可直连）", "1", true),
                        Line("海外（需代理）", "0", false),
                    ]
                ),
                // 实际请求域名：源里写死的节点都会被域名别名层统一改写到 copy5000.com
                LineGroup(
                    settingKey: "__api", title: "API 域名", writable: false,
                    lines: [
                        Line("www.copy5000.com（自动镜像）", "copy5000", true, probeHost: "www.copy5000.com"),
                    ]
                ),
            ]
        case "Komiic":
            return [
                LineGroup(
                    settingKey: "__api", title: "API / 图片域", writable: false,
                    lines: [
                        Line("komiic.cc（自动镜像，可直连）", "api", true, probeHost: "komiic.cc"),
                        Line("public.komiic.cc（封面）", "img", true, probeHost: "public.komiic.cc"),
                    ]
                )
            ]
        case "noyacg":
            return [
                LineGroup(
                    settingKey: "__api", title: "API / 图片域", writable: false,
                    lines: [
                        Line("noy1.top（可直连）", "api", true, probeHost: "noy1.top"),
                        Line("img.noy.asia（可直连）", "img", true, probeHost: "img.noy.asia"),
                    ]
                )
            ]
        case "picacg":
            return [
                LineGroup(
                    settingKey: "__api", title: "API 域名", writable: false,
                    lines: [
                        Line("picaapi.picacomic.com（需代理）", "api", false, probeHost: "picaapi.picacomic.com"),
                    ]
                )
            ]
        case "ehentai":
            return [
                LineGroup(
                    settingKey: "__api", title: "API 域名", writable: false,
                    lines: [
                        Line("e-hentai.org（需代理）", "api", false, probeHost: "e-hentai.org"),
                    ]
                )
            ]
        case "htmanga":
            return [
                LineGroup(
                    settingKey: "__api", title: "API 域名", writable: false,
                    lines: [
                        Line("www.wnacg.com（需代理）", "api", false, probeHost: "www.wnacg.com"),
                    ]
                )
            ]
        case "mh18":
            return [
                LineGroup(
                    settingKey: "__api", title: "API 域名", writable: false,
                    lines: [
                        Line("18mh.org（需代理）", "api", false, probeHost: "18mh.org"),
                    ]
                )
            ]
        default:
            return []
        }
    }

    /// 当前选中的线路值（nil = 跟源默认走）
    static func lineValue(_ key: String, _ setting: String) -> String? {
        UserDefaults.standard.object(forKey: "s_\(key)_\(setting)") as? String
    }

    static func setLineValue(_ key: String, _ setting: String, _ value: String?) {
        let d = UserDefaults.standard
        if let value {
            d.set(value, forKey: "s_\(key)_\(setting)")
        } else {
            d.removeObject(forKey: "s_\(key)_\(setting)")
        }
    }

    /// 线路在源里的显示名：可写的组显示当前选择；只读组直接显示域名
    static func lineLabel(_ key: String, _ group: LineGroup) -> String {
        guard group.writable else {
            return group.lines.first?.label ?? "—"
        }
        guard let v = lineValue(key, group.settingKey) else {
            // 给出「直连优先」的建议：没设置时提示默认值是什么
            let def = group.lines.first { $0.direct } ?? group.lines.first
            return "默认 · 建议选「\(def?.label ?? "-")」"
        }
        return group.lines.first { $0.value == v }?.label ?? v
    }

    // ---- 线路现场测速 ----
    //
    // 判定标准：「连通」= 服务器返回了任何 HTTP 状态码（含 403——禁漫的 CDN
    // 会拦不带签名的首页请求，但带签名的 API 是通的，node 全源探测验证过）。
    // 连不上（DNS 失败 / 超时）才会判 ✗。

    func probeLines(_ key: String, _ group: LineGroup) async -> [String: String] {
        await withTaskGroup(of: (String, String).self) { grp in
            for line in group.lines {
                guard let host = line.probeHost else { continue }
                grp.addTask {
                    let start = Date()
                    let ok = await Self.probeHost(host)
                    let ms = Int(Date().timeIntervalSince(start) * 1000)
                    return (line.label, ok ? "✓ \(ms)ms" : "✗ 不通")
                }
            }
            var out: [String: String] = [:]
            for await (k, v) in grp { out[k] = v }
            return out
        }
    }

    static func probeHost(_ host: String) async -> Bool {
        guard let url = URL(string: "https://\(host)/") else { return false }
        var req = URLRequest(url: url)
        req.timeoutInterval = 8
        req.setValue(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15",
            forHTTPHeaderField: "User-Agent"
        )
        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            if let http = resp as? HTTPURLResponse {
                return (100..<600).contains(http.statusCode)
            }
            return false
        } catch {
            return false
        }
    }
}

// ========================================================================== 带超时的取数

/// 单个源卡住不能拖住整页：到点就放弃它，先显示别的源的结果。
func exploreWithTimeout(key: String, seconds: Double = 15) async -> [String: Any]? {
    await withTaskGroup(of: [String: Any]?.self) { group in
        group.addTask {
            await SourceEngine.shared.explore(key: key)
        }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}

func searchWithTimeout(key: String, keyword: String, seconds: Double = 20) async -> [String: Any]? {
    await withTaskGroup(of: [String: Any]?.self) { group in
        group.addTask {
            await SourceEngine.shared.search(key: key, keyword: keyword, options: "[]", page: 1)
        }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}


/// 发现页「全部源」的结果缓存。
///
/// 引擎的 JS 是同步桥，多个源只能排队跑，一次全量要十几秒。切回发现页时如果 3 分钟内
/// 刚拉过同一批源、同一个关键词，就直接把上次的结果画出来。只放内存，空结果不缓存。
enum ExploreCache {
    struct Hit {
        let works: [Work]
        let ok: Int
        let fail: Int
        /// 10 分钟内拉的：界面直接用，连网络都不用打
        let fresh: Bool
    }

    private final class Entry {
        let works: [Work]
        let ok: Int
        let fail: Int
        let at: Date
        init(_ works: [Work], _ ok: Int, _ fail: Int, _ at: Date) {
            self.works = works; self.ok = ok; self.fail = fail; self.at = at
        }
    }

    private static let freshInterval: TimeInterval = 10 * 60
    private static let keepInterval: TimeInterval = 7 * 24 * 3600
    private static let maxEntries = 4
    private static let storeKey = "explore.cache.v2"

    private static var slots: [String: Entry] = load()

    static func get(_ key: String) -> Hit? {
        guard !key.isEmpty, let e = slots[key], !e.works.isEmpty else { return nil }
        let age = Date().timeIntervalSince(e.at)
        if age > keepInterval {
            slots.removeValue(forKey: key)
            save()
            return nil
        }
        return Hit(works: e.works, ok: e.ok, fail: e.fail, fresh: age <= freshInterval)
    }

    static func put(_ key: String, _ list: [Work], _ okCount: Int, _ failCount: Int) {
        guard !key.isEmpty, !list.isEmpty else { return }
        if slots.count >= maxEntries, slots[key] == nil, let first = slots.keys.first {
            slots.removeValue(forKey: first)
        }
        slots[key] = Entry(Array(list.prefix(240)), okCount, failCount, Date())
        save()
    }

    static func clear() {
        slots = [:]
        save()
    }

    // MARK: - 落盘
    // 发现页/首页的推荐要能跨启动直接显示，不能每次都重新问一遍源。

    private static func save() {
        var root: [String: Any] = [:]
        for (k, e) in slots {
            root[k] = [
                "at": e.at.timeIntervalSince1970,
                "ok": e.ok,
                "fail": e.fail,
                "works": e.works.map { workDict($0) },
            ]
        }
        UserDefaults.standard.set(root, forKey: storeKey)
    }

    private static func load() -> [String: Entry] {
        guard let root = UserDefaults.standard.dictionary(forKey: storeKey) else { return [:] }
        var out: [String: Entry] = [:]
        for (k, v) in root {
            guard let o = v as? [String: Any],
                  let ts = o["at"] as? Double,
                  let arr = o["works"] as? [[String: Any]] else { continue }
            let works = arr.compactMap { workFromDict($0) }
            if works.isEmpty { continue }
            out[k] = Entry(works, (o["ok"] as? Int) ?? 0, (o["fail"] as? Int) ?? 0,
                           Date(timeIntervalSince1970: ts))
        }
        return out
    }

    private static func workDict(_ w: Work) -> [String: Any] {
        [
            "n": w.norm,
            "t": w.title,
            "c": w.cover,
            "e": w.entries.map { e in
                ["sk": e.sourceKey, "sn": e.sourceName, "id": e.comicId, "t": e.title, "c": e.cover]
            },
        ]
    }

    private static func workFromDict(_ o: [String: Any]) -> Work? {
        guard let arr = o["e"] as? [[String: Any]], !arr.isEmpty else { return nil }
        var entries: [WorkEntry] = []
        for e in arr {
            guard let id = e["id"] as? String, !id.isEmpty else { continue }
            entries.append(WorkEntry(
                sourceKey: e["sk"] as? String ?? "",
                sourceName: e["sn"] as? String ?? "",
                comicId: id,
                title: e["t"] as? String ?? "",
                cover: e["c"] as? String ?? ""
            ))
        }
        guard !entries.isEmpty else { return nil }
        let title = o["t"] as? String ?? ""
        return Work(
            norm: (o["n"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? normalizeTitle(title),
            title: title,
            cover: o["c"] as? String ?? "",
            entries: entries
        )
    }
}
