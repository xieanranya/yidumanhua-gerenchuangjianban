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
                needLogin: (s["needLogin"] as? Bool) == true
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
    }

    private static let ttl: TimeInterval = 3 * 60
    private static var sig = ""
    private static var at: Date = .distantPast
    private static var works: [Work] = []
    private static var ok = 0
    private static var fail = 0

    static func get(_ key: String) -> Hit? {
        guard !key.isEmpty, key == sig, !works.isEmpty else { return nil }
        guard Date().timeIntervalSince(at) <= ttl else { return nil }
        return Hit(works: works, ok: ok, fail: fail)
    }

    static func put(_ key: String, _ list: [Work], _ okCount: Int, _ failCount: Int) {
        guard !key.isEmpty, !list.isEmpty else { return }
        sig = key
        at = Date()
        works = list
        ok = okCount
        fail = failCount
    }

    static func clear() {
        sig = ""
        at = .distantPast
        works = []
        ok = 0
        fail = 0
    }
}
