import SwiftUI

/// 下载中心（「我的」页入口）：集中管理全部下载任务 —— 进度 / 状态 / 暂停继续 / 删除，
/// 已完成的书提供导出（整本 CBZ / 单张 JPG·PNG·WebP）。
struct DownloadCenterView: View {

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.appColors) private var c
    @ObservedObject private var dm = DownloadManager.shared

    /// 面板用枚举驱动（同一 View 挂多个 sheet 在老系统会互顶）
    private enum CenterSheet: Identifiable {
        case export(DownloadManager.DownloadTask)
        case share(ShareItem)
        var id: String {
            switch self {
            case .export(let t): return "export-\(t.id)"
            case .share(let s): return "share-\(s.id)"
            }
        }
    }
    struct ShareItem: Identifiable {
        let id = UUID()
        let url: URL
        let cleanup: (() -> Void)?
    }

    @State private var sheet: CenterSheet?
    @State private var pendingDelete: DownloadManager.DownloadTask?
    @State private var openBook: Book?

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "下载管理",
                subtitle: dm.tasks.isEmpty ? nil : "\(dm.tasks.count) 个任务",
                onBack: { dismiss() },
                trailing: nil
            )
            HLine()

            if dm.tasks.isEmpty {
                EmptyHint(
                    title: "还没有下载任务",
                    message: "在在线漫画的详情页点「下载全本」即可离线阅读。",
                    glyph: .down
                )
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(dm.tasks) { task in
                            row(task)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 10)
                    .padding(.bottom, 26)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .fullScreenCover(item: $openBook) { book in
            ReaderView(book: book).environmentObject(store)
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .export(let task):
                DownloadExportView(task: task, onShare: { item in
                    sheet = .share(item)
                })
                .environment(\.appColors, c)
            case .share(let item):
                ShareSheet(items: [item.url])
                    .onDisappear { item.cleanup?() }
            }
        }
        .confirmationDialog(
            "删除下载任务",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除（含已下载文件）", role: .destructive) {
                if let t = pendingDelete { dm.remove(t.id) }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text(pendingDelete.map { "《\($0.title)》的全部离线文件会一并删除。" } ?? "")
        }
    }

    // MARK: - 任务行

    @ViewBuilder
    private func row(_ task: DownloadManager.DownloadTask) -> some View {
        let book = store.books.first {
            $0.type == .folder && $0.relativePath == DownloadManager.relativePath(task.id)
        }
        Button {
            if let book { openBook = book }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(coverGradient(seed: task.title.hashValue, dark: c.dark))
                    if let book, let cover = CoverCache.image(at: store.coverURL(for: book)) {
                        Image(uiImage: cover).resizable().scaledToFill()
                    } else if !task.cover.isEmpty {
                        RemoteImage(url: task.cover, contentMode: .fill, maxPixel: 240)
                    } else {
                        Text(String(task.title.prefix(1)))
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(placeholderInk(c.dark))
                    }
                }
                .frame(width: 52, height: 70)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(c.border, lineWidth: 0.5)
                )

                VStack(alignment: .leading, spacing: 4) {
                    Text(task.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(c.fg)
                        .lineLimit(1)
                    Text("\(task.sourceName) · \(statusText(task))")
                        .font(.system(size: 10))
                        .foregroundColor(c.fg3)
                        .lineLimit(1)
                    if task.status == 0 || task.status == 1 || task.status == 2 {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Rectangle().fill(c.border)
                                Rectangle()
                                    .fill(c.accent)
                                    .frame(width: geo.size.width * CGFloat(max(0.02, task.progress)))
                            }
                        }
                        .frame(height: 3)
                        .clipShape(Capsule())
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if task.status == 3 {
                    Button {
                        sheet = .export(task)
                    } label: {
                        Text("导出")
                            .font(.system(size: 10.5, weight: .semibold))
                            .foregroundColor(c.accent)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 5)
                            .background(c.accent.opacity(0.12))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                if book != nil {
                    Text("阅读")
                        .font(.system(size: 10.5, weight: .semibold))
                        .foregroundColor(c.fg2)
                }
                actionButton(task)
                deleteButton(task)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(c.card)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(c.border, lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func actionButton(_ task: DownloadManager.DownloadTask) -> some View {
        switch task.status {
        case 0, 1:
            capsuleButton("暂停", c.fg2) { dm.toggle(task.id) }
        case 2, 4:
            capsuleButton("继续", c.fg2) { dm.toggle(task.id) }
        default:
            EmptyView()
        }
    }

    private func deleteButton(_ task: DownloadManager.DownloadTask) -> some View {
        capsuleButton("删除", c.fg3) { pendingDelete = task }
    }

    private func capsuleButton(_ text: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundColor(color)
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(c.card.opacity(0.9))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(c.border, lineWidth: 0.5))
        }
        .buttonStyle(.plain)
    }

    private func statusText(_ t: DownloadManager.DownloadTask) -> String {
        let ch = t.chapters.first { $0.status == 1 }
        let tail = ch.map { " · \($0.title)" } ?? ""
        switch t.status {
        case 0: return "排队中 · \(t.doneChapters)/\(t.chapters.count) 话\(tail)"
        case 1: return "下载中 \(Int(t.progress * 100))% · \(t.donePages)/\(t.totalPages) 页\(tail)"
        case 2: return "已暂停 · \(t.doneChapters)/\(t.chapters.count) 话"
        case 3: return "已下载 · \(t.chapters.count) 话 · \(t.totalPages) 页"
        default: return "部分章节失败 · 点继续重试"
        }
    }
}

