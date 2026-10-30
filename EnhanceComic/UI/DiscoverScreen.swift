import SwiftUI

/// 发现：搜索 + 选源 + 全源聚合的三列网格。
///
/// 「全部」模式下会把不同源里**标题完全一致**的作品合并成一张卡，卡角标显示有几个源；
/// 点进去可以切源。各源没有互通的 ID，所以只认归一化后一模一样的标题，
/// 宁可少合也不把两本不同的书串成一本。
struct DiscoverScreen: View {

    @EnvironmentObject private var store: LibraryStore
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
        } else {
            discoverContent
        }
    }

    private var discoverContent: some View {
        VStack(spacing: 0) {
            BigTitle(text: "发现", sub: subline, inset: false)
                .padding(.horizontal, 18)

            searchBar
                .padding(.horizontal, 18)

            sourceChips

            HLine()

            content
        }
        .background(c.bg)
        .task { await load() }
        .onChange(of: picked) { _ in reloadTick += 1 }
        .onChange(of: reloadTick) { _ in
            Task { await load() }
        }
    }

    private var subline: String {
        if busy { return "正在从各源取内容…" }
        if picked.isEmpty {
            let multi = works.filter { $0.sourceCount > 1 }.count
            var s = "\(works.count) 部作品 · \(okSources) 个源"
            if multi > 0 { s += " · \(multi) 部是跨源同一本" }
            if failedSources > 0 { s += " · \(failedSources) 个源没响应" }
            return s
        }
        return "只看这个源"
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

    private var sourceChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                Chip(text: "全部", selected: picked.isEmpty) {
                    picked = ""
                }
                ForEach(hub.enabledRows) { row in
                    Chip(
                        text: row.name,
                        selected: picked == row.key
                    ) {
                        picked = picked == row.key ? "" : row.key
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
        }
    }

    @ViewBuilder
    private var content: some View {
        ScrollView {
            if busy {
                LoadingBox(height: 220)
            } else if let error {
                VStack(spacing: 10) {
                    EmptyHint(
                        title: query.isEmpty ? "没有取到内容" : "没有找到「\(query)」",
                        message: error,
                        glyph: .search
                    )
                    MiniButton(text: "重新加载", accent: true) {
                        reloadTick += 1
                    }
                    .padding(.bottom, 30)
                }
            } else if picked.isEmpty {
                aggregateGrid
            } else {
                singleSourceGrid
            }
        }
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
