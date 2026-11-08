import SwiftUI

/// 首页（v3 沉浸式）：头图就是今日最热的封面大图，一直铺到状态栏后面，
/// 白色大标题压图；Hero 卡悬浮在头图边缘；下面三条带
/// （今日热门 / 本周上升最快 / 为你挑的）。「继续阅读」已删（与书架重复）。
struct HomeScreen: View {

    let onSearch: () -> Void
    let onShelf: () -> Void
    let onSources: () -> Void

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared

    @State private var hot: [Work] = []
    @State private var hotFrom = 0
    @State private var hotBusy = true

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    // 三条带各 6 本（数据不足时各段按实际数量显示，空段整段隐藏）
    private var bandHot: Array<Work>.SubSequence { hot.prefix(6) }
    private var bandRising: Array<Work>.SubSequence { hot.dropFirst(6).prefix(6) }
    private var bandPicked: Array<Work>.SubSequence { hot.dropFirst(12).prefix(6) }

    private var subline: String {
        let n = hub.enabledRows.count
        if hotBusy { return n > 0 ? "内容来自 \(n) 个漫画源" : "正在准备源…" }
        return n > 0 ? "\(n) 个源在用 · 已连上 \(hotFrom) 个" : "还没有可用的源"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // 沉浸头图：纯渐变（对齐网页版交互稿）——封面自带大字会跟标题打架，
                // 而且头图用封面会和下面的 Hero 卡重复显示同一本
                ImmersiveHeader(title: "画质漫画", subtitle: subline) {
                    ImmersivePoster(colors: c.dark ? PosterPalette.forest : PosterPalette.iris)
                }

                if hotBusy {
                    LoadingBox(height: 170)
                        .padding(.top, -26)
                } else if hot.isEmpty {
                    EmptyHint(
                        title: "还没有推荐",
                        message: "这些源这次都没给出内容，回头再刷一次试试。",
                        glyph: .discover
                    )
                    .padding(.top, -18)
                } else {
                    // Hero：今日最热第一本，全宽大卡悬浮在头图边缘
                    if let top = hot.first {
                        NavigationLink {
                            DetailView(work: top)
                        } label: {
                            HeroCard(
                                title: top.title,
                                cover: top.cover,
                                sourceText: (top.sourceCount > 1 ? top.sourceNames + " · " : "") + "更新至最新话",
                                badge: top.badge,
                                seed: top.norm.hashValue
                            )
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 18)
                        .padding(.top, -30)
                    }

                    band(bandHot.dropFirst(1), width: 118)

                    if !bandRising.isEmpty {
                        SectionHeader(title: "本周上升最快", more: "更多 ›", onMore: onSearch, inset: false)
                            .padding(.horizontal, 18)
                        band(bandRising, width: 96)
                    }

                    if !bandPicked.isEmpty {
                        SectionHeader(title: "为你挑的", more: "换一批", onMore: onSearch, inset: false)
                            .padding(.horizontal, 18)
                        LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                            ForEach(Array(bandPicked)) { work in
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
                }
            }
            .padding(.bottom, 26)
        }
        .background(c.bg.ignoresSafeArea())
        .coordinateSpace(name: "immersiveScroll")
        .ignoresSafeArea(edges: .top)
        .overlay(alignment: .topTrailing) {
            GlassCircleButton(glyph: .search) { onSearch() }
                .padding(.trailing, 16)
                .padding(.top, safeTop() + 8)
        }
        .immersiveStatusBar(ui)
        .task { await loadHot() }
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
        // 3 分钟内刚拉过同一批源：直接用上次的，切回首页不再等一遍
        if let cached = ExploreCache.get(sig) {
            await MainActor.run {
                hot = Array(cached.works.prefix(18))
                hotFrom = cached.ok
                hotBusy = false
            }
            return
        }
        var all: [ComicRow] = []
        var hit = 0
        var done = 0
        // 并发跑，谁先回来谁先上屏（原来是串行 for，三个源最慢要等 45 秒）
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
                    let snap = Array(groupWorks(all, sort: false).prefix(18))
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
                        hot = Array(finalWorks.prefix(18))
                        hotFrom = h
                        hotBusy = false
                        ExploreCache.put(sig, finalWorks, h, miss)
                    }
                }
            }
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
