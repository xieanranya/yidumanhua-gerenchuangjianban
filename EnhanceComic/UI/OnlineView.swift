import SwiftUI

// ======================================================================== 单源浏览/搜索

struct BrowseView: View {

    let sourceKey: String
    let sourceName: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appColors) private var c

    @State private var keyword = ""
    @State private var sections: [ExploreSection] = []
    @State private var busy = true
    @State private var error: String?
    @State private var searched = false
    @State private var logged = false
    @State private var tick = 0

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: sourceName,
                subtitle: searched ? "搜索结果" : "首页推荐",
                onBack: { dismiss() },
                trailing: AnyView(
                    NavigationLink {
                        AccountView(key: sourceKey, fallbackName: sourceName)
                    } label: {
                        Text(logged ? "已登录" : "账号")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(logged ? c.ok : c.accent)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background((logged ? c.ok : c.accent).opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    }
                    .buttonStyle(.plain)
                )
            )

            searchBar.padding(.horizontal, 16).padding(.bottom, 8)
            HLine()

            ScrollView {
                if busy {
                    LoadingBox(height: 240)
                } else if let error {
                    VStack(spacing: 10) {
                        EmptyHint(
                            title: searched ? "没有找到" : "没有取到内容",
                            message: error,
                            glyph: .search
                        )
                        MiniButton(text: "重新加载", accent: true) { tick += 1 }
                            .padding(.bottom, 30)
                    }
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(sections) { sec in
                            if !sec.title.isEmpty {
                                SectionHeader(title: sec.title, more: "\(sec.comics.count) 本", inset: false)
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
                            .padding(.top, 6)
                            .padding(.bottom, 10)
                        }
                    }
                    .padding(.bottom, 26)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .onChange(of: tick) { _ in Task { await load() } }
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            GlyphView(glyph: .search, tint: c.fg3, size: 15, strokeWidth: 1.6)
            TextField("在这个源里搜索", text: $keyword)
                .font(.system(size: 13))
                .foregroundColor(c.fg)
                .submitLabel(.search)
                .onSubmit { Task { await search() } }
                .accentColor(c.accent)
        }
        .padding(.horizontal, 12)
        .frame(height: 38)
        .background(c.card2)
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(c.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func load() async {
        await MainActor.run {
            busy = true
            error = nil
            searched = false
        }
        let a = await SourceEngine.shared.account(key: sourceKey)
        if (a["ok"] as? Bool) == true, let d = a["data"] as? [String: Any] {
            await MainActor.run { logged = (d["logged"] as? Bool) == true }
        }
        guard let r = await exploreWithTimeout(key: sourceKey, seconds: 25) else {
            await MainActor.run {
                busy = false
                sections = []
                error = "等了 25 秒没响应。这个站点可能暂时打不开，稍后再试，或者先开代理。"
            }
            return
        }
        let ok = (r["ok"] as? Bool) == true
        let data = r["data"] as? [String: Any]
        let reason = r["error"] as? String
        await MainActor.run {
            busy = false
            if ok, let data {
                sections = ExploreSection.parse(data, sourceKey: sourceKey, sourceName: sourceName)
                error = sections.isEmpty ? "这个源没有首页内容，试试直接搜索。" : nil
            } else {
                sections = []
                error = friendlyError(reason ?? "加载失败")
            }
        }
    }

    private func search() async {
        let q = keyword.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        await MainActor.run {
            busy = true
            error = nil
            searched = true
        }
        guard let r = await searchWithTimeout(key: sourceKey, keyword: q, seconds: 25) else {
            await MainActor.run {
                busy = false
                sections = []
                error = "搜索超时了。这个源这次没响应，换个关键词或过一会儿再试。"
            }
            return
        }
        let ok = (r["ok"] as? Bool) == true
        let data = r["data"] as? [String: Any]
        let reason = r["error"] as? String
        await MainActor.run {
            busy = false
            if ok, let data {
                let comics = parseSearchComics(data, sourceKey: sourceKey, sourceName: sourceName)
                sections = comics.isEmpty ? [] : [ExploreSection(title: "搜索结果", comics: comics)]
                error = comics.isEmpty ? "没有找到「\(q)」" : nil
            } else {
                sections = []
                error = friendlyError(reason ?? "搜索失败")
            }
        }
    }
}

// ============================================================================== 详情

/// 一话的引用（摊平后仍保留分组标题，暂时只用来定位和显示）
private struct EpRef: Identifiable {
    let id: String
    let title: String
    let index: Int
    let group: String
}

struct DetailView: View {

    let work: Work

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var ui: UiStore
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.appColors) private var c

    @State private var entryIndex = 0
    @State private var info: [String: Any] = [:]
    @State private var chapters: [EpRef] = []
    @State private var busy = true
    @State private var error: String?
    @State private var reading: ReadingPayload?

    private let chapterColumns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 7),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 7),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 7),
    ]

    private var entry: WorkEntry {
        work.entries[min(entryIndex, work.entries.count - 1)]
    }

    private var title: String {
        let t = info["title"] as? String ?? ""
        return t.isEmpty ? work.title : t
    }

    private var cover: String {
        let s = info["cover"] as? String ?? ""
        return s.isEmpty ? work.cover : s
    }

    private var description: String {
        (info["description"] as? String ?? "")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    private var record: OnlineRecord? {
        ui.record(sourceKey: entry.sourceKey, comicId: entry.comicId)
    }

    private var currentEpId: String { record?.epId ?? "" }

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: title,
                subtitle: work.sourceCount > 1 ? "\(work.sourceCount) 个源有这本" : entry.sourceName,
                onBack: { dismiss() },
                trailing: AnyView(
                    NavigationLink {
                        AccountView(key: entry.sourceKey, fallbackName: entry.sourceName)
                    } label: {
                        Text("账号")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(c.accent)
                    }
                    .buttonStyle(.plain)
                )
            )
            HLine()

            if busy {
                LoadingBox(height: 260)
                Spacer(minLength: 0)
            } else if let error {
                EmptyHint(title: "加载失败", message: error, glyph: .spark)
                MiniButton(text: "重试", accent: true) { Task { await load(entryIndex) } }
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        head
                        sourceSwitch
                        continueButton
                        chapterGrid
                    }
                    .padding(.bottom, 26)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await load(0) }
        // 用 fullScreenCover 而不是 sheet：sheet 是个卡片，顶部会露出上一页，
        // 看起来就不是全屏阅读（本地阅读器一直是 fullScreenCover）。
        .fullScreenCover(item: $reading) { payload in
            ReaderSheet(payload: payload)
                .environmentObject(store)
        }
    }

    private var head: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                coverGradient(seed: work.norm.hashValue, dark: c.dark)
                if cover.isEmpty {
                    Text(String(title.prefix(1)))
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(placeholderInk(c.dark))
                } else {
                    RemoteImage(url: cover, contentMode: .fill, maxPixel: 420)
                }
            }
            .frame(width: 84, height: 114)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(c.fg)
                    .lineLimit(2)
                Text("来源：\(entry.sourceName)")
                    .font(.system(size: 10.5))
                    .foregroundColor(c.accent)
                if !description.isEmpty {
                    Text(description)
                        .font(.system(size: 10.5))
                        .foregroundColor(c.fg3)
                        .lineLimit(5)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, 12)
    }

    /// 同作品多源：这里直接切源，章节和阅读进度各算各的。
    @ViewBuilder
    private var sourceSwitch: some View {
        if work.entries.count > 1 {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: "换一个源看", inset: false)
                    .padding(.horizontal, 18)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 7) {
                        ForEach(work.entries.indices, id: \.self) { i in
                            Chip(
                                text: work.entries[i].sourceName,
                                selected: i == entryIndex
                            ) {
                                guard i != entryIndex else { return }
                                Task { await load(i) }
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                }
            }
        }
    }

    @ViewBuilder
    private var continueButton: some View {
        if !chapters.isEmpty {
            let target = chapters.first { $0.id == currentEpId } ?? chapters[0]
            HStack(spacing: 9) {
                Button {
                    Task { await open(target) }
                } label: {
                    HStack(spacing: 6) {
                        GlyphView(glyph: .right, tint: c.accentInk, size: 14, strokeWidth: 2)
                        Text(record != nil ? "继续阅读 · \(target.title)" : "从第一话开始")
                            .font(.system(size: 13, weight: .semibold))
                            .lineLimit(1)
                    }
                    .foregroundColor(c.accentInk)
                    .padding(.horizontal, 14)
                    .frame(height: 40)
                    .frame(maxWidth: .infinity)
                    .background(c.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)

                Text("\(chapters.count) 话")
                    .font(.system(size: 10.5))
                    .foregroundColor(c.fg3)
            }
            .padding(.horizontal, 18)
            .padding(.top, 14)
        }
    }

    private var chapterGrid: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(
                title: "章节",
                more: record != nil ? "读到 \(record?.epTitle ?? "")" : nil,
                inset: false
            )
            .padding(.horizontal, 18)

            LazyVGrid(columns: chapterColumns, alignment: .leading, spacing: 7) {
                ForEach(chapters) { ep in
                    Button {
                        Task { await open(ep) }
                    } label: {
                        Text(ep.title)
                            .font(.system(size: 10.5, weight: ep.id == currentEpId ? .semibold : .regular))
                            .foregroundColor(ep.id == currentEpId ? c.accentInk : c.fg2)
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .frame(maxWidth: .infinity)
                            .padding(.horizontal, 6)
                            .frame(height: 32)
                            .background(ep.id == currentEpId ? c.accent : c.card2)
                            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 18)
        }
    }

    private func load(_ index: Int) async {
        let e = work.entries[min(index, work.entries.count - 1)]
        await MainActor.run {
            entryIndex = index
            busy = true
            error = nil
            chapters = []
            info = [:]
        }
        let r = await SourceEngine.shared.detail(key: e.sourceKey, id: e.comicId)
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            let reason = r["error"] as? String ?? "加载失败"
            await MainActor.run {
                busy = false
                error = friendlyError(reason)
            }
            return
        }
        var flat: [EpRef] = []
        for g in (d["groups"] as? [[String: Any]] ?? []) {
            let gt = g["title"] as? String ?? ""
            for o in (g["items"] as? [[String: Any]] ?? []) {
                // 「整本一话」的源（NoyACG）章节 id 可能是空的，只有标题也要保留
                let rid = o["id"] as? String ?? ""
                let rtitle = o["title"] as? String ?? ""
                guard !rid.isEmpty || !rtitle.isEmpty else { continue }
                let id = rid.isEmpty ? "-" : rid
                let title = rtitle.isEmpty ? "全部页面" : rtitle
                flat.append(
                    EpRef(
                        id: id,
                        title: title,
                        index: flat.count,
                        group: gt
                    )
                )
            }
        }
        let finalFlat = flat
        await MainActor.run {
            info = d
            chapters = finalFlat
            busy = false
            if finalFlat.isEmpty {
                error = "这个源没有返回章节。可能需要先登录，或者换个源。"
            }
        }
    }

    private func open(_ ep: EpRef) async {
        await MainActor.run { busy = true }
        let r = await SourceEngine.shared.pages(
            key: entry.sourceKey,
            comicId: entry.comicId,
            epId: ep.id
        )
        await MainActor.run { busy = false }
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            await MainActor.run { error = friendlyError(r["error"] as? String ?? "取图失败") }
            return
        }
        ui.remember(
            sourceKey: entry.sourceKey,
            sourceName: entry.sourceName,
            comicId: entry.comicId,
            title: work.title,
            cover: cover,
            sourceTitle: title,
            epId: ep.id,
            epTitle: ep.title,
            epIndex: ep.index
        )
        // 惰性源（E-Hentai 这类一页一次 API）只回页数，真地址进阅读器再逐页解
        if (d["lazy"] as? Bool) == true {
            let n = d["count"] as? Int ?? 0
            guard n > 0 else {
                await MainActor.run { error = "这一话没有图片（可能需要登录）" }
                return
            }
            await MainActor.run {
                reading = ReadingPayload(
                    title: ep.title,
                    urls: [],
                    lazy: LazyRef(
                        key: entry.sourceKey,
                        comicId: entry.comicId,
                        epId: ep.id,
                        count: n
                    ),
                    sourceKey: entry.sourceKey,
                    comicId: entry.comicId,
                    chapters: chapters.map { ($0.id, $0.title) },
                    chapterIndex: ep.index
                )
            }
            return
        }
        let urls = d["images"] as? [String] ?? []
        guard !urls.isEmpty else {
            await MainActor.run { error = "这一话没有图片（可能需要登录）" }
            return
        }
        await MainActor.run {
            reading = ReadingPayload(
                title: ep.title,
                urls: urls,
                sourceKey: entry.sourceKey,
                comicId: entry.comicId,
                chapters: chapters.map { ($0.id, $0.title) },
                chapterIndex: ep.index
            )
        }
    }
}

