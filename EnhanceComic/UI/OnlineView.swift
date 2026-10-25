import SwiftUI

/// 在线源首页：列出所有源和当前线路（直连 / 需 VPN）
struct OnlineSourceView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var sources: [[String: Any]] = []
    @State private var routes: [String: [String: Any]] = [:]
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if loading && sources.isEmpty {
                    ProgressView("正在启动引擎…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        Section {
                            ForEach(sources.indices, id: \.self) { i in
                                let s = sources[i]
                                let key = s["key"] as? String ?? ""
                                let route = routes[key]
                                NavigationLink {
                                    BrowseView(key: key, title: s["name"] as? String ?? key)
                                } label: {
                                    SourceCell(
                                        name: s["name"] as? String ?? key,
                                        host: route?["host"] as? String ?? "",
                                        line: route?["line"] as? String ?? ""
                                    )
                                }
                            }
                        } footer: {
                            Text("能用直连的走直连；标「需 VPN」的来源在打开代理后自动可用，切换不用重启。")
                        }
                    }
                }
            }
            .navigationTitle("在线漫画源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { dismiss() }
                }
            }
            .task { await bootstrap() }
        }
    }

    private func bootstrap() async {
        _ = await SourceEngine.shared.start()
        await reload()
        loading = false
        await SourceEngine.shared.probeRoutes()
        await reload()
    }

    private func reload() async {
        let srcs = await SourceEngine.shared.sources()
        let st = await SourceEngine.shared.routeStatus()
        var map: [String: [String: Any]] = [:]
        for r in st {
            if let k = r["key"] as? String { map[k] = r }
        }
        await MainActor.run {
            sources = srcs
            routes = map
        }
    }
}

private struct SourceCell: View {
    let name: String
    let host: String
    let line: String

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(Color.accentColor.opacity(0.15))
                Text(String(name.prefix(1))).font(.system(size: 17, weight: .bold)).foregroundStyle(.tint)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.system(size: 15, weight: .medium))
                Text(host.isEmpty ? "未检测" : host)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if !line.isEmpty {
                let direct = line == "direct"
                Text(direct ? "直连" : "需 VPN")
                    .font(.system(size: 11))
                    .foregroundStyle(direct ? Color.green : Color.orange)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(
                        (direct ? Color.green : Color.orange).opacity(0.12),
                        in: RoundedRectangle(cornerRadius: 6)
                    )
            }
        }
        .padding(.vertical, 2)
    }
}

// MARK: - 搜索 / 探索

struct BrowseView: View {
    let key: String
    let title: String

    @State private var keyword = ""
    @State private var comics: [ComicItem] = []
    @State private var busy = false
    @State private var error: String?
    @State private var didLoad = false

    private let columns = [GridItem(.adaptive(minimum: 100), spacing: 10)]

