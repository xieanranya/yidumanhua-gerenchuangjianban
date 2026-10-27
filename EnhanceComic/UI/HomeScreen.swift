import SwiftUI

/// 首页：搜索入口 / 继续阅读 / 快捷入口 / 热门推荐 / 源状态。
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
    @State private var openBook: Book?

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    /// 上次读到一半的本地书
    private var lastLocal: Book? {
        store.books
            .filter { $0.lastPage > 0 }
            .max { ($0.lastReadAt ?? $0.addedAt) < ($1.lastReadAt ?? $1.addedAt) }
    }

    private var lastOnline: OnlineRecord? { ui.history.first }

    private var continueIsOnline: Bool {
        guard let rec = lastOnline else { return false }
        guard let book = lastLocal else { return true }
        return rec.at > (book.lastReadAt ?? book.addedAt).timeIntervalSince1970
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BigTitle(text: "首页", sub: subline, inset: false)
                    .padding(.horizontal, 18)

                SearchStub(text: "搜漫画名、作者、题材", onTap: onSearch, inset: false)
                    .padding(.horizontal, 18)

                continueSection

                SectionHeader(title: "快捷入口", inset: false)
                    .padding(.horizontal, 18)
                quickEntries
                    .padding(.horizontal, 18)

                SectionHeader(
                    title: "热门推荐",
                    more: hotBusy ? "读取中" : (hotFrom > 0 ? "\(hotFrom) 个源" : nil),
                    onMore: hotBusy ? nil : onSearch,
                    inset: false
                )
                .padding(.horizontal, 18)

                if hotBusy {
                    LoadingBox(height: 160)
                } else if hot.isEmpty {
                    EmptyHint(
                        title: "还没有推荐",
                        message: "这些源这次都没给出内容，回头再刷一次试试。",
                        glyph: .discover
                    )
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                        ForEach(hot) { work in
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

                SectionHeader(
                    title: "漫画源",
                    more: "管理",
                    onMore: onSources,
                    inset: false
                )
                .padding(.horizontal, 18)
                NoteBar(
                    text: sourceSummary(hub.rows),
                    tone: .neutral,
                    inset: false
                )
                .padding(.horizontal, 18)
                .padding(.bottom, 26)
            }
            .padding(.top, 4)
        }
        .background(c.bg)
        .task { await loadHot() }
        .fullScreenCover(item: $openBook) { book in
            ReaderView(book: book).environmentObject(store)
        }
    }

    private var subline: String {
        let local = store.books.isEmpty ? "本地书库还是空的" : "本地 \(store.books.count) 本"
        let online = hub.rows.isEmpty ? "在线源读取中" : "\(hub.enabledRows.count) 个在线源"
        return local + " · " + online
    }

    @ViewBuilder
    private var continueSection: some View {
        if lastLocal != nil || lastOnline != nil {
            SectionHeader(
                title: "继续阅读",
                more: "全部",
                onMore: continueIsOnline ? onSources : onShelf,
                inset: false
            )
            .padding(.horizontal, 18)

            if continueIsOnline, let rec = lastOnline {
                NavigationLink {
                    DetailView(work: rec.asWork)
                } label: {
                    ContinueCard(
                        title: rec.sourceTitle.isEmpty ? rec.title : rec.sourceTitle,
                        meta: "\(rec.sourceName) · 读到 \(rec.epTitle)",
                        label: "在线阅读",
                        coverURL: rec.cover,
                        localCover: nil,
                        seed: rec.comicId.hashValue,
                        inset: false
                    )
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 18)
            } else if let book = lastLocal {
                let progress = book.pageCount > 0 ? Int(Double(book.lastPage + 1) / Double(book.pageCount) * 100) : 0
                ContinueCard(
                    title: book.title,
                    meta: "第 \(book.lastPage + 1) / \(book.pageCount) 页 · \(progress)%",
                    label: "本地阅读",
                    coverURL: nil,
                    localCover: CoverCache.image(at: store.coverURL(for: book)),
                    seed: book.title.hashValue,
                    inset: false
                )
                .onTapGesture { openBook = book }
                .padding(.horizontal, 18)
            }
        }
    }

    private var quickEntries: some View {
        HStack(spacing: 9) {
            quickCard(glyph: .shelf, title: "本地书架", note: "\(store.books.count) 本", action: onShelf)
            quickCard(glyph: .list, title: "源管理", note: "\(hub.enabledRows.count) 个", action: onSources)
            quickCard(glyph: .search, title: "搜索", note: "全源", action: onSearch)
        }
    }

    private func quickCard(glyph: Glyph, title: String, note: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 7) {
                GlyphView(glyph: glyph, tint: c.fg2, size: 18, strokeWidth: 1.6)
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(c.fg)
                    .lineLimit(1)
                Text(note)
                    .font(.system(size: 9.5))
                    .foregroundColor(c.fg3)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 11)
            .padding(.vertical, 10)
            .background(c.card2)
            .overlay(
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .stroke(c.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
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
                hot = Array(cached.works.prefix(12))
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