// ============================================================================== 账号

/// 独立账号页：登录 / 退出 / 看账号信息。照着 PicACG 那种「先登录再进内容」的习惯做的。
struct AccountView: View {

    let key: String
    let fallbackName: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appColors) private var c
    @Environment(\.openURL) private var openURL

    @State private var info: [String: Any] = [:]
    @State private var loading = true
    @State private var loadError: String?
    @State private var user = ""
    @State private var pass = ""
    @State private var busy = false
    @State private var loginError: String?
    @State private var notice: String?
    /// Cookie 登录（E-Hentai 这类）按源声明的字段数填，比如 ipb_member_id / ipb_pass_hash / igneous / star
    @State private var cookieVals: [String] = []
    /// 网页登录（绅士漫画要过滑块，账号密码接口过不去）
    @State private var webLogin = false

    private var name: String {
        let n = info["name"] as? String ?? ""
        return n.isEmpty ? fallbackName : n
    }
    private var logged: Bool { (info["logged"] as? Bool) == true }
    private var canLogin: Bool { (info["canLogin"] as? Bool) == true }
    private var canWeb: Bool { (info["canWebLogin"] as? Bool) == true }
    private var registerUrl: String { info["registerUrl"] as? String ?? "" }
    private var webUrl: String { info["loginWebUrl"] as? String ?? "" }
    private var savedAccount: String { info["savedAccount"] as? String ?? "" }
    private var loginMode: String { info["loginMode"] as? String ?? "" }
    private var loginFields: [String] { info["loginFields"] as? [String] ?? [] }

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "账号",
                subtitle: name,
                onBack: { dismiss() },
                trailing: AnyView(
                    Text(logged ? "已登录" : (canLogin ? "未登录" : "免登录"))
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundColor(logged ? c.ok : (canLogin ? c.warn : c.fg3))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background((logged ? c.ok : (canLogin ? c.warn : c.fg3)).opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                )
            )
            HLine()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if loading {
                        LoadingBox(height: 140)
                    } else if canLogin {
                        if logged {
                            groupCard {
                                Text(savedAccount == "cookies"
                                     ? "Cookie 登录"
                                     : (savedAccount.isEmpty ? "（该源未回传账号名）" : savedAccount))
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundColor(c.fg)
                                if let notice {
                                    Text(notice)
                                        .font(.system(size: 11))
                                        .foregroundColor(c.ok)
                                        .padding(.top, 4)
                                }
                                infoRows
                            }
                            MiniButton(text: busy ? "退出中…" : "退出登录", enabled: !busy) {
                                Task { await logout() }
                            }
                            .padding(.horizontal, 18)
                            .padding(.top, 12)
                            Text("退出后这个源要重新登录才能搜索和看图。")
                                .font(.system(size: 10))
                                .foregroundColor(c.fg3)
                                .padding(.horizontal, 18)
                                .padding(.top, 6)
                        } else if loginMode == "cookie" {
                            groupCard {
                                Text("Cookie 登录")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(c.fg)
                                Text("在浏览器里登录 \(name)，打开开发者工具 → Application → Cookies，" +
                                     "把下面这 \(loginFields.count) 项逐个复制过来。填一次就不用再管。")
                                    .font(.system(size: 11))
                                    .foregroundColor(c.fg2)
                                    .padding(.top, 4)
                                ForEach(loginFields.indices, id: \.self) { i in
                                    HLine().padding(.vertical, 2)
                                    TextField(loginFields[i], text: Binding(
                                        get: { i < cookieVals.count ? cookieVals[i] : "" },
                                        set: { v in
                                            while cookieVals.count <= i { cookieVals.append("") }
                                            cookieVals[i] = v
                                        }
                                    ))
                                    .font(.system(size: 12.5))
                                    .foregroundColor(c.fg)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .accentColor(c.accent)
                                    .padding(.vertical, 6)
                                }
                                Text("igneous / star 可以为空（没有就空着）。")
                                    .font(.system(size: 10))
                                    .foregroundColor(c.fg3)
                                    .padding(.top, 6)
                            }
                            if let loginError {
                                NoteBar(text: loginError, tone: .bad, inset: false)
                                    .padding(.horizontal, 18)
                                    .padding(.top, 10)
                            }
                            HStack(spacing: 9) {
                                MiniButton(
                                    text: busy ? "验证中…" : "登录",
                                    accent: true,
                                    enabled: !busy && cookieVals.contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                                ) {
                                    Task { await loginCookies() }
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 18)
                            .padding(.top, 12)
                            Text("Cookie 只存在本机。E-Hentai 校验时会访问它的论坛页，国内需要代理。")
                                .font(.system(size: 10))
                                .foregroundColor(c.fg3)
                                .padding(.horizontal, 18)
                                .padding(.top, 6)
                        } else {
                            groupCard {
                                TextField("账号 / 邮箱", text: $user)
                                    .font(.system(size: 13))
                                    .foregroundColor(c.fg)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .accentColor(c.accent)
                                    .padding(.vertical, 8)
                                HLine()
                                SecureField("密码", text: $pass)
                                    .font(.system(size: 13))
                                    .foregroundColor(c.fg)
                                    .accentColor(c.accent)
                                    .padding(.vertical, 8)
                            }
                            if let loginError {
                                NoteBar(text: loginError, tone: .bad, inset: false)
                                    .padding(.horizontal, 18)
                                    .padding(.top, 10)
                            }
                            HStack(spacing: 9) {
                                MiniButton(
                                    text: busy ? "登录中…" : "登录",
                                    accent: true,
                                    enabled: !busy && !user.isEmpty && !pass.isEmpty
                                ) {
                                    Task { await login() }
                                }
                                if !registerUrl.isEmpty {
                                    MiniButton(text: "去注册") {
                                        if let u = URL(string: registerUrl) { openURL(u) }
                                    }
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 18)
                            .padding(.top, 12)
                            if canWeb && !webUrl.isEmpty {
                                MiniButton(text: "改用网页登录（带滑块）") {
                                    webLogin = true
                                }
                                .padding(.top, 10)
                            }
                            Text("账号密码只存在本机，不会上传。登录后这个源才能搜索和看图。")
                                .font(.system(size: 10))
                                .foregroundColor(c.fg3)
                                .padding(.horizontal, 18)
                                .padding(.top, 6)
                        }
                    } else if canWeb {
                        NoteBar(
                            text: "这个源在网页里登录（有滑块也在网页里拖）。登录后 Cookie 存在本机，之后所有请求自动带上。",
                            tone: .neutral,
                            inset: false
                        )
                        .padding(.horizontal, 18)
                        .padding(.top, 12)
                        if !webUrl.isEmpty {
                            MiniButton(text: "网页登录（带滑块）", accent: true) {
                                webLogin = true
                            }
                            .padding(.horizontal, 18)
                            .padding(.top, 10)
                        }
                    } else {
                        NoteBar(text: "这个源不用登录，直接搜索就行。", tone: .neutral, inset: false)
                            .padding(.horizontal, 18)
                            .padding(.top, 12)
                    }

                    if let loadError {
                        NoteBar(text: loadError, tone: .bad, inset: false)
                            .padding(.horizontal, 18)
                            .padding(.top, 10)
                    }
                }
                .padding(.top, 14)
                .padding(.bottom, 30)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .fullScreenCover(isPresented: $webLogin) {
            WebLoginView(
                key: key,
                url: webUrl,
                sourceName: name,
                onDone: {
                    webLogin = false
                    notice = "登录成功"
                    Task { await refresh() }
                },
                onCancel: { webLogin = false }
            )
        }
        .task { await refresh() }
    }

    @ViewBuilder
    private var infoRows: some View {
        let items = info["items"] as? [[String: Any]] ?? []
        let shown = items.filter {
            let t = $0["title"] as? String ?? ""
            let v = $0["value"] as? String ?? ""
            let a = $0["action"] as? String ?? ""
            return !t.isEmpty && !(v.isEmpty && a.isEmpty)
        }
        if !shown.isEmpty {
            VStack(spacing: 0) {
                ForEach(shown.indices, id: \.self) { i in
                    HStack(alignment: .top, spacing: 8) {
                        Text(shown[i]["title"] as? String ?? "")
                            .font(.system(size: 11))
                            .foregroundColor(c.fg3)
                            .frame(width: 76, alignment: .leading)
                        let v = shown[i]["value"] as? String ?? ""
                        Text(v.isEmpty ? "需在源设置里填（App 暂不支持）" : v)
                            .font(.system(size: 11))
                            .foregroundColor(c.fg2)
                        Spacer(minLength: 0)
                    }
                    .padding(.top, 6)
                }
            }
        }
    }

    @ViewBuilder
    private func groupCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(c.card2)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(c.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.horizontal, 18)
    }

    private func refresh() async {
        let r = await SourceEngine.shared.account(key: key)
        if (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] {
            let saved = d["savedAccount"] as? String ?? ""
            let fields = d["loginFields"] as? [String] ?? []
            await MainActor.run {
                info = d
                loadError = nil
                if !saved.isEmpty && saved != "cookies" && user.isEmpty { user = saved }
                if !fields.isEmpty && cookieVals.count != fields.count {
                    cookieVals = Array(repeating: "", count: fields.count)
                }
                loading = false
            }
        } else {
            let msg = r["error"] as? String ?? "账号信息读取失败"
            await MainActor.run {
                loadError = friendlyError(msg)
                loading = false
            }
        }
    }

    private func login() async {
        await MainActor.run {
            busy = true
            loginError = nil
            notice = nil
        }
        let u = user.trimmingCharacters(in: .whitespaces)
        let r = await SourceEngine.shared.login(key: key, user: u, password: pass)
        let ok = (r["ok"] as? Bool) == true
        let raw = r["error"] as? String ?? "账号或密码不对"
        let code = r["code"] as? String
        await MainActor.run { busy = false }
        if ok {
            await MainActor.run {
                pass = ""
                notice = "登录成功"
            }
            await SourceHub.shared.refreshRows()
            await refresh()
        } else {
            await MainActor.run {
                switch code {
                case "net":
                    loginError = "连不上这个源。先确认网站本身能打开；E-Hentai / 绅士漫画 / PicACG 这类站点国内要开代理。"
                case "forbidden":
                    loginError = "站点拒绝了请求（403），多半是云防护拦了。开或关代理后重试。"
                default:
                    loginError = "登录失败：" + raw
                }
            }
        }
    }

    private func loginCookies() async {
        await MainActor.run {
            busy = true
            loginError = nil
            notice = nil
        }
        let vals = cookieVals.map { $0.trimmingCharacters(in: .whitespaces) }
        let json = (try? JSONSerialization.data(withJSONObject: vals)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        let r = await SourceEngine.shared.login(key: key, user: json, password: "")
        let ok = (r["ok"] as? Bool) == true
        let raw = r["error"] as? String ?? "Cookie 无效"
        await MainActor.run { busy = false }
        if ok {
            await MainActor.run {
                cookieVals = cookieVals.map { _ in "" }
                notice = "登录成功"
            }
            await SourceHub.shared.refreshRows()
            await refresh()
        } else {
            await MainActor.run { loginError = "登录失败：" + raw }
        }
    }

    private func logout() async {
        await MainActor.run { busy = true }
        _ = await SourceEngine.shared.logout(key: key)
        await MainActor.run {
            busy = false
            pass = ""
            notice = "已退出登录"
        }
        await SourceHub.shared.refreshRows()
        await refresh()
    }
}

