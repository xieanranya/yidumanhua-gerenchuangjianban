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
                BigTitle(text: "我的", sub: appVersionText(), inset: false)
                    .padding(.horizontal, 18)

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
                    .font(.system(size: 10))
                    .foregroundColor(c.fg3)
                    .padding(.horizontal, 20)
                    .padding(.top, 14)
                    .padding(.bottom, 30)
            }
            .padding(.top, 4)
        }
        .background(c.bg)
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
            case .about:
                AboutSheet()
                    .environment(\.appColors, c)
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
        HLine().padding(.leading, 44)
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

    private func row(glyph: Glyph, title: String, sub: String, tone: Tone = .neutral) -> some View {
        HStack(spacing: 11) {
            GlyphView(glyph: glyph, tint: c.fg2, size: 17, strokeWidth: 1.6)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(c.fg)
                Text(sub)
                    .font(.system(size: 10.5))
                    .foregroundColor(tone == .neutral ? c.fg3 : tone.fg(c))
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            GlyphView(glyph: .right, tint: c.fg3, size: 13, strokeWidth: 1.6)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

// ================================================================================== 源管理

struct SourceManagerView: View {

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appColors) private var c
    @ObservedObject private var hub = SourceHub.shared
    @State private var account: SourceRow?

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "源管理",
                subtitle: sourceSummary(hub.rows),
                onBack: { dismiss() },
                trailing: nil
            )
            HLine()

            ScrollView {
                VStack(spacing: 0) {
                    ForEach(hub.rows) { row in
                        HStack(spacing: 11) {
                            NavigationLink {
                                BrowseView(sourceKey: row.key, sourceName: row.name)
                            } label: {
                                HStack(spacing: 11) {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                                            .fill(c.chip)
                                        Text(String(row.name.prefix(1)))
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundColor(c.fg2)
                                    }
                                    .frame(width: 34, height: 34)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(row.name)
                                            .font(.system(size: 13.5, weight: .medium))
                                            .foregroundColor(c.fg)
                                        Text(row.logged ? "已登录" : (row.needLogin ? "登录后才能看内容" : "公开内容可直接看"))
                                            .font(.system(size: 10))
                                            .foregroundColor(c.fg3)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)

                            if row.canLogin {
                                Button {
                                    account = row
                                } label: {
                                    Text(row.logged ? "已登录" : "登录")
                                        .font(.system(size: 10.5, weight: .medium))
                                        .foregroundColor(row.logged ? c.ok : c.accent)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background((row.logged ? c.ok : c.accent).opacity(0.12))
                                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        HLine().padding(.leading, 61)
                    }

                    NoteBar(
                        text: "内容直接来自各源自己的站点，某个域名打不开会自动换备用域名重试。"
                            + "E-Hentai / 绅士漫画 / PicACG 这类站点国内仍需自备代理。",
                        tone: .neutral,
                        inset: false
                    )
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
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
