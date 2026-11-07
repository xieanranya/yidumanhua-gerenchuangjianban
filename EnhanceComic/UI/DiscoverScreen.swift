import SwiftUI

/// 发现（v3 沉浸式）：头图紫渐变铺到状态栏后；源列表升级为毛玻璃悬浮卡；
/// 点源进单源页（同样沉浸头图 + 左上返回圆钮）。
struct DiscoverScreen: View {

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared

    @State private var keyword = ""
    @State private var query = ""
    @State private var picked = ""          // "" = 全部源
    @State private var works: [Work] = []
    @State private var sections: [ExploreSection] = []
    @State private var busy = true
    @State private var error: String?
    @State private var okSources = 0
    @State private var failedSources = 0
    @State private var reloadTick = 0
    // 点「12 本 ›」打开的分区全部列表
    @State private var openSection: ExploreSection?
    // 搜索框默认收起（右上圆钮展开）；聚合完整列表页开关
    @State private var showSearch = false
    @State private var showAggAll = false

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    var body: some View {
        if let sec = openSection {
            SectionListView(
                title: sec.title.isEmpty ? "发现" : sec.title,
                comics: sec.comics,
                onBack: { openSection = nil }
            )
        } else if showAggAll {
            aggAllView
        } else if !picked.isEmpty {
            sourcePage
        } else {
            discoverRoot
        }
    }

    // ══════════════════════ 根页（沉浸头图 + 源卡） ══════════════════════

    private var discoverRoot: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ImmersiveHeader(
                    title: "发现",
                    subtitle: "按源浏览 · \(hub.enabledRows.count) 个源可用"
                ) {
                    ImmersivePoster(colors: PosterPalette.violet)
                }

                if showSearch {
                    searchBar
                        .padding(.horizontal, 18)
                        .padding(.top, -34)
                        .padding(.bottom, 4)
                }