// ============================================================================== 阅读器

/// 惰性取图的引用信息
struct LazyRef {
    let key: String
    let comicId: String
    let epId: String
    let count: Int
}

struct ReadingPayload: Identifiable {
    let id = UUID()
    let title: String
    let urls: [String]
    var lazy: LazyRef? = nil
    /// 源 key / 作品 id：换话时要重新请求 pages，非惰性源也得有这两个
    var sourceKey: String = ""
    var comicId: String = ""
    /// 整本目录（id，标题）—— 阅读中直接换话，不用退出去重进
    var chapters: [(String, String)] = []
    var chapterIndex: Int = 0
}

/// 在线章节阅读。
///
/// 界面分成两套：本地书用 ReaderView 那套「两行工具栏」，在线用这里自己的一套
/// （一行顶栏 + 底部设置面板），因为在线是网页/条漫，顶上两行会一直压着内容。
struct ReaderSheet: View {
    let payload: ReadingPayload

    var body: some View {
        OnlineReader(payload: payload)
    }
}

struct OnlineReader: View {

    let payload: ReadingPayload

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss

    /// 一页：地址 + 源要求的请求头 + 打乱段数。
    /// 少了请求头，Komiic/NoyACG/绅士漫画 直接 403；少了段数，禁漫整页是错位横条。
    struct PageRef {
        let url: String
        var headers: [String: String] = [:]
        var num: Int = 0
    }

