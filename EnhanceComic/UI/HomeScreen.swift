import SwiftUI

/// 首页（v4 简洁版）：品牌头 + 今日热门横排 + 各源单独排行榜。
/// 排行榜：源 chips（默认禁漫）+ 周期 chips（今日/本周/月/总），
/// 数据 = 该源 explore 分区里按关键字匹配的排行分区（匹配不到显示空态，不造假）。
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

    // 排行榜
    @State private var rankSrc = ""                 // 当前排行源（默认 jm）
    @State private var rankTab = 0                  // 0 今日 / 1 本周 / 2 月 / 3 总
    @State private var rankSections: [String: [ExploreSection]] = [:]
    @State private var rankBusy = false
    @State private var rankError: String?
    @State private var rankTick = 0

    static let rankTabs = ["今日", "本周", "月", "总"]

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    private var rankRow: SourceRow? {
        hub.enabledRows.first { $0.key == rankSrc } ?? hub.enabledRows.first
    }

    private var subline: String {
        let n = hub.enabledRows.count
        return n > 0 ? "\(n) 个源在用 · 已连上 \(hotBusy ? 0 : hotFrom) 个" : "正在准备源…"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BrandHeader(title: "画质漫画", subtitle: subline) {
                    GlassCircleButton(glyph: .search) { onSearch() }
                }

                // ── 今日热门 ──
                SectionHeader(
                    title: "今日热门",
                    more: hotBusy ? nil : (hotFrom > 0 ? "\(hotFrom) 个源" : nil),
                    onMore: hotBusy ? nil : onSearch,
                    inset: false
                )
                .padding(.horizontal, 18)

                if hotBusy {
                    LoadingBox(height: 150)
                } else if hot.isEmpty {
                    Text("这些源这次都没给出内容，回头再刷一次试试。")
                        .font(.system(size: 11.5))
                        .foregroundColor(c.fg3)
                        .padding(.horizontal, 18)
                        .padding(.top, 8)
                } else {
                    band(hot.prefix(6), width: 118)
                }

                // ── 排行榜 ──
                SectionHeader(
                    title: "排行榜",
                    more: rankRow != nil ? "\(rankRow!.name) · \(Self.rankTabs[rankTab])" : nil,
                    inset: false
                )
                .padding(.horizontal, 18)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 7) {
                        ForEach(hub.enabledRows) { row in
                            Chip(text: row.name, selected: rankSrc == row.key) {
                                if rankSrc != row.key {
                                    rankSrc = row.key
                                    rankTick += 1
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 2)
                }
                .frame(height: 38)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 7) {
                        ForEach(Self.rankTabs.indices, id: \.self) { i in
                            Chip(text: Self.rankTabs[i], selected: rankTab == i) {
                                if rankTab != i {
                                    rankTab = i
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

                rankContent
            }
            .padding(.bottom, 26)
        }
        .background(c.bg.ignoresSafeArea())
        .onAppear {
            if rankSrc.isEmpty {
                // 用户要求：默认禁漫的排行
                rankSrc = hub.enabledRows.first { $0.key == "jm" }?.key
                    ?? hub.enabledRows.first?.key ?? ""
            }
        }
        .task { await loadHot() }
        .task(id: "\(rankSrc)|\(rankTab)|\(rankTick)|\(hub.enabledRows.count)") { await loadRank() }
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
        } else if let matched = matchedSection {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
                ForEach(Array(matched.comics.enumerated()), id: \.element.id) { i, comic in
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
        } else {
            Text("这个源没有「\(Self.rankTabs[rankTab])」的排行数据，换一个周期或源试试。")
                .font(.system(size: 11.5))
                .foregroundColor(c.fg3)
                .padding(.horizontal, 18)
                .padding(.top, 8)
        }
    }

    /// 当前周期匹配到的排行分区。找不到匹配时「总」兜底用第一个分区。
    private var matchedSection: ExploreSection? {
        guard let secs = rankSections[rankSrc], !secs.isEmpty else { return nil }
        let keys: [[String]] = [
            ["日", "今日", "daily"],
            ["週", "周", "week"],
            ["月", "month"],
            ["總", "总", "排行", "rank", "人气", "人氣", "hot"],
        ]
        let pats = keys[min(rankTab, keys.count - 1)]
        for sec in secs {
            let t = sec.title.lowercased()
            if pats.contains(where: { t.contains($0.lowercased()) }) { return sec }
        }
        return rankTab == 3 ? secs.first : nil
    }

    /// 横向滚动的封面条带
    @ViewBuilder
    private func band(_ works: Array<Work>.SubSequence, width: CGFloat) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(Array(works)) { work in
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
                        .frame(width: width)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 2)
        }
    }

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

    private func loadRank() async {
        guard let row = rankRow else {
            rankError = "没有可用的源。"
            return
        }
        if rankSections[row.key] != nil { return }   // 已拉过，chips 切换即时显示
        rankBusy = true
        rankError = nil
        let r = await exploreWithTimeout(key: row.key, seconds: 15)
        guard let r, (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            await MainActor.run {
                rankBusy = false
                rankError = "这个源没返回内容（可能需要代理或登录）。"
            }
            return
        }
        let secs = ExploreSection.parse(d, sourceKey: row.key, sourceName: row.name)
        await MainActor.run {
            rankSections[row.key] = secs
            rankBusy = false
        }
    }
}

extension OnlineRecord {
    /// 从阅读记录跳详情时要的 Work（只有当前这一个源）
    var asWork: Work {
        let entry = WorkEntry(
            sourceKey: sourceKey,
            sourceName: sourceName,
            comicId: comicId,
            title: sourceTitle.isEmpty ? title : sourceTitle,
            cover: cover
        )
        return Work(
            norm: normalizeTitle(entry.title),
            title: entry.title,
            cover: cover,
            entries: [entry]
        )
    }
}
