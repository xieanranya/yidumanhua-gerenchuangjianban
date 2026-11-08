import SwiftUI

/// 发现（v3.1 · 单页直显）：打开就是内容，不要「先选源再看」的两级结构。
///
/// 布局对齐 JmBox：顶部源 chips（默认第一个源=禁漫）→ 分区 chips（该源的
/// explore 分区）→ 3 列大封面网格。点源 chips 切换其他源，点「聚合」看全部源
/// 的合并结果。搜索在右上圆钮里。
struct DiscoverScreen: View {

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared

    @State private var keyword = ""
    @State private var query = ""
    @State private var picked = ""          // 源 key；"agg" = 全部源聚合；"" = 还没选（进页面自动选）
    @State private var sectionIdx = 0       // 当前分区（单源模式）
    @State private var works: [Work] = []
    @State private var sections: [ExploreSection] = []
    @State private var busy = true
    @State private var error: String?
    @State private var reloadTick = 0
    @State private var showSearch = false

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    private var subline: String {
        let name = pickedName
        return name.isEmpty ? "\(hub.enabledRows.count) 个源可用" : "\(name) · \(hub.enabledRows.count) 个源可切换"
    }

    private var pickedName: String {
        if picked == "agg" { return "全部源聚合" }
        return hub.enabledRows.first { $0.key == picked }?.name ?? ""
    }

    var body: some View {
        discoverRoot
    }

    // ══════════════════════ 单页直显 ══════════════════════

    private var discoverRoot: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BrandHeader(title: "发现", subtitle: subline) {
                    GlassCircleButton(glyph: .search) {
                        withAnimation(.easeOut(duration: 0.2)) { showSearch.toggle() }
                    }
                }

                if showSearch {
                    searchBar
                        .padding(.horizontal, 18)
                        .padding(.top, 8)
                        .padding(.bottom, 4)
                }