    @State private var pages: [Int: PageRef]
    @State private var fails: [Int: String] = [:]
    @State private var loading: Set<Int> = []
    @State private var current = 0
    @State private var showBar = true
    @State private var showSettings = false

    /// 当前这一话（换话时改）
    @State private var epId: String
    @State private var epTitle: String
    @State private var pageCount: Int
    @State private var isLazy: Bool
    @State private var chapterIndex: Int
    @State private var chapters: [(String, String)]
    @State private var switching = false

    @State private var savedBrightness: CGFloat = UIScreen.main.brightness
    @State private var hideTask: Task<Void, Never>?

    init(payload: ReadingPayload) {
        self.payload = payload
        var seed: [Int: PageRef] = [:]
        for (i, u) in payload.urls.enumerated() where !u.isEmpty { seed[i] = PageRef(url: u) }
        _pages = State(initialValue: seed)
        _epId = State(initialValue: payload.lazy?.epId ?? "")
        _epTitle = State(initialValue: payload.title)
        _pageCount = State(initialValue: payload.lazy?.count ?? payload.urls.count)
        _isLazy = State(initialValue: payload.lazy != nil)
        _chapterIndex = State(initialValue: payload.chapterIndex)
        _chapters = State(initialValue: payload.chapters)
    }

    private var sourceKey: String { payload.lazy?.key ?? payload.sourceKey }
    private var comicId: String { payload.lazy?.comicId ?? payload.comicId }

