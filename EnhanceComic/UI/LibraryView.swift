import SwiftUI
import UniformTypeIdentifiers

enum CoverPalette {
    static let colors: [Color] = [
        Color(red: 0.33, green: 0.29, blue: 0.72),
        Color(red: 0.11, green: 0.62, blue: 0.46),
        Color(red: 0.73, green: 0.46, blue: 0.09),
        Color(red: 0.60, green: 0.21, blue: 0.34),
        Color(red: 0.11, green: 0.31, blue: 0.85),
        Color(red: 0.49, green: 0.18, blue: 0.07),
    ]

    static func color(for id: UUID) -> Color {
        var accumulator = 0
        for scalar in id.uuidString.unicodeScalars {
            accumulator = (accumulator &* 31 &+ Int(scalar.value)) & 0x7FFF_FFFF
        }
        return colors[accumulator % colors.count]
    }

    static func typeLabel(_ type: BookType) -> String {
        switch type {
        case .zip: return "ZIP"
        case .rar: return "RAR"
        case .pdf: return "PDF"
        case .folder: return "文件夹"
        }
    }

    /// 7z / tar 解包成文件夹入库，书架上仍标原容器名，免得用户以为格式变了。
    static func label(for book: Book) -> String {
        book.sourceLabel.isEmpty ? typeLabel(book.type) : book.sourceLabel
    }
}

/// Tiny in-memory cache so scrolling the shelf does not re-decode cover JPEGs.
enum CoverCache {
    private static var storage: [String: UIImage] = [:]

    static func image(at url: URL?) -> UIImage? {
        guard let url else { return nil }
        if let hit = storage[url.path] { return hit }
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        storage[url.path] = image
        return image
    }

    static func invalidate(_ url: URL) {
        storage.removeValue(forKey: url.path)
    }
}

