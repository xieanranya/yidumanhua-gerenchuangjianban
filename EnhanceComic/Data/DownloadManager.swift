import Foundation
import UIKit

/// 漫画离线下载。
///
/// 一本书 = 一个任务 = 书库 `Downloads/<key>/` 下的一个目录（章节子目录/页图），
/// 落盘后直接注册成本地 `Book`（type .folder），书架/阅读器全部复用现有链路。
///
/// 调度：
///   - 同时最多 5 本（队列排队），每本内章节串行，章内页图并行 3 路——不限速，
///     快慢完全由网络质量决定；
///   - 断点：章节内按「页文件是否存在」续传，任务队列整体落盘 tasks.json，
///     杀进程重启后自动续跑；
///   - 暂停 = 取消 runner + 状态落盘；继续 = 重启 runner（已完成章节/页直接跳过）。
@MainActor
final class DownloadManager: ObservableObject {

    static let shared = DownloadManager()
    static let maxConcurrentBooks = 5
    static let pagesParallel = 3

    @Published private(set) var tasks: [DownloadTask] = []

    private var runners: [String: Task<Void, Never>] = [:]
    private weak var store: LibraryStore?
    private var dir: URL?
    private var tasksURL: URL? { dir?.appendingPathComponent("tasks.json") }

    private init() {}

    // MARK: - 任务模型

    struct DownloadTask: Identifiable, Codable, Equatable {
        var id: String                 // 目录名（源 key + 作品 id 清洗）
        var sourceKey: String
        var sourceName: String
        var comicId: String
        var title: String
        var cover: String
        /// 0 排队 / 1 下载中 / 2 暂停 / 3 完成 / 4 部分章节失败
        var status: Int
        var chapters: [DownloadChapter]
        var createdAt: Date = Date()

        var doneChapters: Int { chapters.filter { $0.status == 2 }.count }
        var totalPages: Int { chapters.reduce(0) { $0 + max($1.total, 0) } }
        var donePages: Int { chapters.reduce(0) { $0 + max($1.done, 0) } }
        var progress: Double { totalPages > 0 ? Double(donePages) / Double(totalPages) : 0 }
    }

    struct DownloadChapter: Codable, Equatable {
        var id: String
        var title: String
        var index: Int
        var dir: String                // 书目录下的章节子目录名
        var total: Int = 0             // 拿到页列表后填
        var done: Int = 0
        /// 0 待下 / 1 下载中 / 2 完成 / 3 失败
        var status: Int = 0
    }

    // MARK: - 接入

    func attach(_ store: LibraryStore) {
        guard dir == nil else { return }
        self.store = store
        let d = store.libraryDir.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        dir = d
        load()
        // 启动续传：上次退出时在跑/排队的任务重新排队（文件级断点由下载时兜底）
        var changed = false
        for i in tasks.indices where tasks[i].status == 0 || tasks[i].status == 1 {
            tasks[i].status = 0
            for c in tasks[i].chapters.indices where tasks[i].chapters[c].status == 1 {
                tasks[i].chapters[c].status = 0
            }
            changed = true
        }
        if changed { save() }
        pump()
    }

    // MARK: - 对外操作

    /// 入队一本（已存在则：暂停/失败 → 继续，其余不动）。
    func start(sourceKey: String, sourceName: String, comicId: String, title: String, cover: String, chapters: [(id: String, title: String)]) {
        guard dir != nil, !chapters.isEmpty else { return }
        let key = Self.folderKey(sourceKey: sourceKey, comicId: comicId)
        if let i = tasks.firstIndex(where: { $0.id == key }) {
            if tasks[i].status == 2 || tasks[i].status == 4 { toggle(key) }
            return
        }
        var chs: [DownloadChapter] = []
        for (i, c) in chapters.enumerated() {
            chs.append(DownloadChapter(
                id: c.id, title: c.title, index: i,
                dir: Self.chapterDirName(index: i, title: c.title)
            ))
        }
        let task = DownloadTask(
            id: key, sourceKey: sourceKey, sourceName: sourceName,
            comicId: comicId, title: title, cover: cover,
            status: 0, chapters: chs
        )
        tasks.append(task)
        try? FileManager.default.createDirectory(at: bookDir(task), withIntermediateDirectories: true)
        save()
        pump()
    }

