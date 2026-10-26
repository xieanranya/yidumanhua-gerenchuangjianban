import SwiftUI

// =========================================================================== 源的界面状态

/// 一个源在界面上的状态：线路、登录、是否探通。
struct SourceRow: Identifiable, Equatable {
    let key: String
    let name: String
    let host: String
    let line: String
    let canLogin: Bool
    let logged: Bool
    let needLogin: Bool
    /// 探测确认所有通道都不通
    let offline: Bool
    /// 探测选中过（false = 还在检测中）
    let picked: Bool

    var id: String { key }

    var label: String {
        if offline { return "不通" }
        if !picked { return "检测中" }
        if line == "direct" { return "直连" }
        if line == "proxy" { return "需 VPN" }
        return ""
    }

    var tone: Tone {
        if offline { return .bad }
        if !picked { return .neutral }
        if line == "direct" { return .ok }
        if line == "proxy" { return .warn }
        return .neutral
    }

    static func build(sources: [[String: Any]], routes: [String: [String: Any]]) -> [SourceRow] {
        sources.compactMap { s in
            guard let key = s["key"] as? String, !key.isEmpty else { return nil }
            let r = routes[key]
            let line = r?["line"] as? String ?? ""
            // pick=false 说明 ci/host 还只是线路表里的默认值，不是探测结果。
            let picked = (r?["pick"] as? Bool) == true
            let offline = (r?["offline"] as? Bool) == true
            return SourceRow(
                key: key,
                name: s["name"] as? String ?? key,
                host: r?["host"] as? String ?? "",
                line: line,
                canLogin: (s["canLogin"] as? Bool) == true,
                logged: (s["logged"] as? Bool) == true,
                needLogin: (s["needLogin"] as? Bool) == true,
                offline: offline,
                picked: picked
            )
        }
    }
}

/// 按状态给一行中文小结，首页/我的页直接用。
func routeSummary(_ rows: [SourceRow]) -> String {
    if rows.isEmpty { return "还没有源数据" }
    let direct = rows.filter { $0.picked && !$0.offline && $0.line == "direct" }.count
    let proxy = rows.filter { $0.picked && !$0.offline && $0.line == "proxy" }.count
    let off = rows.filter { $0.offline }.count
    let probing = rows.filter { !$0.picked && !$0.offline }.count
    var parts = ["\(direct) 个直连"]
    if proxy > 0 { parts.append("\(proxy) 个需 VPN") }
    if off > 0 { parts.append("\(off) 个不通") }
    if probing > 0 { parts.append("\(probing) 个检测中") }
    return parts.joined(separator: " · ")
}

func routeTone(_ rows: [SourceRow]) -> Tone {
    if rows.isEmpty { return .neutral }
    let off = rows.filter { $0.offline }.count
    if off >= rows.count { return .bad }
    let ok = rows.filter { $0.picked && !$0.offline }.count
    if ok >= rows.count - off { return .ok }
    return .warn
}

/// 选来请求内容的源。
///
/// 还没探测过的源也要放进来（排最后）：新装第一次打开时线路表里本来就是直连默认值，
/// 这时候如果不给请求，发现页会空白，用户会以为整个 App 坏了。
func pickableSources(_ rows: [SourceRow]) -> [SourceRow] {
    rows.filter { !$0.offline }
        .sorted { a, b in rank(a) < rank(b) }
}

private func rank(_ r: SourceRow) -> Int {
    if r.picked && r.line == "direct" { return 0 }
    if r.picked && r.line == "proxy" { return 1 }
    if r.picked { return 2 }
    return 3
}

/// 源抛出来的网络错误太生硬（"HTTP 0"），换成用户能照做的说法。
func friendlyError(_ e: String) -> String {
    let low = e.lowercased()
    if low.contains("status code: 0") || e.contains("HTTP 0")
        || low.contains("请求失败: 0") || low.contains("timed out") || low.contains("timeout") {
        return "连不上这个源。回「我的 → 源管理」点「重新检测」，看它标「直连」还是「需 VPN」——标「需 VPN」就要先开代理。"
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
func groupWorks(_ comics: [ComicRow]) -> [Work] {
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

/// 源列表 + 线路状态。首页、发现、我的三个页面共用一份，避免每次切 Tab 都重探。
final class SourceHub: ObservableObject {
    @Published private(set) var rows: [SourceRow] = []
    @Published private(set) var loading = false
    @Published private(set) var probed = false

    static let shared = SourceHub()

    func load(force: Bool = false) async {
        if !rows.isEmpty && !force { return }
        if loading { return }
        await MainActor.run { loading = true }
        _ = await SourceEngine.shared.start()
        await refreshRows()
        await MainActor.run { loading = false }
        if force || !probed {
            await SourceEngine.shared.probeRoutes(force: force)
            await refreshRows()
            await MainActor.run { probed = true }
        }
    }

    func reprobe() async {
        await MainActor.run { loading = true }
        await SourceEngine.shared.probeRoutes(force: true)
        await refreshRows()
        await MainActor.run {
            loading = false
            probed = true
        }
    }

    /// 登录后刷新「已登录」标记
    func refreshRows() async {
        let srcs = await SourceEngine.shared.sources()
        let st = await SourceEngine.shared.routeStatus()
        var map: [String: [String: Any]] = [:]
        for r in st {
            if let k = r["key"] as? String { map[k] = r }
        }
        let built = SourceRow.build(sources: srcs, routes: map)
        await MainActor.run { rows = built }
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
