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
                error = "搜索超时，线路可能不通。"
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
        .sheet(item: $reading) { payload in
            ReaderSheet(payload: payload)
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
                guard let id = o["id"] as? String, !id.isEmpty else { continue }
                flat.append(
                    EpRef(
                        id: id,
                        title: o["title"] as? String ?? id,
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
                    )
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
            reading = ReadingPayload(title: ep.title, urls: urls)
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
                                Text(savedAccount.isEmpty ? "（该源未回传账号名）" : savedAccount)
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
                            Text("账号密码只存在本机，不会上传。登录后这个源才能搜索和看图。")
                                .font(.system(size: 10))
                                .foregroundColor(c.fg3)
                                .padding(.horizontal, 18)
                                .padding(.top, 6)
                        }
                    } else if canWeb {
                        NoteBar(
                            text: "这个源不支持账号密码，登录是在网页里完成的（靠 Cookie）。公开内容不登录也能看。",
                            tone: .neutral,
                            inset: false
                        )
                        .padding(.horizontal, 18)
                        .padding(.top, 12)
                        if !webUrl.isEmpty {
                            MiniButton(text: "打开登录网页", accent: true) {
                                if let u = URL(string: webUrl) { openURL(u) }
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
            await MainActor.run {
                info = d
                loadError = nil
                if !saved.isEmpty && user.isEmpty { user = saved }
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
}

/// 在线章节阅读：竖向无缝连续（跟本地阅读器一个习惯）。
///
/// 页图走 RemoteImageLoader（带 UA / 15s 超时 / 解码缓存），取不到会直接说哪一页没取到，
/// 而不是留一个永远转的菊花 —— 之前的版本就是「黑屏两个菊花、还特别卡」。
struct ReaderSheet: View {
    let payload: ReadingPayload

    var body: some View {
        OnlineReader(payload: payload)
    }
}

struct OnlineReader: View {

    let payload: ReadingPayload

    @Environment(\.dismiss) private var dismiss

    @State private var urls: [Int: String]
    @State private var fails: [Int: String] = [:]
    @State private var loading: Set<Int> = []
    @State private var current = 0
    @State private var showBar = true

    init(payload: ReadingPayload) {
        self.payload = payload
        var seed: [Int: String] = [:]
        for (i, u) in payload.urls.enumerated() where !u.isEmpty { seed[i] = u }
        _urls = State(initialValue: seed)
    }

    private var total: Int { payload.lazy?.count ?? payload.urls.count }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()

            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(spacing: 0) {
                    ForEach(0..<max(total, 0), id: \.self) { i in
                        cell(i)
                            .frame(maxWidth: .infinity)
                            .onAppear { if current != i { current = i } }
                    }
                }
            }
            .ignoresSafeArea()
            .simultaneousGesture(
                SpatialTapGesture().onEnded { _ in toggleBar() }
            )

            if showBar { bar }
        }
        .statusBarHidden(!showBar)
    }

    // MARK: 一页

    @ViewBuilder
    private func cell(_ i: Int) -> some View {
        if let u = urls[i] {
            OnlinePageImage(url: u, index: i)
                .task(id: u) { prefetch(i) }
        } else if let f = fails[i] {
            failCell(i, f)
        } else {
            Color.black
                .aspectRatio(0.72, contentMode: .fit)
                .overlay(ProgressView().tint(.white.opacity(0.4)))
                .task(id: "fetch-\(i)") { await ensure(i) }
        }
    }

    private func failCell(_ i: Int, _ message: String) -> some View {
        VStack(spacing: 9) {
            Text("第 \(i + 1) 页没取到：\(message)")
                .font(.system(size: 12.5))
                .multilineTextAlignment(.center)
                .foregroundColor(.white.opacity(0.75))
            Button {
                fails[i] = nil
                Task { await ensure(i) }
            } label: {
                Text("点这里重试")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Color(red: 1, green: 0.43, blue: 0.61))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity)
        .frame(height: 220)
    }

    private var bar: some View {
        HStack(spacing: 8) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Text(payload.title)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(.white)
                .lineLimit(1)

            Spacer(minLength: 0)

            Text(total > 0 ? "\(min(current + 1, total)) / \(total) 页" : "0 页")
                .font(.system(size: 12))
                .foregroundColor(.white.opacity(0.7))
        }
        .padding(.horizontal, 10)
        .padding(.top, 6)
        .padding(.bottom, 8)
        .background(Color.black.opacity(0.55))
    }

    private func toggleBar() {
        withAnimation(.easeInOut(duration: 0.16)) { showBar.toggle() }
    }

    // MARK: 惰性取图

    @MainActor
    private func ensure(_ i: Int) async {
        guard i >= 0, i < total else { return }
        guard urls[i] == nil, fails[i] == nil, !loading.contains(i) else { return }
        guard let lz = payload.lazy else { return }
        loading.insert(i)
        let r = await SourceEngine.shared.pageImage(
            key: lz.key,
            comicId: lz.comicId,
            epId: lz.epId,
            index: i
        )
        loading.remove(i)
        if (r["ok"] as? Bool) == true,
           let d = r["data"] as? [String: Any],
           let u = d["url"] as? String,
           !u.isEmpty {
            urls[i] = u
        } else {
            fails[i] = r["error"] as? String ?? "取图失败"
        }
    }

    /// 下一张顺手解出来，快速往下划的时候不至于每页都等
    private func prefetch(_ i: Int) {
        guard payload.lazy != nil else { return }
        let next = i + 1
        guard next < total, urls[next] == nil, fails[next] == nil, !loading.contains(next) else {
            return
        }
        Task { await ensure(next) }
    }
}
