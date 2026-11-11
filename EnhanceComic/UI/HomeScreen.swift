import SwiftUI

/// 首页（v4 简洁版）：品牌头 + 今日热门横排 + 各源排行榜。
/// 排行榜 = 源网站自己的排行（禁漫总/月/周/日、拷贝日/周/月/总、哔咔 24h/7天/30天、
/// Komiic 月/綜合……）：引擎 rankOptions 探测每个源的排行选项，**没有排行接口的源
/// 不出现在源 chips 里**（不硬凑）；数据全部从源实时拉取，空榜诚实显示。
struct HomeScreen: View {

    let onSearch: () -> Void
    let onShelf: () -> Void
    let onSources: () -> Void

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared

    // 今日热门（三个源并发拉，合并后取前 12 本横排）
    @State private var hot: [Work] = []
    @State private var hotFrom = 0
    @State private var hotBusy = true

    // 排行榜（源网站自己的排行）
    @State private var rankSrc = ""                              // 当前排行源
    @State private var rankOptIdx = 0                            // 当前周期下标
    @State private var rankOptions: [String: [[String: String]]] = [:]   // key → [{value,text}]
    @State private var rankComics: [String: [ComicRow]] = [:]    // "key|value" → 榜单
    @State private var rankBusy = false
    @State private var rankError: String?
    @State private var rankTick = 0
    @State private var appeared = false
    @State private var posterDetail: Work?

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    /// 有排行接口的源（rankOptions 非空）
    private var rankableRows: [SourceRow] {
        hub.enabledRows.filter { (rankOptions[$0.key] ?? []).isEmpty == false }
    }

    private var rankRow: SourceRow? {
        rankableRows.first { $0.key == rankSrc } ?? rankableRows.first
    }

    /// 当前源的周期选项
    private var rankOpts: [[String: String]] {
        rankOptions[rankSrc] ?? []
    }

    private var rankCacheKey: String {
        rankSrc + "|" + (rankOpts.indices.contains(rankOptIdx) ? rankOpts[rankOptIdx]["value"] ?? "" : "")
    }

    private var subline: String {
        let n = hub.enabledRows.count
        return n > 0 ? "\(n) 个源在用 · 已连上 \(hotBusy ? 0 : hotFrom) 个" : "正在准备源…"
    }