    var body: some View {
        ScrollView {
            if busy {
                ProgressView().padding(24)
            }
            if let error {
                Text(error).font(.system(size: 12)).foregroundStyle(.red).padding(.horizontal)
            }
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(comics) { c in
                    NavigationLink {
                        DetailView(sourceKey: key, comicId: c.id, fallbackTitle: c.title)
                    } label: {
                        ComicCell(item: c)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(12)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $keyword, prompt: "搜索关键词")
        .onSubmit(of: .search) { Task { await search(keyword) } }
        .task {
            if didLoad { return }
            didLoad = true
            await explore()
        }
    }

    private func explore() async {
        busy = true
        let r = await SourceEngine.shared.explore(key: key)
        busy = false
        if (r["ok"] as? Bool) == true, let data = r["data"] as? [String: Any] {
            comics = ComicItem.parseExplore(data)
        }
    }

    private func search(_ kw: String) async {
        guard !kw.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        busy = true; error = nil
        let r = await SourceEngine.shared.search(key: key, keyword: kw, options: "[]", page: 1)
        busy = false
        if (r["ok"] as? Bool) == true, let data = r["data"] as? [String: Any] {
            comics = ComicItem.parseSearch(data)
            if comics.isEmpty { error = "没有结果" }
        } else {
            error = r["error"] as? String ?? "搜索失败"
        }
    }
}

private struct ComicCell: View {
    let item: ComicItem
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Color.clear
                .aspectRatio(0.72, contentMode: .fit)
                .overlay {
                    AsyncImage(url: URL(string: item.cover)) { phase in
                        if let img = phase.image {
                            img.resizable().scaledToFill()
                        } else {
                            Rectangle().fill(Color.secondary.opacity(0.12))
                                .overlay(ProgressView().scaleEffect(0.6))
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
            Text(item.title)
                .font(.system(size: 11))
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
    }
}

// MARK: - 详情

struct DetailView: View {
    let sourceKey: String
    let comicId: String
    let fallbackTitle: String

    @State private var info: [String: Any] = [:]
    @State private var groups: [(title: String, items: [(id: String, title: String)])] = []
    @State private var busy = true
    @State private var error: String?
    @State private var reading: ReadingPayload?

    var body: some View {
        Group {
            if busy {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error {
                Text(error).foregroundStyle(.red).padding()
            } else {
                List {
                    Section {
                        HStack(alignment: .top, spacing: 12) {
                            AsyncImage(url: URL(string: info["cover"] as? String ?? "")) { phase in
                                if let img = phase.image {
                                    img.resizable().scaledToFill()
                                } else {
                                    Rectangle().fill(Color.secondary.opacity(0.12))
                                }
                            }
                            .frame(width: 92, height: 128)
                            .clipShape(RoundedRectangle(cornerRadius: 8))

                            VStack(alignment: .leading, spacing: 6) {
                                Text(info["title"] as? String ?? fallbackTitle)
                                    .font(.system(size: 15, weight: .semibold))
                                Text((info["description"] as? String ?? "")
                                    .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression))
                                    .font(.system(size: 11))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(6)
                            }
                        }
                    }
                    ForEach(groups.indices, id: \.self) { gi in
                        Section(groups[gi].title) {
                            ForEach(groups[gi].items.indices, id: \.self) { ci in
                                let ch = groups[gi].items[ci]
                                Button {
                                    Task { await open(ch.id, ch.title) }
                                } label: {
                                    Text(ch.title).font(.system(size: 13))
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(info["title"] as? String ?? fallbackTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .sheet(item: $reading) { payload in
            ReaderSheet(payload: payload)
        }
    }

    private func load() async {
        busy = true
        let r = await SourceEngine.shared.detail(key: sourceKey, id: comicId)
        busy = false
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            error = r["error"] as? String ?? "加载失败"
            return
        }
        info = d
        var gs: [(String, [(String, String)])] = []
        for g in (d["groups"] as? [[String: Any]] ?? []) {
            let t = g["title"] as? String ?? ""
            let items = (g["items"] as? [[String: Any]] ?? []).compactMap { o -> (String, String)? in
                guard let id = o["id"] as? String else { return nil }
                return (id, o["title"] as? String ?? id)
            }
            gs.append((t, items))
        }
        groups = gs
    }

    private func open(_ epId: String, _ epTitle: String) async {
        busy = true
        let r = await SourceEngine.shared.pages(key: sourceKey, comicId: comicId, epId: epId)
        busy = false
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any],
              let urls = d["images"] as? [String], !urls.isEmpty else {
            error = r["error"] as? String ?? "这一话没有图片（可能需要登录）"
            return
        }
        reading = ReadingPayload(title: epTitle, urls: urls)
    }
}

struct ReadingPayload: Identifiable {
    let id = UUID()
    let title: String
    let urls: [String]
}

/// 在线章节阅读：竖向连续，跟本地阅读器的习惯一致
struct ReaderSheet: View {
    let payload: ReadingPayload
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0
    @State private var showBar = true

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(payload.urls.indices, id: \.self) { i in
                        AsyncImage(url: URL(string: payload.urls[i])) { phase in
                            if let img = phase.image {
                                img.resizable().scaledToFit()
                            } else {
                                Color.black.frame(height: 360)
                                    .overlay(ProgressView().tint(.white))
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .ignoresSafeArea()

            if showBar {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left").foregroundStyle(.white)
                    }
                    Text(payload.title).foregroundStyle(.white).font(.system(size: 13)).lineLimit(1)
                    Spacer()
                    Text("\(payload.urls.count) 页").foregroundStyle(.white.opacity(0.7)).font(.system(size: 12))
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.black.opacity(0.55))
            }
        }
        .statusBarHidden()
        .onTapGesture { showBar.toggle() }
    }
}

// MARK: - 数据

struct ComicItem: Identifiable {
    let id: String
    let title: String
    let cover: String

    static func parseSearch(_ d: [String: Any]) -> [ComicItem] {
        ((d["comics"] as? [[String: Any]]) ?? []).compactMap {
            guard let id = $0["id"] as? String else { return nil }
            return ComicItem(id: id, title: $0["title"] as? String ?? "", cover: $0["cover"] as? String ?? "")
        }
    }

    /// 探索页返回 {分区: [comic]}，取第一个非空分区
    static func parseExplore(_ d: [String: Any]) -> [ComicItem] {
        for (_, v) in d {
            if let arr = v as? [[String: Any]], !arr.isEmpty {
                return arr.compactMap {
                    guard let id = $0["id"] as? String else { return nil }
                    return ComicItem(id: id, title: $0["title"] as? String ?? "", cover: $0["cover"] as? String ?? "")
                }
            }
        }
        return []
    }
}