    private var layout: ReaderLayout { store.prefs.onlineLayout }
    private var spreads: Int { max(1, (pageCount + 1) / 2) }

    /// 页面间距：设置里存的是「视口高度的百分比」
    private var gap: CGFloat {
        UIScreen.main.bounds.height * CGFloat(store.prefs.pageGapPercent) / 100
    }

    private var total: Int { max(pageCount, 0) }

    /// 裁白边的强度；0 = 关（1/2/3 对应 保守/中/激进）
    private var cropLevel: Int {
        store.prefs.autoCropOnline ? store.prefs.cropLevel + 1 : 0
    }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()

            content

            if showBar {
                VStack(spacing: 0) {
                    OnlineTopBar(
                        title: epTitle,
                        pageLabel: total > 0 ? "\(min(current + 1, total)) / \(total) 页" : "0 页",
                        onBack: { dismiss() },
                        onSettings: {
                            hideTask?.cancel()
                            withAnimation(.easeOut(duration: 0.22)) { showSettings = true }
                        }
                    )
                    Spacer(minLength: 0)
                    if needsSeekBar {
                        OnlineSeekBar(current: current, total: total) { target in
                            seek(to: target)
                        }
                    }
                }
                .transition(.opacity)
            }

            if showSettings {
                OnlineSkin.scrim
                    .ignoresSafeArea()
                    .onTapGesture { closePanels() }
                    .transition(.opacity)
            }

