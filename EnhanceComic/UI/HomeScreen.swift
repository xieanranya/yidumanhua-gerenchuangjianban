import SwiftUI

/// 首页（v8）：品牌头 + 杂志海报 + 今日热门 + 发现入口。
/// 排行榜已升为独立 Tab（RankView）；发现降级为这里的入口（分类浏览走二级页）。
struct HomeScreen: View {

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared

    // 今日热门（三个源并发拉，合并后取前 12 本横排）
    @State private var hot: [Work] = []
    @State private var hotFrom = 0
    @State private var hotBusy = true

    @State private var appeared = false
    @State private var posterDetail: Work?
    @State private var showDiscover = false

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

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
                    trailing: AnyView(GlassCircleButton(glyph: .search) { showDiscover = true })
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
                    // 6 本（2 行）：给发现入口留出首屏位置
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

                // ── 发现入口（分类浏览降级到这里，点开是二级页）──
                SectionHeader(title: "发现", inset: false)
                    .padding(.horizontal, 18)

                NavigationLink {
                    DiscoverScreen()
                } label: {
                    HStack(spacing: 12) {
                        GlyphView(glyph: .discover, tint: c.fg2, size: 17, strokeWidth: 1.6)
                            .frame(width: 34, height: 34)
                            .background(
                                RoundedRectangle(cornerRadius: 9, style: .continuous).fill(c.chip)
                            )
                        VStack(alignment: .leading, spacing: 2) {
                            Text("分类浏览 · 各源分区")
                                .font(.system(size: 13.5, weight: .medium))
                                .foregroundColor(c.fg)
                            Text("按源逛分类、标签与最新更新")
                                .font(.system(size: 10.5))
                                .foregroundColor(c.fg3)
                        }
                        Spacer(minLength: 0)
                        GlyphView(glyph: .right, tint: c.fg3, size: 13, strokeWidth: 1.6)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(c.card2)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(c.border, lineWidth: 0.5)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 18)
                .padding(.bottom, 26)
            }
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
        .navigationDestination(isPresented: $showDiscover) {
            DiscoverScreen()
        }
        .task { await loadHot() }
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
}