    /// 暂停 ⇄ 继续（完成态无效）。
    func toggle(_ id: String) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        switch tasks[i].status {
        case 0, 1:
            tasks[i].status = 2
            for c in tasks[i].chapters.indices where tasks[i].chapters[c].status == 1 {
                tasks[i].chapters[c].status = 0
            }
            save()
            runners[id]?.cancel()
            runners[id] = nil
        case 2, 4:
            // 继续时把失败章节也重置为待下（页文件级断点保证已下的不重下）
            for c in tasks[i].chapters.indices where tasks[i].chapters[c].status == 3 {
                tasks[i].chapters[c].status = 0
            }
            tasks[i].status = 0
            save()
            pump()
        default:
            break
        }
    }

    /// 删除任务 + 删书目录（含书架上的本地书条目）。
    func remove(_ id: String) {
        runners[id]?.cancel()
        runners[id] = nil
        tasks.removeAll { $0.id == id }
        save()
        if let store {
            if let book = store.books.first(where: { $0.type == .folder && $0.relativePath == Self.relativePath(id) }) {
                store.delete(book)     // 会连目录一起删
                return
            }
        }
        if let dir {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(id, isDirectory: true))
        }
    }

    // MARK: - 调度

    private func pump() {
        guard dir != nil else { return }
        var slots = Self.maxConcurrentBooks - runners.count
        guard slots > 0 else { return }
        var started = false
        for i in tasks.indices {
            if slots <= 0 { break }
            guard tasks[i].status == 0, runners[tasks[i].id] == nil else { continue }
            tasks[i].status = 1
            let id = tasks[i].id
            runners[id] = Task { await runBook(id: id) }
            slots -= 1
            started = true
        }
        if started { save() }
    }

    private func runBook(id: String) async {
        defer {
            runners[id] = nil
            pump()
        }
        guard let ti0 = tasks.firstIndex(where: { $0.id == id }) else { return }
        let chapterCount = tasks[ti0].chapters.count
        for ci in 0..<chapterCount {
            if Task.isCancelled { return }
            // 章节等待期间任务可能被删除/重建 —— 每轮重取索引
            guard let ti = tasks.firstIndex(where: { $0.id == id }) else { return }
            guard ci < tasks[ti].chapters.count else { return }
            if tasks[ti].chapters[ci].status == 2 { continue }   // 已完成
            await runChapter(id: id, ci: ci)
            if Task.isCancelled { return }
            registerBook(id: id)
            save()
        }
        // 收尾：全完成 → 3；有失败 → 4
        guard !Task.isCancelled, let ti2 = tasks.firstIndex(where: { $0.id == id }) else { return }
        let allDone = tasks[ti2].chapters.allSatisfy { $0.status == 2 }
        tasks[ti2].status = allDone ? 3 : 4
        registerBook(id: id)
        save()
    }

    private func runChapter(id: String, ci: Int) async {
        guard let ti0 = tasks.firstIndex(where: { $0.id == id }), let dir else { return }
        let ch = tasks[ti0].chapters[ci]
        tasks[ti0].chapters[ci].status = 1

        // 1) 取页列表（惰性源只回页数，真地址逐页解析；非惰性直接给 URL 串数组）
        let r = await SourceEngine.shared.pages(
            key: tasks[ti0].sourceKey, comicId: tasks[ti0].comicId, epId: ch.id
        )
        // 等待期间任务可能被删 —— 重取索引
        guard let ti = tasks.firstIndex(where: { $0.id == id }) else { return }
        guard ci < tasks[ti].chapters.count else { return }
        guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else {
            tasks[ti].chapters[ci].status = 3
            return
        }
        let lazy = (d["lazy"] as? Bool) == true
        var urls: [String?] = []
        if lazy {
            let n = d["count"] as? Int ?? 0
            guard n > 0 else {
                tasks[ti].chapters[ci].status = 3
                return
            }
            urls = Array(repeating: nil, count: n)
        } else {
            let list = d["images"] as? [String] ?? []
            guard !list.isEmpty else {
                tasks[ti].chapters[ci].status = 3
                return
            }
            urls = list
        }
        let total = urls.count
        tasks[ti].chapters[ci].total = total

        let chDir = bookDir(tasks[ti]).appendingPathComponent(ch.dir, isDirectory: true)
        try? FileManager.default.createDirectory(at: chDir, withIntermediateDirectories: true)
        // 子任务里不能读 MainActor 状态 —— 先把不变量摘成局部值
        let sk = tasks[ti].sourceKey
        let cid = tasks[ti].comicId
        let epId = ch.id

        // 2) 逐页下载：先解析（惰性），再落盘；缺页才下（文件级断点），窗口 3 并行
        var failed = 0
        var done = (0..<total).filter { Self.hasPage(chDir, $0) }.count
        tasks[ti].chapters[ci].done = done

        var index = 0
        while index < total {
            if Task.isCancelled { return }
            // 组一个 3 页的窗口（跳过已有文件的页）
            var window: [Int] = []
            while index < total && window.count < Self.pagesParallel {
                if !Self.hasPage(chDir, index) { window.append(index) }
                index += 1
            }
            guard !window.isEmpty else { continue }
            let results = await withTaskGroup(of: Bool.self) { group -> Int in
                for i in window {
                    group.addTask {
                        await Self.fetchOnePage(
                            sourceKey: sk, comicId: cid, epId: epId,
                            pageIndex: i, lazy: lazy, rawUrl: urls[i],
                            dest: chDir.appendingPathComponent(Self.pageFileName(i))
                        )
                    }
                }
                var ok = 0
                for await okFlag in group where okFlag { ok += 1 }
                return ok
            }
            done += results
            failed += window.count - results
            guard let tix = tasks.firstIndex(where: { $0.id == id }) else { return }
            tasks[tix].chapters[ci].done = done
            save()
        }

        guard let tix2 = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[tix2].chapters[ci].done = total - failed
        tasks[tix2].chapters[ci].status = failed == 0 ? 2 : 3
    }

    /// 解析 + 下载 + 还原 + 落盘一页。纯函数（nonisolated），跑在协作线程池上。
    nonisolated private static func fetchOnePage(
        sourceKey: String, comicId: String, epId: String, pageIndex: Int,
        lazy: Bool, rawUrl: String?, dest: URL
    ) async -> Bool {
        if FileManager.default.fileExists(atPath: dest.path) { return true }
        var url = rawUrl ?? ""
        var headers: [String: String] = [:]
        var num = 0
        if lazy {
            let r = await SourceEngine.shared.pageImage(key: sourceKey, comicId: comicId, epId: epId, index: pageIndex)
            guard (r["ok"] as? Bool) == true, let d = r["data"] as? [String: Any] else { return false }
            guard let u = d["url"] as? String, !u.isEmpty else { return false }
            url = u
            headers = d["headers"] as? [String: String] ?? [:]
            if let ds = d["descramble"] as? [String: Any] { num = ds["num"] as? Int ?? 0 }
        }
        guard url.hasPrefix("http") else { return false }
        return await Self.savePage(url: url, headers: headers, num: num, dest: dest)
    }

    /// 下载字节（镜像 RemoteImageLoader.fetch 的请求头语义）；num≤1 存原始字节，
    /// num>1 解码 → 乱序还原 → JPEG 重编码。
    nonisolated private static func savePage(url: String, headers: [String: String], num: Int, dest: URL) async -> Bool {
        guard let u = URL(string: url) else { return false }
        let ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        var req = URLRequest(url: u)
        req.timeoutInterval = 60
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
        var hasReferer = false
        for (k, v) in headers where !v.isEmpty {
            let lk = k.lowercased()
            if lk == "accept-encoding" || lk == "connection" || lk == "host" || lk == "content-length" { continue }
            if lk == "referer" { hasReferer = true }
            req.setValue(v, forHTTPHeaderField: k)
        }
        if !hasReferer {
            req.setValue("\(u.scheme ?? "https")://\(u.host ?? "")/", forHTTPHeaderField: "Referer")
        }

        var data: Data?
        for _ in 0..<3 {
            do {
                let (d, resp) = try await URLSession.shared.data(for: req)
                if let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode), !d.isEmpty {
                    data = d
                    break
                }
            } catch { }
            try? await Task.sleep(nanoseconds: 800_000_000)
        }
        guard var bytes = data, !bytes.isEmpty else { return false }

        if num > 1 {
            guard let img = UIImage(data: bytes),
                  let straight = RemoteImageLoader.descramble(img, num: num),
                  let jpg = straight.jpegData(compressionQuality: 0.92) else { return false }
            bytes = jpg
        }
        return (try? bytes.write(to: dest, options: .atomic)) != nil
    }

    // MARK: - 本地书注册

    private func registerBook(id: String) {
        guard let store else { return }
        guard let ti = tasks.firstIndex(where: { $0.id == id }) else { return }
        let donePages = tasks[ti].chapters.reduce(0) { $0 + max($1.done, 0) }
        guard donePages > 0 else { return }
        let rel = Self.relativePath(id)
        if let idx = store.books.firstIndex(where: { $0.type == .folder && $0.relativePath == rel }) {
            if store.books[idx].pageCount != donePages {
                var b = store.books[idx]
                b.pageCount = donePages
                store.update(b)
            }
            return
        }
        var book = Book(title: tasks[ti].title, type: .folder, relativePath: rel, pageCount: donePages)
        if let name = LibraryStore.cover(for: book, libraryDir: store.libraryDir) {
            book.coverName = name
        }
        store.insert(book)
    }

    // MARK: - 持久化

    private func load() {
        guard let tasksURL else { return }
        if let data = try? Data(contentsOf: tasksURL),
           let list = try? JSONDecoder().decode([DownloadTask].self, from: data) {
            tasks = list
        }
    }

    private func save() {
        guard let tasksURL,
              let data = try? JSONEncoder().encode(tasks) else { return }
        try? data.write(to: tasksURL, options: .atomic)
    }

    // MARK: - 路径 / 命名

    private func bookDir(_ task: DownloadTask) -> URL {
        dir!.appendingPathComponent(task.id, isDirectory: true)
    }

    static func relativePath(_ id: String) -> String { "Downloads/\(id)" }

    static func folderKey(sourceKey: String, comicId: String) -> String {
        let clean = comicId.map { c in (c.isLetter || c.isNumber) ? c : "_" }
        var s = String(clean).replacingOccurrences(of: "_", with: "_")
        s = sourceKey + "_" + s
        // 过长的 id 截断（保留尾部，避免全目录同名）
        if s.count > 96 { s = String(s.suffix(96)) }
        return s.isEmpty ? "book" : s
    }

    static func chapterDirName(index: Int, title: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|")
        let clean = title.unicodeScalars.map { bad.contains($0) ? "_" : Character($0) }
        var name = String(clean).trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { name = "章节" }
        if name.count > 40 { name = String(name.prefix(40)) }
        return String(format: "%04d", index + 1) + " - " + name
    }

    static func pageFileName(_ index: Int) -> String {
        String(format: "%05d.jpg", index + 1)
    }

    static func hasPage(_ chDir: URL, _ index: Int) -> Bool {
        FileManager.default.fileExists(atPath: chDir.appendingPathComponent(pageFileName(index)).path)
    }
}