struct LibraryView: View {

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.scenePhase) private var scenePhase

    @State private var picker: PickerKind?
    @State private var importing = false
    @State private var importNote = ""
    @State private var openBook: Book?
    @State private var pendingDelete: Book?
    @State private var message: String?
    /// 导入失败会一直留在屏幕上，直到用户手动关掉。旧版 3.6 秒就消失，
    /// 而且经常被文件选择器盖住，看起来就像「什么都没发生」。
    @State private var problem: String?

    private let columns = [GridItem(.adaptive(minimum: 148), spacing: 12)]

    private var reading: Book? {
        store.books.filter { $0.lastPage > 0 }.max { $0.addedAt < $1.addedAt }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                actions
                if importing { importingCard }
                if let reading { continueCard(reading) }
                if store.books.isEmpty {
                    if !importing { emptyState }
                } else {
                    Text("全部漫画")
                        .font(.system(size: 13, weight: .semibold))
                        .padding(.top, 6)
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(store.books) { book in
                            bookCard(book)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 30)
        }
        .background(Color(uiColor: .systemBackground))
        .task {
            await store.backfillCovers()
            // 文件共享目录里躺着压缩包就顺手收进书库。这条路径不经过选择器，
            // 万一选择器在某些系统版本上还是不灵，用户从「文件」App 拖进来也能用。
            await store.sweepDocumentsFolder()
        }
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            Task { await store.sweepDocumentsFolder() }
        }
        .overlay(alignment: .bottom) {
            VStack(spacing: 8) {
                if let problem {
                    problemBanner(problem)
                } else if let message {
                    Text(message)
                        .font(.system(size: 13))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(Color.black.opacity(0.86))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.horizontal, 24)
                        .transition(.opacity)
                }
            }
            .padding(.bottom, 26)
        }
        .fullScreenCover(item: $openBook) { book in
            ReaderView(book: book).environmentObject(store)
        }
        .fullScreenCover(isPresented: $showOnline) {
            OnlineSourceView()
        }
        // 一个 sheet 槽驱动两种选择器。之前两个 `.fileImporter` 挂在同一个 View 上会抢
        // 唯一的 presentation slot，「导入漫画」那条被顶掉，点了完全没反应。
        //
        // 现在换成自己包的 UIDocumentPicker：文件走 `asCopy: true`，系统先把 iCloud
        // 里的内容下载完、复制到临时目录再给我们，不再出现「拿到云盘占位符 → 复制失败
        // → 静默」这条死路。
        .sheet(item: $picker) { kind in
            DocumentPicker(
                kind: kind,
                onPick: { urls in
                    picker = nil
                    guard !urls.isEmpty else {
                        problem = "没有拿到文件。如果它还在 iCloud 里，请先在「文件」App 里下载到本机再导入。"
                        return
                    }
                    runImport(urls)
                },
                onCancel: { picker = nil }
            )
            .ignoresSafeArea()
        }
        .confirmationDialog(
            "删除这本书？",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let book = pendingDelete { store.delete(book) }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("会同时删除导入时复制到应用内的文件，手机里的原始文件不受影响。")
        }
    }

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("画质漫画")
                .font(.system(size: 28, weight: .bold))
            Text(
                store.books.isEmpty
                    ? "本地书本 · 画质增强"
                    : "本地书库 · 共 \(store.books.count) 本 · 画质增强"
            )
            .font(.system(size: 12))
            .foregroundColor(.secondary)
            Text(versionText)
                .font(.system(size: 10.5))
                .foregroundColor(.secondary.opacity(0.7))
        }
        .padding(.top, 4)
    }

    private var versionText: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "v\(version) (\(build))"
    }

    @State private var showOnline = false

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                problem = nil
                picker = .files
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "plus")
                    Text("导入漫画")
                }
                .font(.system(size: 14, weight: .medium))
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .background(CoverPalette.colors[0])
                .foregroundColor(.white)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }

            Button {
                problem = nil
                picker = .folder
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "folder")
                    Text("导入文件夹")
                }
                .font(.system(size: 14, weight: .medium))
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.secondary.opacity(0.35), lineWidth: 1)
                )
            }
            Button {
                showOnline = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "globe")
                    Text("在线源")
                }
                .font(.system(size: 14, weight: .medium))
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.secondary.opacity(0.35), lineWidth: 1)
                )
            }
            Spacer(minLength: 0)
        }
    }

    private var importingCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(importNote.isEmpty ? "正在导入，请稍候…" : importNote)
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .lineLimit(2)
            ProgressView().progressViewStyle(.linear)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    /// 常驻的错误条。必须手动关掉，不能自动消失 —— 3.6 秒的 toast 在被选择器
    /// 盖住时等于没有，用户只会看到「点了没反应」。
    private func problemBanner(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                Text("导入未完成")
                    .font(.system(size: 13, weight: .semibold))
                Spacer(minLength: 6)
                Button {
                    withAnimation { problem = nil }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .padding(6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Text(text)
                .font(.system(size: 12))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Text("原文件还在原处，没有被动过。")
                .font(.system(size: 10.5))
                .opacity(0.72)
        }
        .foregroundColor(.white)
        .padding(14)
        .background(Color(red: 0.46, green: 0.17, blue: 0.17))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 18)
        .transition(.opacity)
    }

    private func continueCard(_ book: Book) -> some View {
        let progress = book.pageCount > 0
            ? min(1, Double(book.lastPage) / Double(book.pageCount))
            : 0
        return Button {
            openBook = book
        } label: {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(CoverPalette.color(for: book.id))
                    .frame(width: 46, height: 62)
                    .overlay {
                        if let cover = CoverCache.image(at: store.coverURL(for: book)) {
                            Image(uiImage: cover).resizable().scaledToFill().clipped()
                        } else {
                            Text(String(book.title.prefix(1)))
                                .font(.system(size: 19, weight: .semibold))
                                .foregroundColor(.white)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text("继续阅读")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(CoverPalette.colors[0])
                    Text(book.title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    ProgressView(value: progress)
                        .tint(CoverPalette.colors[0])
                        .padding(.top, 4)
                    Text("第 \(book.lastPage + 1) / \(book.pageCount) 页")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(CoverPalette.colors[0].opacity(0.12))
                    .frame(width: 84, height: 84)
                Image(systemName: "book.closed")
                    .font(.system(size: 32, weight: .light))
                    .foregroundColor(CoverPalette.colors[0])
            }
            Text("书架还是空的")
                .font(.system(size: 16, weight: .semibold))
            Text("支持 ZIP / CBZ / RAR / CBR / 7Z / CB7 / TAR / PDF，\n也可以直接导入一个装满图片的文件夹。")
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            // 备用入口：不经过文件选择器，一定能走通。
            VStack(spacing: 3) {
                Text("导入没反应的话")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(.secondary)
                Text("打开「文件」App → 我的 iPhone → 画质漫画，\n把压缩包放进去，回到这里会自动收进书架。")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary.opacity(0.8))
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
        .padding(.bottom, 40)
    }

    private func bookCard(_ book: Book) -> some View {
        let progress = book.pageCount > 0
            ? min(1, Double(book.lastPage) / Double(book.pageCount))
            : 0
        return VStack(alignment: .leading, spacing: 0) {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(CoverPalette.color(for: book.id))
                .aspectRatio(0.72, contentMode: .fit)
                .overlay {
                    ZStack {
                        if let cover = CoverCache.image(at: store.coverURL(for: book)) {
                            Image(uiImage: cover).resizable().scaledToFill()
                        } else {
                            Text(String(book.title.prefix(1)))
                                .font(.system(size: 40, weight: .bold))
                                .foregroundColor(.white.opacity(0.92))
                        }
                    }
                    .clipped()
                }
                .overlay(alignment: .bottomTrailing) {
                    Text(CoverPalette.label(for: book))
                        .font(.system(size: 9, weight: .medium))
                        .foregroundColor(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.32))
                        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                        .padding(7)
                }
                .overlay(alignment: .bottom) {
                    if progress > 0 {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Color.white.opacity(0.25)
                                Color.white.frame(width: geo.size.width * CGFloat(progress))
                            }
                        }
                        .frame(height: 3)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

            Text(book.title)
                .font(.system(size: 13, weight: .medium))
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .padding(.top, 7)

            Text(
                book.lastPage > 0
                    ? "\(book.pageCount) 页 · 读到 \(book.lastPage + 1)"
                    : "\(book.pageCount) 页"
            )
            .font(.system(size: 11))
            .foregroundColor(.secondary)
            .padding(.top, 2)
        }
        .contentShape(Rectangle())
        .onTapGesture { openBook = book }
        .onLongPressGesture { pendingDelete = book }
    }

    /// Runs the import and always reports an outcome. Silence was the worst part of the old
    /// behaviour: a file that got skipped just vanished from the run with no explanation.
    private func runImport(_ urls: [URL]) {
        problem = nil
        importing = true
        importNote = urls.count == 1
            ? "正在导入「\(urls[0].lastPathComponent)」…"
            : "正在导入 \(urls.count) 个文件…"
        Task { @MainActor in
            let outcome = await store.importAny(urls)
            importing = false
            importNote = ""
            if !outcome.problems.isEmpty {
                problem = outcome.problems.prefix(3).joined(separator: "\n")
            }
            if outcome.added > 0 {
                showMessage(outcome.problems.isEmpty
                    ? "已导入 \(outcome.added) 本"
                    : "已导入 \(outcome.added) 本，其余见下方提示")
            }
        }
    }

    private func showMessage(_ text: String) {
        withAnimation { message = text }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            withAnimation { message = nil }
        }
    }
}