// ================================================================================== 导出面板

/// 单本导出面板：整本 CBZ 打包 / 单张页图（JPG / PNG / WebP）。
struct DownloadExportView: View {

    let task: DownloadManager.DownloadTask
    var onShare: (DownloadCenterView.ShareItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appColors) private var c

    @State private var page = 1               // 1 起
    @State private var fmt: ExportKit.PageFormat = .jpg
    @State private var busy = false
    @State private var note: String?

    private var totalPages: Int {
        max(task.chapters.reduce(0) { $0 + max($1.total, 0) }, 1)
    }

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: "导出 · \(task.title)",
                subtitle: statusLine,
                onBack: { dismiss() },
                trailing: nil
            )
            HLine()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionHeader(title: "整本打包", inset: false)
                        .padding(.horizontal, 18)
                    Button {
                        exportCBZ()
                    } label: {
                        HStack(spacing: 8) {
                            if busy {
                                ProgressView().tint(c.accent).scaleEffect(0.8)
                            }
                            Text("导出 CBZ · \(task.chapters.count) 话 · \(task.donePages) 页")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(c.accent)
                            Spacer(minLength: 0)
                            GlyphView(glyph: .right, tint: c.fg3, size: 13, strokeWidth: 1.6)
                        }
                        .padding(.horizontal, 15)
                        .padding(.vertical, 13)
                        .background(c.card2)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(c.border, lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(busy)
                    .padding(.horizontal, 18)
                    Text("CBZ = 标准漫画压缩包，可直接导入本 App 或任意漫画阅读器。")
                        .font(.system(size: 10))
                        .foregroundColor(c.fg3)
                        .padding(.horizontal, 20)
                        .padding(.top, 6)

                    SectionHeader(title: "单张导出", inset: false)
                        .padding(.horizontal, 18)
                        .padding(.top, 10)

                    HStack(spacing: 10) {
                        Button {
                            if page > 1 { page -= 1 }
                        } label: {
                            Text("−")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(c.fg)
                                .frame(width: 34, height: 34)
                                .background(c.chip)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        Text("第 \(page) / \(totalPages) 页")
                            .font(.system(size: 12.5, design: .monospaced))
                            .foregroundColor(c.fg)
                        Button {
                            if page < totalPages { page += 1 }
                        } label: {
                            Text("＋")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(c.fg)
                                .frame(width: 34, height: 34)
                                .background(c.chip)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 6)

                    HStack(spacing: 7) {
                        ForEach(ExportKit.PageFormat.allCases) { f in
                            Chip(text: f.label, selected: fmt == f) { fmt = f }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 4)

                    Button {
                        exportPage()
                    } label: {
                        Text("导出第 \(page) 页（\(fmt.label)）")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Capsule().fill(c.accent))
                    }
                    .buttonStyle(.plain)
                    .disabled(busy)
                    .padding(.horizontal, 18)
                    .padding(.top, 8)

                    if let note {
                        Text(note)
                            .font(.system(size: 10.5))
                            .foregroundColor(c.fg3)
                            .padding(.horizontal, 20)
                            .padding(.top, 8)
                    }
                    Text("导出后走系统分享：可「存储到文件」、存入相册或 AirDrop。")
                        .font(.system(size: 10))
                        .foregroundColor(c.fg3)
                        .padding(.horizontal, 20)
                        .padding(.top, 6)
                        .padding(.bottom, 30)
                }
                .padding(.top, 4)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private var statusLine: String {
        "共 \(task.chapters.count) 话 · 已下 \(task.donePages) 页"
    }

    // MARK: - 动作

    private func exportCBZ() {
        guard let bookDir = DownloadManager.shared.bookDirectory(task.id) else {
            note = "找不到下载目录"
            return
        }
        busy = true
        note = nil
        let entries: [(name: String, file: URL)] = task.chapters.flatMap { ch in
            (0..<max(ch.total, 0)).compactMap { p in
                let f = bookDir
                    .appendingPathComponent(ch.dir, isDirectory: true)
                    .appendingPathComponent(DownloadManager.pageFileName(p))
                return FileManager.default.fileExists(atPath: f.path)
                    ? (name: "\(ch.dir)/\(DownloadManager.pageFileName(p))", file: f)
                    : nil
            }
        }
        let safe = sanitize(task.title)
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(safe).cbz")
        Task.detached(priority: .userInitiated) {
            do {
                try? FileManager.default.removeItem(at: dest)
                try ExportKit.exportCBZ(entries: entries, dest: dest)
                await MainActor.run {
                    busy = false
                    onShare(DownloadCenterView.ShareItem(
                        url: dest,
                        cleanup: { try? FileManager.default.removeItem(at: dest) }
                    ))
                }
            } catch {
                await MainActor.run {
                    busy = false
                    note = "导出失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func exportPage() {
        guard let bookDir = DownloadManager.shared.bookDirectory(task.id) else {
            note = "找不到下载目录"
            return
        }
        guard let file = ExportKit.pageFileURL(
            chapters: task.chapters, bookDir: bookDir, globalIndex: page - 1
        ) else {
            note = "第 \(page) 页还没下载（可能属于失败章节）"
            return
        }
        busy = true
        note = nil
        Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: file),
                  let img = UIImage(data: data),
                  let out = ExportKit.encodePage(img, format: fmt) else {
                await MainActor.run {
                    busy = false
                    note = "页面解码失败"
                }
                return
            }
            let safe = sanitize(task.title)
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("\(safe)_p\(String(format: "%05d", page)).\(fmt.ext)")
            do {
                try? FileManager.default.removeItem(at: dest)
                try out.write(to: dest, options: .atomic)
                await MainActor.run {
                    busy = false
                    onShare(DownloadCenterView.ShareItem(
                        url: dest,
                        cleanup: { try? FileManager.default.removeItem(at: dest) }
                    ))
                }
            } catch {
                await MainActor.run {
                    busy = false
                    note = "写入失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func sanitize(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|")
        let clean = s.unicodeScalars.map { bad.contains($0) ? "_" : Character($0) }
        var name = String(clean).trimmingCharacters(in: .whitespacesAndNewlines)
        if name.count > 50 { name = String(name.prefix(50)) }
        return name.isEmpty ? "export" : name
    }
}