                // 源 chips：默认第一个源（禁漫），最后一个「聚合」
                // 嵌套在纵向滚动里的横向滚动必须给显式高度，否则手势会被外层吞掉（滑不动）
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 7) {
                        ForEach(hub.enabledRows) { row in
                            Chip(text: row.name, selected: picked == row.key) {
                                if picked != row.key {
                                    picked = row.key
                                    sectionIdx = 0
                                    reloadTick += 1
                                }
                            }
                        }
                        Chip(text: "聚合", selected: picked == "agg") {
                            if picked != "agg" {
                                picked = "agg"
                                reloadTick += 1
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 2)
                }
                .frame(height: 38)
                .padding(.top, showSearch ? 10 : 6)
                .padding(.bottom, 6)

                if busy {
                    LoadingBox(height: 220)
                } else if let error {
                    VStack(spacing: 10) {
                        EmptyHint(
                            title: query.isEmpty ? "没有取到内容" : "没有找到「\(query)」",
                            message: error,
                            glyph: .search
                        )
                        MiniButton(text: "重新加载", accent: true) { reloadTick += 1 }
                            .padding(.bottom, 30)
                    }
                } else if picked == "agg" {
                    aggregateGrid
                } else {
                    sourceSections
                }
            }
            .padding(.bottom, 26)
        }
        .background(c.bg.ignoresSafeArea())
        .task(id: "\(picked)|\(query)|\(reloadTick)") { await load() }
    }

    /// 单源模式：分区 chips（JmBox 式分类）+ 当前分区的 3 列大封面网格
    @ViewBuilder
    private var sourceSections: some View {
        if sections.isEmpty {
            Text("这个源没有返回内容，换一个源试试。")
                .font(.system(size: 11.5))
                .foregroundColor(c.fg3)
                .padding(.horizontal, 18)
                .padding(.top, 20)
        } else {
            let safeIdx = min(sectionIdx, sections.count - 1)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 7) {
                    ForEach(Array(sections.enumerated()), id: \.offset) { i, sec in
                        Chip(text: sec.title.isEmpty ? "分区 \(i + 1)" : sec.title, selected: safeIdx == i) {
                            sectionIdx = i
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 2)
            }
            .frame(height: 38)
            .padding(.bottom, 6)

            let sec = sections[safeIdx]
            LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
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
        }
    }

    private var aggregateGrid: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
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

    private func submit() {
        query = keyword.trimmingCharacters(in: .whitespaces)
        reloadTick += 1
    }

    // ══════════════════════ 数据 ══════════════════════

    private func load() async {
        await MainActor.run {
            busy = true
            error = nil
        }
        await hub.load()

        // 第一次进来：默认选禁漫（用户要求第一个界面就是它），禁漫没启用就用排最前的源
        if picked.isEmpty {
            let pref = hub.enabledRows.first { $0.key == "jm" }?.key
                ?? hub.enabledRows.first?.key
                ?? ""
            if pref.isEmpty {
                await MainActor.run {
                    busy = false
                    error = "没有可用的源。去「我的 → 源管理」至少打开一个。"
                }
                return
            }
            await MainActor.run { picked = pref }
            return   // picked 变化会重启本 task，带着正确的源重新加载
        }

        let q = query

        if picked == "agg" {
            // ── 全部源聚合 ──
            let targets = Array(hub.enabledRows.prefix(8))
            if targets.isEmpty {
                await MainActor.run {
                    busy = false
                    error = "你把所有源都关掉了。去「我的 → 源管理」至少打开一个。"
                }
                return
            }
            let cacheSig = "v|" + q + "|" + targets.map { $0.key }.joined(separator: ",")
            var stale = false
            if let hit = ExploreCache.get(cacheSig) {
                let fresh = hit.fresh
                await MainActor.run {
                    works = hit.works
                    sections = []
                    busy = false
                    error = nil
                }
                if fresh { return }
                stale = true
            }
            var all: [ComicRow] = []
            var ok = 0
            await withTaskGroup(of: [ComicRow]?.self) { group in
                for s in targets {
                    group.addTask {
                        if q.isEmpty {
                            guard let r = await exploreWithTimeout(key: s.key, seconds: 15),
                                  (r["ok"] as? Bool) == true,
                                  let d = r["data"] as? [String: Any] else { return nil }
                            let secs = ExploreSection.parse(d, sourceKey: s.key, sourceName: s.name)
                            return secs.flatMap { $0.comics }.isEmpty ? nil : secs.flatMap { $0.comics }
                        } else {
                            guard let r = await searchWithTimeout(key: s.key, keyword: q, seconds: 15),
                                  (r["ok"] as? Bool) == true,
                                  let d = r["data"] as? [String: Any] else { return nil }
                            let comics = parseSearchComics(d, sourceKey: s.key, sourceName: s.name)
                            return comics.isEmpty ? nil : comics
                        }
                    }
                }
                for await result in group {
                    if let list = result {
                        all.append(contentsOf: list)
                        ok += 1
                        let snapshot = groupWorks(all, sort: false)
                        let okNow = ok
                        if !stale {
                            await MainActor.run {
                                works = snapshot
                                sections = []
                                if busy { busy = false }
                            }
                        }
                    }
                }
            }
            let grouped = groupWorks(all)
            let okFinal = ok
            await MainActor.run {
                works = grouped
                sections = []
                busy = false
                ExploreCache.put(cacheSig, grouped, okFinal, targets.count - okFinal)
                if grouped.isEmpty {
                    error = q.isEmpty
                        ? "这些源这次都没返回内容。可能是网站临时抽风，回头再刷一次。"
                        : "换个关键词试试，或者只选一个源搜。"
                }
            }
        } else {
            // ── 单源（默认模式）──
            guard let row = hub.enabledRows.first(where: { $0.key == picked }) else {
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
                sectionIdx = 0
                busy = false
                if finalSecs.isEmpty {
                    error = friendlyError(finalFail ?? "这个源没有内容。可能需要先登录，或者换个源。")
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