            if showSettings {
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    OnlineSettingsPanel(
                        prefs: $store.prefs,
                        chapters: chapters,
                        chapterIndex: chapterIndex,
                        onPickChapter: { switchChapter($0) },
                        onClose: { closePanels() }
                    )
                }
                .ignoresSafeArea(edges: .bottom)
                .transition(.move(edge: .bottom))
            }

            if switching {
                Color.black.opacity(0.4).ignoresSafeArea()
                ProgressView().tint(.white)
            }
        }
        .statusBarHidden(!showBar)
        .onAppear {
            applyScreen()
            scheduleHide()
        }
        .onDisappear {
            hideTask?.cancel()
            UIScreen.main.brightness = savedBrightness
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .onChange(of: store.prefs.brightness) { _ in applyScreen() }
        .onChange(of: store.prefs.autoBrightness) { _ in applyScreen() }
        .onChange(of: store.prefs.keepScreenOn) { _ in applyScreen() }
        .onChange(of: store.prefs.onlineLayout) { newValue in
            // 换阅读器类型时把位置带过去，别又从第 1 页开始
            if newValue == .double { current = (current / 2) * 2 }
        }
    }

    // MARK: - 五种布局

    private var needsSeekBar: Bool {
        layout == .paged || layout == .double
    }

    @ViewBuilder
    private var content: some View {
        switch layout {
        case .webtoon:
            verticalScroll(zoomable: false)
        case .free:
            verticalScroll(zoomable: true)
        case .slide:
            slideScroll
        case .paged:
            pagedTab
        case .double:
            doubleTab
        }
    }

    private func verticalScroll(zoomable: Bool) -> some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(spacing: gap) {
                ForEach(Array(0..<total), id: \.self) { i in
                    let cellView = cell(i, fit: false)
                        .frame(maxWidth: .infinity)
                    if zoomable {
                        ZoomablePage { cellView }
                            .onAppear { current = i }
                    } else {
                        cellView.onAppear { current = i }
                    }
                }
            }
        }
        .ignoresSafeArea()
        .simultaneousGesture(tapGesture)
    }

    private var slideScroll: some View {
        GeometryReader { geo in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: gap) {
                    ForEach(Array(0..<total), id: \.self) { i in
                        cell(i, fit: true)
                            .frame(width: geo.size.width, height: geo.size.height)
                            .onAppear { current = i }
                    }
                }
            }
        }
        .ignoresSafeArea()
        .simultaneousGesture(tapGesture)
    }

    private var pagedTab: some View {
        TabView(selection: pageBinding) {
            ForEach(Array(0..<total), id: \.self) { i in
                cell(i, fit: true)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
        .simultaneousGesture(tapGesture)
    }

    private var doubleTab: some View {
        TabView(selection: spreadBinding) {
            ForEach(Array(0..<spreads), id: \.self) { s in
                HStack(spacing: 0) {
                    cell(s * 2, fit: true)
                    if s * 2 + 1 < total {
                        cell(s * 2 + 1, fit: true)
                    } else {
                        Color.black
                    }
                }
                .tag(s)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
        .simultaneousGesture(tapGesture)
    }

    /// 右到左：把 TabView 的下标反过来映射，图片顺序自然就从右往左
    private var pageBinding: Binding<Int> {
        Binding(
            get: { store.prefs.rightToLeft ? max(total - 1 - current, 0) : current },
            set: { current = store.prefs.rightToLeft ? max(total - 1 - $0, 0) : $0 }
        )
    }

    private var spreadBinding: Binding<Int> {
        Binding(
            get: {
                let s = current / 2
                return store.prefs.rightToLeft ? max(spreads - 1 - s, 0) : s
            },
            set: { value in
                let s = store.prefs.rightToLeft ? max(spreads - 1 - value, 0) : value
                current = s * 2
            }
        )
    }

    // MARK: - 一页

    @ViewBuilder
    private func cell(_ i: Int, fit: Bool) -> some View {
        if let p = pages[i] {
            OnlinePageImage(
                url: p.url,
                index: i,
                headers: p.headers,
                descramble: p.num,
                fit: fit,
                crop: cropLevel
            )
            .task(id: p.url) { prefetch(i) }
        } else if let f = fails[i] {
            failCell(i, f, fit: fit)
        } else {
            ZStack {
                Color.black
                ProgressView().tint(.white.opacity(0.4))
            }
            .frame(maxWidth: .infinity)
            .frame(maxHeight: fit ? .infinity : nil)
            .aspectRatio(fit ? nil : 0.72, contentMode: .fit)
            .task(id: "fetch-\(i)") { await ensure(i) }
        }
    }

    private func failCell(_ i: Int, _ message: String, fit: Bool) -> some View {
        VStack(spacing: 9) {
            Text("第 \(i + 1) 页没取到")
                .font(.system(size: 13))
                .foregroundColor(.white.opacity(0.72))
            Text(message)
                .font(.system(size: 11))
                .foregroundColor(.white.opacity(0.45))
                .lineLimit(2)
                .multilineTextAlignment(.center)
            Button {
                fails.removeValue(forKey: i)
                Task { await ensure(i) }
            } label: {
                Text("点这里重试")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(OnlineSkin.accent)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
        .frame(maxWidth: .infinity)
        .frame(maxHeight: fit ? .infinity : nil)
        .frame(height: fit ? nil : 260)
    }

    // MARK: - 栏体

    private var tapGesture: some Gesture {
        SpatialTapGesture().onEnded { _ in
            guard !showSettings else { return }
            withAnimation(.easeInOut(duration: 0.16)) { showBar.toggle() }
            scheduleHide()
        }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        guard showBar else { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if Task.isCancelled { return }
            await MainActor.run {
                guard !showSettings else { return }
                withAnimation(.easeInOut(duration: 0.2)) { showBar = false }
            }
        }
    }

    private func closePanels() {
        withAnimation(.easeInOut(duration: 0.2)) {
            showSettings = false
            showBar = true
        }
        scheduleHide()
    }

    private func seek(to target: Int) {
        current = min(max(target, 0), max(total - 1, 0))
    }

    // MARK: - 亮度 / 常亮

    private func applyScreen() {
        UIApplication.shared.isIdleTimerDisabled = store.prefs.keepScreenOn
        if store.prefs.autoBrightness {
            UIScreen.main.brightness = savedBrightness
        } else {
            UIScreen.main.brightness = min(max(CGFloat(store.prefs.brightness / 100), 0.02), 1)
        }
    }

    // MARK: - 换话

    private func switchChapter(_ index: Int) {
        guard index >= 0, index < chapters.count, index != chapterIndex else { return }
        let item = chapters[index]
        chapterIndex = index
        epId = item.0
        epTitle = item.1
        pages = [:]
        fails = [:]
        loading = []
        current = 0
        showSettings = false
        showBar = true
        scheduleHide()

        guard !sourceKey.isEmpty, !comicId.isEmpty else { return }
        switching = true
        Task {
            let r = await SourceEngine.shared.pages(key: sourceKey, comicId: comicId, epId: item.0)
            await MainActor.run {
                switching = false
                guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
                    fails[0] = r["error"] as? String ?? "这一话打不开"
                    pageCount = 0
                    return
                }
                if (d["lazy"] as? Bool) == true {
                    isLazy = true
                    pageCount = d["count"] as? Int ?? 0
                } else {
                    isLazy = false
                    let urls = d["images"] as? [String] ?? []
                    var seed: [Int: PageRef] = [:]
                    for (i, u) in urls.enumerated() where !u.isEmpty { seed[i] = PageRef(url: u) }
                    pages = seed
                    pageCount = urls.count
                }
            }
        }
    }

    // MARK: - 惰性取图

    @MainActor
    private func ensure(_ i: Int) async {
        guard i >= 0, i < pageCount else { return }
        guard pages[i] == nil, fails[i] == nil, !loading.contains(i) else { return }
        guard isLazy, !epId.isEmpty else { return }
        loading.insert(i)
        let r = await SourceEngine.shared.pageImage(
            key: sourceKey,
            comicId: comicId,
            epId: epId,
            index: i
        )
        loading.remove(i)
        if (r["ok"] as? Bool) == true,
           let d = r["data"] as? [String: Any],
           let u = d["url"] as? String,
           !u.isEmpty {
            var p = PageRef(url: u)
            if let h = d["headers"] as? [String: Any] {
                var m: [String: String] = [:]
                for (k, v) in h { if let s = v as? String, !s.isEmpty { m[k] = s } }
                p.headers = m
            }
            if let ds = d["descramble"] as? [String: Any], let n = ds["num"] as? Int {
                p.num = n
            }
            pages[i] = p
        } else {
            fails[i] = r["error"] as? String ?? "取图失败"
        }
    }

    /// 后面两页顺手解出来，并且把图先下好 —— 往下划的时候直接命中缓存
    private func prefetch(_ i: Int) {
        guard isLazy else { return }
        for k in 1...2 {
            let next = i + k
            guard next < total, pages[next] == nil, fails[next] == nil, !loading.contains(next) else {
                continue
            }
            Task { @MainActor in
                await ensure(next)
                guard let p = pages[next] else { return }
                let width = UIScreen.main.bounds.width * 3
                _ = await RemoteImageLoader.shared.image(
                    p.url, headers: p.headers, num: p.num, maxPixel: width, crop: cropLevel
                )
            }
        }
    }
}

/// 「自由放大」：整页双指缩放 1–4 倍，竖向滚动照常（用 simultaneousGesture，不抢滚动）。
private struct ZoomablePage<Content: View>: View {
    @ViewBuilder var content: () -> Content

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        content()
            .scaleEffect(scale)
            .simultaneousGesture(
                MagnificationGesture()
                    .onChanged { value in
                        scale = min(max(lastScale * value, 1), 4)
                    }
                    .onEnded { _ in
                        lastScale = scale
                        if scale <= 1.01 {
                            scale = 1
                            lastScale = 1
                        }
                    }
            )
    }
}
