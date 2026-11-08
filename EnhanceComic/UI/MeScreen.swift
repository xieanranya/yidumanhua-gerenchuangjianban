import SwiftUI

/// 我的：源管理 / 阅读记录 / 外观 / 本地漫画 / 阅读设置 / 关于。
struct MeScreen: View {

    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared

    @State private var showTheme = false
    /// 面板用枚举驱动，别在同一个 View 上挂两个 .sheet —— 老系统上会互相顶掉
    @State private var sheet: MeSheet?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // 沉浸头图：粉渐变铺到状态栏后面
                ImmersiveHeader(
                    title: "我的",
                    subtitle: "\(hub.rows.count) 个源 · 已登录 \(hub.rows.filter { $0.logged }.count) 个"
                ) {
                    ImmersivePoster(colors: PosterPalette.rose)
                }

                // 头像卡悬浮在头图边缘
                HStack(spacing: 13) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(LinearGradient(colors: [Color(red: 1.0, green: 0.43, blue: 0.61), Color(red: 0.88, green: 0.20, blue: 0.43)], startPoint: .topLeading, endPoint: .bottomTrailing))
                        Text("画")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .frame(width: 52, height: 52)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("画质漫画")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(c.fg)
                        Text("本地 \(store.books.count) 本 · \(sourceSummary(hub.rows))")
                            .font(.system(size: 11))
                            .foregroundColor(c.fg3)
                    }
                    Spacer(minLength: 0)
                    Text("›").font(.system(size: 16)).foregroundColor(c.fg3)
                }
                .padding(.horizontal, 18)
                .padding(.top, -30)
                .padding(.bottom, 18)

                groupCard {
                    NavigationLink {
                        SourceManagerView()
                    } label: {
                        row(
                            glyph: .list,
                            title: "源管理",
                            sub: sourceSummary(hub.rows),
                            tone: .neutral
                        )
                    }
                    .buttonStyle(.plain)
                    divider
                    NavigationLink {
                        HistoryView()
                    } label: {
                        row(
                            glyph: .bookmark,
                            title: "阅读记录",
                            sub: ui.history.isEmpty ? "还没有在线记录" : "\(ui.history.count) 条"
                        )
                    }
                    .buttonStyle(.plain)
                }

                groupCard {
                    Button {
                        showTheme = true
                    } label: {
                        row(glyph: .theme, title: "外观", sub: ui.theme.label)
                    }
                    .buttonStyle(.plain)
                    divider
                    Button {
                        sheet = .readerPrefs
                    } label: {
                        row(glyph: .layout, title: "阅读设置", sub: readerPrefsSub)
                    }
                    .buttonStyle(.plain)
                    divider
                    NavigationLink {
                        LocalBooksView()
                    } label: {
                        row(glyph: .shelf, title: "本地漫画", sub: "\(store.books.count) 本")
                    }
                    .buttonStyle(.plain)
                }

                groupCard {
                    Button {
                        sheet = .about
                    } label: {
                        row(glyph: .gear, title: "关于", sub: "画质漫画 \(appVersionText())")
                    }
                    .buttonStyle(.plain)
                }

                Text("纯自用版本，不上传任何数据。源脚本登录凭证只存在本机。")
                    .font(.system(size: 11.5))
                    .foregroundColor(c.fg3)
                    .padding(.horizontal, 20)
                    .padding(.top, 14)
                    .padding(.bottom, 30)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .coordinateSpace(name: "immersiveScroll")
        .ignoresSafeArea(edges: .top)
        .immersiveStatusBar(ui)
        .task { await hub.load() }
        .confirmationDialog("外观", isPresented: $showTheme, titleVisibility: .visible) {
            ForEach(ThemeMode.allCases) { mode in
                Button(mode.label) { ui.theme = mode }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("「跟随系统」跟手机的深色模式走；阅读界面里不放这个开关。")
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .readerPrefs:
                ReaderPrefsSheet()
                    .environmentObject(store)
                    .environment(\.appColors, c)
                    // sheet 从沉浸页弹出：这里期间状态栏交还给普通样式，关掉自动还原
                    .immersiveStatusBar(ui, light: false)
            case .about:
                AboutSheet()
                    .environment(\.appColors, c)
                    .immersiveStatusBar(ui, light: false)
            }
        }
    }

    private enum MeSheet: String, Identifiable {
        case readerPrefs
        case about
        var id: String { rawValue }
    }

    private var readerPrefsSub: String {
        var parts = [store.prefs.readingMode.label]
        if store.prefs.autoCropWhiteBorders { parts.append("裁白边") }
        if store.prefs.blueLight > 0 { parts.append("蓝光 \(store.prefs.blueLight)") }
        return parts.joined(separator: " · ")
    }

    private var divider: some View {
        HLine().padding(.leading, 62)
    }

    @ViewBuilder
    private func groupCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .background(c.card2)
            .overlay(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .stroke(c.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            .padding(.horizontal, 18)
            .padding(.bottom, 12)
    }

    /// 分组图标的彩色底（Aidoku Settings 式）：按枚举给固定色相
    private func iconTint(_ g: Glyph) -> Color {
        switch g {
        case .list: return Color(red: 0.33, green: 0.42, blue: 0.88)
        case .bookmark: return Color(red: 0.15, green: 0.56, blue: 0.95)
        case .shelf: return Color(red: 0.95, green: 0.55, blue: 0.25)
        case .theme: return Color(red: 0.58, green: 0.40, blue: 0.88)
        case .layout: return Color(red: 0.28, green: 0.62, blue: 0.92)
        case .spark: return Color(red: 1.00, green: 0.43, blue: 0.61)
        case .gear: return Color(red: 0.44, green: 0.48, blue: 0.56)
        default: return Color(red: 0.55, green: 0.45, blue: 0.85)
        }
    }

    private func row(glyph: Glyph, title: String, sub: String, tone: Tone = .neutral) -> some View {
        HStack(spacing: 13) {
            GlyphView(glyph: glyph, tint: .white, size: 16, strokeWidth: 1.7)
                .frame(width: 34, height: 34)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(iconTint(glyph))
                )
            VStack(alignment: .leading, spacing: 2.5) {
                Text(title)
                    .font(.system(size: 15.5, weight: .semibold))
                    .foregroundColor(c.fg)
                Text(sub)
                    .font(.system(size: 12))
                    .foregroundColor(tone == .neutral ? c.fg3 : tone.fg(c))
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            GlyphView(glyph: .right, tint: c.fg3, size: 14, strokeWidth: 1.6)
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
    }
}

// ================================================================================== 源管理

/// 源管理（v3 沉浸式）：头图 + 毛玻璃源卡（渐变图标 / 登录态 / 开关 / 排序）；
/// 禁漫展开专属区：线路选择 + 自动签到开关 + 立即签到。
struct SourceManagerView: View {

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared
    @State private var account: SourceRow?
    // 源专属面板：线路选择目标（源key + 设置项 + 标题）
    @State private var linePick: LinePickTarget?
    @State private var checkinBusy: Set<String> = []
    @State private var checkinMsg: [String: String] = [:]
    // 线路现场测速
    @State private var probeResults: [String: String] = [:]
    @State private var probeRunning = false

    struct LinePickTarget: Identifiable {
        let rowKey: String
        let group: SourceHub.LineGroup
        var id: String { rowKey + group.settingKey }
    }

    private var loggedCount: Int { hub.rows.filter { $0.logged }.count }

    private var subline: String {
        "\(hub.rows.count) 个源 · 已登录 \(loggedCount) 个"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ImmersiveHeader(title: "源管理", subtitle: subline, height: 240) {
                    ImmersivePoster(colors: PosterPalette.azure)
                }

                VStack(spacing: 9) {
                    ForEach(Array(hub.rows.enumerated()), id: \.element.id) { idx, row in
                        sourceCard(row, idx)
                        if row.enabled && hasPanel(row) {
                            sourcePanel(row, idx)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, -30)

                NoteBar(
                    text: "关掉的源不参与「全部源」搜索和首页推荐，单独点进去仍然能看。"
                        + "标「可直连」的线路不用开代理；连不上时会自动换备用线路。",
                    tone: .neutral,
                    inset: false
                )
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 30)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .coordinateSpace(name: "immersiveScroll")
        .ignoresSafeArea(edges: .top)
        .overlay(alignment: .topLeading) {
            GlassCircleButton(glyph: .back) { dismiss() }
                .padding(.leading, 16)
                .padding(.top, safeTop() + 8)
        }
        .immersiveStatusBar(ui)
        .toolbar(.hidden, for: .navigationBar)
        .task { await hub.load(force: false) }
        .navigationDestination(
            isPresented: Binding(
                get: { account != nil },
                set: { shown in
                    if !shown {
                        account = nil
                        Task { await hub.refreshRows() }
                    }
                }
            )
        ) {
            AccountView(key: account?.key ?? "", fallbackName: account?.name ?? "")
        }
        .confirmationDialog(
            "选择\(linePick?.group.title ?? "线路")",
            isPresented: Binding(
                get: { linePick != nil },
                set: { if !$0 { linePick = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let target = linePick {
                // 直连的排前面；每条线路旁显示现场测速结果（真实可达性一目了然）
                ForEach(Array(target.group.lines.sorted { $0.direct && !$1.direct }.enumerated()), id: \.offset) { _, line in
                    Button {
                        SourceHub.setLineValue(target.rowKey, target.group.settingKey, line.value)
                    } label: {
                        HStack(spacing: 6) {
                            Text(line.label)
                            if line.direct {
                                Text("· 可直连").foregroundColor(c.ok)
                            }
                            if probeRunning, line.probeHost != nil {
                                Text("测速中…").foregroundColor(c.fg3)
                            } else if let r = probeResults[line.label] {
                                Text(r)
                                    .foregroundColor(r.hasPrefix("✓") ? c.ok : c.bad)
                            }
                        }
                    }
                }
                Button("恢复默认", role: .destructive) {
                    SourceHub.setLineValue(target.rowKey, target.group.settingKey, nil)
                }
                Button("取消", role: .cancel) {}
            }
        } message: {
            Text("线路清单全部来自官方 App，打开时会自动逐条实测：✓ = 服务器可达（含耗时）。")
        }
    }

    /// 打开线路选择时自动对每条线路发一次真实请求（最多 8 秒/条，并发跑）
    private func probeStart(_ target: LinePickTarget) {
        probeResults = [:]
        guard target.group.lines.contains(where: { $0.probeHost != nil }) else { return }
        probeRunning = true
        Task {
            let r = await SourceHub.shared.probeLines(target.rowKey, target.group)
            await MainActor.run {
                probeResults = r
                probeRunning = false
            }
        }
    }

    private func hasPanel(_ row: SourceRow) -> Bool {
        !SourceHub.lineGroups(for: row.key).isEmpty || (row.canCheckin && row.logged)
    }

    // ────────────────────────── 源行卡 ──────────────────────────

    private func sourceCard(_ row: SourceRow, _ idx: Int) -> some View {
        HStack(spacing: 12) {
            NavigationLink {
                BrowseView(sourceKey: row.key, sourceName: row.name)
            } label: {
                HStack(spacing: 12) {
                    Text(String(row.name.prefix(1)))
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(row.enabled ? .white : .white.opacity(0.55))
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
                    VStack(alignment: .leading, spacing: 2.5) {
                        Text(row.name)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(row.enabled ? c.fg : c.fg3)
                        HStack(spacing: 5) {
                            if row.logged {
                                Circle().fill(c.ok).frame(width: 5.5, height: 5.5)
                            }
                            Text(row.logged ? "已登录"
                                 : (row.needLogin ? "不登录看不了"
                                    : (row.canLogin ? "未登录" : "公开内容")))
                                .font(.system(size: 10))
                                .foregroundColor(row.logged ? c.ok : c.fg3)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            VStack(alignment: .trailing, spacing: 4) {
                Toggle("", isOn: Binding(
                    get: { row.enabled },
                    set: { hub.setEnabled(row.key, $0) }
                ))
                .labelsHidden()
                .tint(c.accent)
                .scaleEffect(0.82)
                .frame(width: 43, height: 26)

                HStack(spacing: 4) {
                    if row.canLogin {
                        Button {
                            account = row
                        } label: {
                            Text(row.logged ? "账号" : "登录")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundColor(row.logged ? c.ok : c.accent)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background((row.logged ? c.ok : c.accent).opacity(0.12))
                                .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                    moveButton("chevron.up", enabled: idx > 0) {
                        hub.move(row.key, by: -1)
                    }
                    moveButton("chevron.down", enabled: idx < hub.rows.count - 1) {
                        hub.move(row.key, by: 1)
                    }
                }
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(c.card.opacity(0.55))
        )
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(c.border.opacity(0.8), lineWidth: 0.8)
        )
    }

    // ────────────────────────── 源专属面板（线路 + 签到） ──────────────────────────

    private func sourcePanel(_ row: SourceRow, _ idx: Int) -> some View {
        let groups = SourceHub.lineGroups(for: row.key)
        return VStack(spacing: 0) {
            ForEach(Array(groups.enumerated()), id: \.element.settingKey) { gi, group in
                if gi > 0 { panelDivider }
                Button {
                    let target = LinePickTarget(rowKey: row.key, group: group)
                    linePick = target
                    probeStart(target)
                } label: {
                    panelRow(
                        glyph: .layout,
                        tint: Color(red: 0.28, green: 0.62, blue: 0.92),
                        title: "\(row.name) \(group.title)",
                        sub: SourceHub.lineLabel(row.key, group)
                    ) {
                        GlyphView(glyph: .right, tint: c.fg3, size: 13, strokeWidth: 1.6)
                    }
                }
                .buttonStyle(.plain)
            }

            if row.canCheckin {
                if !groups.isEmpty { panelDivider }
                // 自动签到
                panelRow(
                    glyph: .spark,
                    tint: Color(red: 1.00, green: 0.43, blue: 0.61),
                    title: "自动签到",
                    sub: row.logged ? "打开 App 时自动签" : "登录后可用"
                ) {
                    Toggle("", isOn: Binding(
                        get: { row.logged && hub.autoCheckinOn(row.key) },
                        set: { if row.logged { hub.setAutoCheckin(row.key, $0) } }
                    ))
                    .labelsHidden()
                    .tint(c.accent)
                    .scaleEffect(0.82)
                    .frame(width: 43, height: 26)
                    .disabled(!row.logged)
                }
                panelDivider
                // 立即签到
                panelRow(
                    glyph: .bookmark,
                    tint: Color(red: 0.15, green: 0.56, blue: 0.95),
                    title: "立即签到",
                    sub: checkinMsg[row.key]
                        ?? (hub.checkedToday(row.key) ? "今天已签到" : "今天还没签到")
                ) {
                    if checkinBusy.contains(row.key) {
                        ProgressView().tint(c.accent).scaleEffect(0.8)
                    } else if hub.checkedToday(row.key) {
                        GlyphView(glyph: .spark, tint: c.ok, size: 14, strokeWidth: 1.8)
                    } else {
                        Button {
                            checkinBusy.insert(row.key)
                            Task {
                                let (ok, msg) = await hub.checkinNow(row)
                                await MainActor.run {
                                    checkinMsg[row.key] = ok ? msg : "失败：\(msg)"
                                    checkinBusy.remove(row.key)
                                }
                            }
                        } label: {
                            Text("签到")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(c.accentInk)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 5)
                                .background(c.accent)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(c.accent.opacity(0.06))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(c.accent.opacity(0.25), lineWidth: 0.8)
        )
    }

    private var panelDivider: some View {
        HLine().padding(.leading, 60)
    }

    @ViewBuilder
    private func panelRow<Trailing: View>(
        glyph: Glyph, tint: Color, title: String, sub: String,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        HStack(spacing: 12) {
            GlyphView(glyph: glyph, tint: .white, size: 15, strokeWidth: 1.7)
                .frame(width: 30, height: 30)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(tint)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundColor(c.fg)
                Text(sub)
                    .font(.system(size: 10))
                    .foregroundColor(c.fg3)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            trailing()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    /// 行内的小箭头：调顺序用。到头了就淡掉并禁用，不做「绕回」。
    @ViewBuilder
    private func moveButton(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 9, weight: .bold))
                .foregroundColor(enabled ? c.fg2 : c.fg3.opacity(0.3))
                .frame(width: 21, height: 19)
                .background(enabled ? c.chip : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// ================================================================================= 阅读记录

struct HistoryView: View {

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var ui: UiStore
    @Environment(\.appColors) private var c

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "阅读记录",
                subtitle: ui.history.isEmpty ? nil : "\(ui.history.count) 条",
                onBack: { dismiss() },
                trailing: clearButton
            )
            HLine()

            if ui.history.isEmpty {
                EmptyHint(
                    title: "还没有在线记录",
                    message: "在「发现」里点开一本书开始读，这里会记住读到哪一话。",
                    glyph: .bookmark
                )
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(ui.history) { rec in
                            NavigationLink {
                                DetailView(work: rec.asWork)
                            } label: {
                                HStack(spacing: 11) {
                                    ZStack {
                                        coverGradient(seed: rec.comicId.hashValue, dark: c.dark)
                                        if !rec.cover.isEmpty {
                                            RemoteImage(url: rec.cover, contentMode: .fill, maxPixel: 240)
                                        }
                                    }
                                    .frame(width: 38, height: 50)
                                    .clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(rec.sourceTitle.isEmpty ? rec.title : rec.sourceTitle)
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundColor(c.fg)
                                            .lineLimit(1)
                                        Text("\(rec.sourceName) · 读到 \(rec.epTitle)")
                                            .font(.system(size: 10.5))
                                            .foregroundColor(c.fg3)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                    GlyphView(glyph: .right, tint: c.fg3, size: 13, strokeWidth: 1.6)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 9)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            HLine().padding(.leading, 65)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private var clearButton: AnyView? {
        guard !ui.history.isEmpty else { return nil }
        return AnyView(
            Button {
                ui.clearHistory()
            } label: {
                Text("清空")
                    .font(.system(size: 12))
                    .foregroundColor(c.fg3)
            }
            .buttonStyle(.plain)
        )
    }
}

// =========================================================================== 本地漫画（我的页）

struct LocalBooksView: View {

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.appColors) private var c
    @State private var openBook: Book?

    private var columns: [GridItem] {
        let n = c.dark ? 3 : 2
        return Array(repeating: GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9), count: n)
    }

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "本地漫画",
                subtitle: "\(store.books.count) 本",
                onBack: { dismiss() },
                trailing: nil
            )
            HLine()
            ScrollView {
                if store.books.isEmpty {
                    EmptyHint(title: "书架还是空的", message: "去「书架」页导入漫画。", glyph: .shelf)
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                        ForEach(store.books) { book in
                            VStack(alignment: .leading, spacing: 5) {
                                ZStack {
                                    coverGradient(seed: book.title.hashValue, dark: c.dark)
                                    if let cover = CoverCache.image(at: store.coverURL(for: book)) {
                                        Image(uiImage: cover).resizable().scaledToFill()
                                    } else {
                                        Text(String(book.title.prefix(1)))
                                            .font(.system(size: 24, weight: .bold))
                                            .foregroundColor(placeholderInk(c.dark))
                                    }
                                }
                                .aspectRatio(0.72, contentMode: .fit)
                                .clipped()
                                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                                Text(book.title)
                                    .font(.system(size: 11.5, weight: .medium))
                                    .foregroundColor(c.fg)
                                    .lineLimit(2)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { openBook = book }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 12)
                    .padding(.bottom, 24)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .fullScreenCover(item: $openBook) { book in
            ReaderView(book: book).environmentObject(store)
        }
    }
}

// ============================================================================ 阅读设置面板

/// 阅读设置。阅读器里那套面板照旧存在（读的时候临时调），这里是「设一次」的地方。
struct ReaderPrefsSheet: View {

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appColors) private var c

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(title: "阅读设置", subtitle: "跟阅读器里的面板是同一份设置", onBack: { dismiss() }, trailing: nil)
            HLine()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionHeader(title: "翻页方式", inset: false)
                        .padding(.horizontal, 18)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 7) {
                            ForEach(ReadingMode.allCases) { mode in
                                Chip(text: mode.label, selected: store.prefs.readingMode == mode) {
                                    var p = store.prefs
                                    p.readingMode = mode
                                    store.prefs = p
                                }
                            }
                        }
                        .padding(.horizontal, 18)
                    }
                    .padding(.bottom, 6)

                    SectionHeader(title: "显示", inset: false)
                        .padding(.horizontal, 18)
                    toggleRow("自动裁白边", on: store.prefs.autoCropWhiteBorders) { v in
                        var p = store.prefs; p.autoCropWhiteBorders = v; store.prefs = p
                    }
                    toggleRow("从右往左翻（日漫）", on: store.prefs.rightToLeft) { v in
                        var p = store.prefs; p.rightToLeft = v; store.prefs = p
                    }
                    toggleRow("宽页自动对半分", on: store.prefs.splitWidePages) { v in
                        var p = store.prefs; p.splitWidePages = v; store.prefs = p
                    }
                    toggleRow("白色工具栏", on: store.prefs.chromeLight) { v in
                        var p = store.prefs; p.chromeLight = v; store.prefs = p
                    }

                    SectionHeader(title: "蓝光过滤", inset: false)
                        .padding(.horizontal, 18)
                    HStack(spacing: 10) {
                        Slider(
                            value: Binding(
                                get: { Double(store.prefs.blueLight) },
                                set: { v in
                                    var p = store.prefs
                                    p.blueLight = Int(v.rounded())
                                    store.prefs = p
                                }
                            ),
                            in: 0...100
                        )
                        .tint(c.accent)
                        Text("\(store.prefs.blueLight)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(c.fg3)
                            .frame(width: 28)
                    }
                    .padding(.horizontal, 18)
                    Text("0 = 关闭。夜晚看久了眼睛累就往上调。")
                        .font(.system(size: 10))
                        .foregroundColor(c.fg3)
                        .padding(.horizontal, 18)
                        .padding(.top, 2)
                        .padding(.bottom, 24)
                }
                .padding(.top, 4)
            }
        }
        .background(c.bg.ignoresSafeArea())
    }

    private func toggleRow(_ title: String, on: Bool, onChange: @escaping (Bool) -> Void) -> some View {
        HStack {
            Text(title)
                .font(.system(size: 12.5))
                .foregroundColor(c.fg)
            Spacer(minLength: 0)
            Toggle("", isOn: Binding(get: { on }, set: { onChange($0) }))
                .labelsHidden()
                .tint(c.accent)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 4)
    }
}

// ==================================================================================== 关于

struct AboutSheet: View {

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appColors) private var c

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(title: "关于", onBack: { dismiss() }, trailing: nil)
            HLine()
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("画质漫画")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(c.fg)
                    Text(appVersionText())
                        .font(.system(size: 11))
                        .foregroundColor(c.fg3)
                    Text("双端自用阅读器：本地 ZIP / RAR / 7Z / TAR / PDF + 在线漫画源。\n"
                         + "源脚本是 Venera 兼容的 JS，App 内置 JS 引擎自己跑；\n"
                         + "登录凭证只存在本机，不上传任何服务器。")
                        .font(.system(size: 12))
                        .foregroundColor(c.fg2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
        }
        .background(c.bg.ignoresSafeArea())
    }
}