                // 毛玻璃源悬浮卡
                VStack(spacing: 9) {
                    ForEach(Array(hub.enabledRows.enumerated()), id: \.element.key) { idx, row in
                        sourceCard(row, idx)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, showSearch ? 10 : -34)

                SectionHeader(
                    title: "全部源聚合",
                    more: works.isEmpty ? nil : "查看全部 ›",
                    onMore: works.isEmpty ? nil : { showAggAll = true },
                    inset: false
                )
                .padding(.horizontal, 18)

                if busy {
                    LoadingBox(height: 120)
                } else if works.isEmpty {
                    if error == nil {
                        Text("源还没有给出内容。")
                            .font(.system(size: 11.5))
                            .foregroundColor(c.fg3)
                            .padding(.horizontal, 18)
                            .padding(.bottom, 20)
                    } else {
                        Text(error!)
                            .font(.system(size: 10.5))
                            .foregroundColor(c.fg3)
                            .lineLimit(2)
                            .padding(.horizontal, 18)
                            .padding(.bottom, 20)
                    }
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                        ForEach(works.prefix(6)) { work in
                            NavigationLink {
                                DetailView(work: work)
                            } label: {
                                ComicCell(
                                    title: work.title,
                                    cover: work.cover,
                                    sub: work.sourceCount > 1 ? work.sourceNames : work.primary.sourceName,
                                    badge: work.badge,
                                    seed: work.norm.hashValue
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 26)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .coordinateSpace(name: "immersiveScroll")
        .ignoresSafeArea(edges: .top)
        .overlay(alignment: .topTrailing) {
            GlassCircleButton(glyph: .search) {
                withAnimation(.easeOut(duration: 0.2)) { showSearch.toggle() }
            }
            .padding(.trailing, 16)
            .padding(.top, safeTop() + 8)
        }
        .immersiveStatusBar(ui)
        .task(id: "\(picked)|\(query)|\(reloadTick)") { await load() }
    }

    /// 毛玻璃源悬浮卡（图标渐变 + 名称 + 登录状态 + 绿点）
    private func sourceCard(_ row: SourceRow, _ idx: Int) -> some View {
        Button {
            picked = row.key
            reloadTick += 1
        } label: {
            HStack(spacing: 12) {
                Text(String(row.name.prefix(1)))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 40, height: 40)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: PosterPalette.bySeed(idx),
                                    startPoint: .topLeading, endPoint: .bottomTrailing
                                )
                            )
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(c.fg)
                    Text(row.logged ? "已登录"
                         : (row.needLogin ? "需要登录后浏览"
                            : (row.canLogin ? "支持账号登录" : "公开内容")))
                        .font(.system(size: 10))
                        .foregroundColor(c.fg3)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if row.logged {
                    Circle().fill(c.ok).frame(width: 6, height: 6)
                }
                GlyphView(glyph: .right, tint: c.fg3, size: 13, strokeWidth: 1.6)
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 11)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(c.card.opacity(0.55))
            )
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(c.border.opacity(0.8), lineWidth: 0.8)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // ══════════════════════ 单源页（沉浸头图 + 返回圆钮） ══════════════════════

    private var sourcePage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ImmersiveHeader(
                    title: pickedName,
                    subtitle: pickedSub,
                    height: 250
                ) {
                    ImmersivePoster(colors: PosterPalette.bySeed(pickedSeed))
                }

                Group {
                    if busy {
                        LoadingBox(height: 200)
                            .padding(.top, -20)
                    } else if let error {
                        VStack(spacing: 10) {
                            EmptyHint(title: "没有找到内容", message: error, glyph: .search)
                            MiniButton(text: "重新加载", accent: true) { reloadTick += 1 }
                                .padding(.bottom, 30)
                        }
                        .padding(.top, -16)
                    } else {
                        singleSourceGrid
                            .padding(.top, -18)
                    }
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .coordinateSpace(name: "immersiveScroll")
        .ignoresSafeArea(edges: .top)
        .overlay(alignment: .topLeading) {
            GlassCircleButton(glyph: .back) { picked = "" }
                .padding(.leading, 16)
                .padding(.top, safeTop() + 8)
        }
        .immersiveStatusBar(ui)
        .task(id: "s|\(picked)|\(query)|\(reloadTick)") { await load() }
    }

    private var pickedName: String {
        hub.enabledRows.first { $0.key == picked }?.name ?? picked
    }

    private var pickedSub: String {
        guard let row = hub.enabledRows.first(where: { $0.key == picked }) else { return "源浏览" }
        if row.logged { return "已登录 · 源首页" }
        if row.needLogin { return "不登录看不了这个源" }
        return row.canLogin ? "支持账号登录" : "公开内容"
    }

    private var pickedSeed: Int {
        guard let idx = hub.enabledRows.firstIndex(where: { $0.key == picked }) else { return 0 }
        return idx
    }

    // ══════════════════════ 聚合完整列表（普通二级页） ══════════════════════

    private var aggAllView: some View {
        VStack(spacing: 0) {
            navbarBack("全部源聚合") { showAggAll = false }
            ScrollView {
                if busy { LoadingBox(height: 220).padding(.top, 30) }
                else if works.isEmpty { EmptyHint(title: "没有取到内容", message: error ?? "回头再试一次。", glyph: .discover) }
                else { aggregateGrid }
            }
            .background(c.bg)
        }
        .immersiveStatusBar(ui, light: false)
    }

    /// 源浏览页的返回导航条（「‹ 发现」）
    private func navbarBack(_ title: String, onBack: @escaping () -> Void) -> some View {
        HStack(spacing: 7) {
            Button(action: onBack) {
                HStack(spacing: 4) {
                    Text("‹").font(.system(size: 18, weight: .semibold))
                    Text(title).font(.system(size: 14, weight: .semibold))
                }
                .foregroundColor(c.accent)
            }
            .buttonStyle(.plain)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 6)
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            GlyphView(glyph: .search, tint: c.fg3, size: 15, strokeWidth: 1.6)
            TextField("搜漫画名、作者、题材", text: $keyword)
                .font(.system(size: 13))
                .foregroundColor(c.fg)
                .submitLabel(.search)
                .onSubmit { submit() }
                .accentColor(c.accent)
            if !keyword.isEmpty {
                Button {
                    keyword = ""
                    submit()
                } label: {
                    GlyphView(glyph: .more, tint: c.fg3, size: 14, strokeWidth: 1.6)
                        .rotationEffect(.degrees(90))
                        .frame(width: 22, height: 22)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 13)
        .frame(height: 40)
        .background(c.card2)
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .stroke(c.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private var aggregateGrid: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
            ForEach(works) { work in
                NavigationLink {
                    DetailView(work: work)
                } label: {
                    ComicCell(
                        title: work.title,
                        cover: work.cover,
                        sub: work.sourceCount > 1 ? work.sourceNames : work.primary.sourceName,
                        badge: work.badge,
                        seed: work.norm.hashValue
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 12)
        .padding(.bottom, 26)
    }

    private var singleSourceGrid: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(sections) { sec in
                if !sec.title.isEmpty {
                    SectionHeader(
                        title: sec.title,
                        more: "\(sec.comics.count) 本",
                        onMore: { openSection = sec },
                        inset: false
                    )
                    .padding(.horizontal, 18)
                }
                LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                    ForEach(sec.comics) { comic in
                        NavigationLink {
                            DetailView(work: comic.asWork)
                        } label: {
                            ComicCell(
                                title: comic.title,
                                cover: comic.cover,
                                sub: comic.sub.isEmpty ? nil : comic.sub,
                                seed: comic.comicId.hashValue
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 8)
                .padding(.bottom, 12)
            }
        }
        .padding(.top, 4)
        .padding(.bottom, 26)
    }

    private func submit() {
        query = keyword.trimmingCharacters(in: .whitespaces)
        reloadTick += 1
    }

    private func load() async {
        await MainActor.run {
            busy = true
            error = nil
            okSources = 0
            failedSources = 0
        }
        await hub.load()
        let rows = hub.enabledRows
        // 用「已提交」的关键词，别用输入框里的实时文本：切源时不该跟着未提交的字搜
        let q = query

        if picked.isEmpty {
            let targets = Array(rows.prefix(8))
            if targets.isEmpty {
                await MainActor.run {
                    works = []
                    sections = []
                    busy = false
                    error = "你把所有源都关掉了。去「我的 → 源管理」至少打开一个。"
                }
                return
            }
            let cacheSig = "v|" + q + "|" + targets.map { $0.key }.joined(separator: ",")
            // 缓存两档：10 分钟内直接当结果用；更早的先画出来，再后台静默更新。
            // 以前只有 3 分钟内存缓存，过了就整页重拉，每次点「发现」都要等一轮。
            var stale = false
            if let hit = ExploreCache.get(cacheSig) {
                let fresh = hit.fresh
                await MainActor.run {
                    works = hit.works
                    sections = []
                    okSources = hit.ok
                    failedSources = hit.fail
                    busy = false
                    error = nil
                }
                if fresh { return }
                stale = true
            }
            var all: [ComicRow] = []
            var ok = 0
            var bad = 0
            await withTaskGroup(of: [ComicRow]?.self) { group in
                for s in targets {
                    group.addTask {
                        if q.isEmpty {
                            guard let r = await exploreWithTimeout(key: s.key, seconds: 15),
                                  (r["ok"] as? Bool) == true,
                                  let d = r["data"] as? [String: Any] else { return nil }
                            let secs = ExploreSection.parse(d, sourceKey: s.key, sourceName: s.name)
                            let comics = secs.flatMap { $0.comics }
                            return comics.isEmpty ? nil : comics
                        } else {
                            guard let r = await searchWithTimeout(key: s.key, keyword: q, seconds: 15),
                                  (r["ok"] as? Bool) == true,
                                  let d = r["data"] as? [String: Any] else { return nil }
                            let comics = parseSearchComics(d, sourceKey: s.key, sourceName: s.name)
                            return comics.isEmpty ? nil : comics
                        }
                    }
                }
                // 哪个源先回来就先上屏，不等最慢的那个
                for await result in group {
                    if let list = result {
                        all.append(contentsOf: list)
                        ok += 1
                        let snapshot = groupWorks(all, sort: false)
                        let okNow = ok
                        let badNow = bad
                        // 屏幕上是上次的完整结果时，刷新过程中别把它缩成一两个源
                        if !stale {
                            await MainActor.run {
                                works = snapshot
                                sections = []
                                okSources = okNow
                                failedSources = badNow
                                if busy { busy = false }
                            }
                        }
                    } else {
                        bad += 1
                    }
                }
            }
            let grouped = groupWorks(all)
            let okFinal = ok
            let badFinal = bad
            await MainActor.run {
                works = grouped
                sections = []
                okSources = okFinal
                failedSources = badFinal
                busy = false
                ExploreCache.put(cacheSig, grouped, okFinal, badFinal)
                if grouped.isEmpty {
                    error = q.isEmpty
                        ? "这些源这次都没返回内容。可能是网站临时抽风，回头再刷一次。"
                        : "换个关键词试试，或者只选一个源搜。"
                }
            }
        } else {
            guard let row = rows.first(where: { $0.key == picked }) else {
                await MainActor.run {
                    busy = false
                    error = "这个源不在列表里了。"
                }
                return
            }
            var secs: [ExploreSection] = []
            var fail: String?
            if q.isEmpty {
                if let r = await exploreWithTimeout(key: row.key, seconds: 18),
                   (r["ok"] as? Bool) == true,
                   let d = r["data"] as? [String: Any] {
                    secs = ExploreSection.parse(d, sourceKey: row.key, sourceName: row.name)
                } else {
                    fail = "这个源没返回首页内容。"
                }
            } else {
                if let r = await searchWithTimeout(key: row.key, keyword: q, seconds: 18),
                   (r["ok"] as? Bool) == true,
                   let d = r["data"] as? [String: Any] {
                    let comics = parseSearchComics(d, sourceKey: row.key, sourceName: row.name)
                    if !comics.isEmpty {
                        secs = [ExploreSection(title: "搜索结果", comics: comics)]
                    }
                } else {
                    fail = "搜索失败。"
                }
            }
            let finalSecs = secs
            let finalFail = fail
            await MainActor.run {
                sections = finalSecs
                works = []
                okSources = finalSecs.isEmpty ? 0 : 1
                failedSources = finalSecs.isEmpty ? 1 : 0
                busy = false
                if finalSecs.isEmpty {
                    error = friendlyError(finalFail ?? "这个源没有内容。可能需要先登录，或者换个关键词。")
                }
            }
        }
    }
}

extension ComicRow {
    var asWork: Work {
        let entry = WorkEntry(
            sourceKey: sourceKey,
            sourceName: sourceName,
            comicId: comicId,
            title: title,
            cover: cover
        )
        return Work(norm: normalizeTitle(title), title: title, cover: cover, entries: [entry])
    }
}