    /// 海报内容：优先最近阅读记录，其次今日热门第一本
    private var poster: (title: String, cover: String, pill: String, progress: String, button: String,
                         target: Work?) {
        if let rec = ui.history.first {
            return (
                title: rec.title.isEmpty ? "未命名" : rec.title,
                cover: rec.cover,
                pill: rec.epTitle.isEmpty ? "继续阅读" : "继续阅读 · \(rec.epTitle)",
                progress: "来源 \(rec.sourceName) · 已读 \(rec.epIndex + 1) 话",
                button: "继续阅读",
                target: rec.asWork
            )
        }
        if let first = hot.first {
            return (
                title: first.title,
                cover: first.cover,
                pill: "今日热门 · No.1",
                progress: first.sourceCount > 1 ? "来源 \(first.sourceNames)" : "来源 \(first.primary.sourceName)",
                button: "开始阅读",
                target: first
            )
        }
        return (
            title: "画质漫画",
            cover: "",
            pill: "今日热门",
            progress: "内容加载中…",
            button: "开始阅读",
            target: nil
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageTopBar(
                    title: "画质漫画",
                    trailing: AnyView(GlassCircleButton(glyph: .search) { onSearch() })
                )

                // ── 杂志封面海报：最近在读（有记录）或今日热门第一本 ──
                MagazinePoster(
                    title: poster.title,
                    cover: poster.cover,
                    pill: poster.pill,
                    progressLine: poster.progress,
                    buttonLabel: poster.button,
                    onButton: { if let t = poster.target { posterDetail = t } },
                    onCover: { if let t = poster.target { posterDetail = t } }
                )
                .padding(.horizontal, 14)
                .padding(.top, 4)
                .padding(.bottom, 6)

                // ── 今日热门：3 列网格直接铺开（v5 极简，无大卡无横排）──
                SectionHeader(
                    title: "今日热门",
                    more: hotBusy ? nil : (hotFrom > 0 ? "\(hotFrom) 个源" : nil),
                    onMore: hotBusy ? nil : onSearch,
                    inset: false
                )
                .padding(.horizontal, 18)

                if hotBusy {
                    LoadingBox(height: 190)
                } else if hot.isEmpty {
                    Text("这些源这次都没给出内容，回头再刷一次试试。")
                        .font(.system(size: 11.5))
                        .foregroundColor(c.fg3)
                        .padding(.horizontal, 18)
                        .padding(.top, 8)
                } else {
                    // 6 本（2 行）：给排行榜留出首屏位置
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
                        ForEach(Array(hot.prefix(6))) { work in
                            NavigationLink {
                                DetailView(work: work)
                            } label: {
                                ComicCell(
                                    title: work.title,
                                    cover: work.cover,
                                    sub: work.sourceCount > 1 ? work.sourceNames : nil,
                                    badge: work.badge,
                                    seed: work.norm.hashValue
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 18)
                }

                // ── 排行榜（源网站自己的排行）──
                SectionHeader(
                    title: "排行榜",
                    more: rankRow != nil
                        ? "\(rankRow!.name) · \(rankOpts.indices.contains(rankOptIdx) ? rankOpts[rankOptIdx]["text"] ?? "" : "")"
                        : nil,
                    inset: false
                )
                .padding(.horizontal, 18)

                if rankSrc.isEmpty {
                    LoadingBox(height: 60)
                } else {
                    // 源 chips：只有真有排行接口的源出现
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 7) {
                            ForEach(rankableRows) { row in
                                Chip(text: row.name, selected: rankSrc == row.key) {
                                    if rankSrc != row.key {
                                        rankSrc = row.key
                                        rankOptIdx = 0
                                        rankTick += 1
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 2)
                    }
                    .frame(height: 38)

                    // 周期 chips：来自源自己的排行选项（總排行/月排行/日排行…）
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 7) {
                            ForEach(Array(rankOpts.enumerated()), id: \.offset) { i, opt in
                                Chip(text: opt["text"] ?? "", selected: rankOptIdx == i) {
                                    if rankOptIdx != i {
                                        rankOptIdx = i
                                        rankTick += 1
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 2)
                    }
                    .frame(height: 38)
                    .padding(.bottom, 4)
                }

                rankContent
            }
            .padding(.bottom, 26)
            // 入场：整页轻微上移淡入（低帧率设备也流畅）
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 14)
            .onAppear {
                withAnimation(.easeOut(duration: 0.35)) { appeared = true }
            }
        }
        .background(c.bg.ignoresSafeArea())
        // 海报封面/按钮 → 详情（多源聚合，里面再继续阅读）
        .navigationDestination(
            isPresented: Binding(
                get: { posterDetail != nil },
                set: { if !$0 { posterDetail = nil } }
            )
        ) {
            if let w = posterDetail { DetailView(work: w) }
        }
        // 今日热门先跑完（引擎队列串行，不能抢），再探测排行选项、再拉榜单
        .task { await loadHot() }
        .task(id: "\(rankTick)|\(hotBusy)|\(hub.enabledRows.count)") {
            if hotBusy { return }        // task 会因 hotBusy 变化自动重启
            await loadRankOptions()
            await loadRank()
        }
    }

    // ── 排行榜内容 ──
    @ViewBuilder
    private var rankContent: some View {
        if rankBusy {
            LoadingBox(height: 180)
        } else if let rankError {
            Text(rankError)
                .font(.system(size: 11.5))
                .foregroundColor(c.fg3)
                .padding(.horizontal, 18)
                .padding(.top, 8)
        } else {
            let comics = rankComics[rankCacheKey] ?? []
            if comics.isEmpty {
                Text("这个榜现在没有数据（源站还没更新），换一个周期或源试试。")
                    .font(.system(size: 11.5))
                    .foregroundColor(c.fg3)
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
            } else {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
                    ForEach(Array(comics.enumerated()), id: \.element.id) { i, comic in
                        NavigationLink {
                            DetailView(work: comic.asWork)
                        } label: {
                            RankCell(
                                index: i,
                                title: comic.title,
                                cover: comic.cover,
                                seed: comic.comicId.hashValue,
                                sub: comic.sub.isEmpty ? nil : comic.sub
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 18)
            }
        }
    }


    // ══════════════════════ 数据 ══════════════════════

    private func loadHot() async {
        await hub.load()
        let targets = Array(hub.enabledRows.prefix(3))
        if targets.isEmpty {
            hotBusy = false
            return
        }
        let sig = "home|" + targets.map { $0.key }.joined(separator: ",")
        if let cached = ExploreCache.get(sig) {
            await MainActor.run {
                hot = Array(cached.works.prefix(12))
                hotFrom = cached.ok
                hotBusy = false
            }
            return
        }
        var all: [ComicRow] = []
        var hit = 0
        var done = 0
        await withTaskGroup(of: [ComicRow]?.self) { group in
            for s in targets {
                group.addTask {
                    guard let r = await exploreWithTimeout(key: s.key, seconds: 12),
                          (r["ok"] as? Bool) == true,
                          let d = r["data"] as? [String: Any] else { return nil }
                    let comics = ExploreSection.parse(d, sourceKey: s.key, sourceName: s.name)
                        .flatMap { $0.comics }
                    return comics.isEmpty ? nil : comics
                }
            }
            for await result in group {
                done += 1
                if let list = result {
                    hit += 1
                    all.append(contentsOf: list)
                    let snap = Array(groupWorks(all, sort: false).prefix(12))
                    let h = hit
                    await MainActor.run {
                        hot = snap
                        hotFrom = h
                        if hotBusy { hotBusy = false }
                    }
                }
                if done == targets.count {
                    let finalWorks = groupWorks(all)
                    let h = hit
                    let miss = targets.count - hit
                    await MainActor.run {
                        hot = Array(finalWorks.prefix(12))
                        hotFrom = h
                        hotBusy = false
                        ExploreCache.put(sig, finalWorks, h, miss)
                    }
                }
            }
        }
    }

    /// 探测每个源的排行选项（同步调用，无网络，串行极快）。
    /// 默认选中禁漫（有四榜）。
    private func loadRankOptions() async {
        await hub.load()
        var opts: [String: [[String: String]]] = [:]
        for row in hub.enabledRows {
            let o = await SourceEngine.shared.rankOptions(key: row.key)
            // 引擎返回 [{value,text}]，元素统一转成纯字符串字典
            let cleaned: [[String: String]] = o.map { dict in
                dict.compactMapValues { $0 as? String }
            }
            if !cleaned.isEmpty { opts[row.key] = cleaned }
        }
        await MainActor.run {
            rankOptions = opts
            if rankSrc.isEmpty {
                rankSrc = opts.first { $0.key == "jm" }?.key
                    ?? hub.enabledRows.first { (opts[$0.key] ?? []).isEmpty == false }?.key ?? ""
            }
        }
    }

    /// 拉当前源当前周期的榜单
    private func loadRank() async {
        let key = rankCacheKey
        guard rankOpts.indices.contains(rankOptIdx), !rankSrc.isEmpty,
              let option = rankOpts[rankOptIdx]["value"] else { return }
        if rankComics[key] != nil { return }   // 已拉过
        rankBusy = true
        rankError = nil
        let r = await SourceEngine.shared.rank(key: rankSrc, option: option, page: 1)
        let ok = (r["ok"] as? Bool) == true
        let comics = parseRankComics(r, sourceKey: rankSrc,
                                     sourceName: hub.enabledRows.first { $0.key == rankSrc }?.name ?? rankSrc)
        await MainActor.run {
            rankComics[key] = comics
            rankBusy = false
            if !ok {
                rankError = (r["error"] as? String) ?? "拉取失败"
            }
        }
    }

    /// rank 返回的 comics → ComicRow（引擎已解析成 {id,title,cover,subTitle}）。
    /// 注意引擎任务事件是 {id, ok, data:{...}} 包装——漫画列表在 data.comics，
    /// 之前直接读顶层 comics 永远是 nil，排行榜因此一直是空态（v1.9.71 起的老 bug）。
    private func parseRankComics(_ r: [String: Any], sourceKey: String, sourceName: String) -> [ComicRow] {
        guard ok(r), let d = r["data"] as? [String: Any],
              let arr = d["comics"] as? [[String: Any]] else { return [] }
        return arr.compactMap { o -> ComicRow? in
            guard let id = o["id"] as? String, !id.isEmpty else { return nil }
            return ComicRow(
                sourceKey: sourceKey,
                sourceName: sourceName,
                comicId: id,
                title: o["title"] as? String ?? "",
                cover: o["cover"] as? String ?? "",
                sub: o["subTitle"] as? String ?? o["subtitle"] as? String ?? ""
            )
        }
    }
}

private func ok(_ r: [String: Any]) -> Bool { (r["ok"] as? Bool) == true }
