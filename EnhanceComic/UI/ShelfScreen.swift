import SwiftUI
import UniformTypeIdentifiers

/// 书架：本地书库。原来 LibraryView 的那套导入/删除/进度全部保留，只换了外壳。
struct ShelfScreen: View {

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.appColors) private var c
    @Environment(\.scenePhase) private var scenePhase

    @State private var picker: PickerKind?
    @State private var importing = false
    @State private var importNote = ""
    @State private var openBook: Book?
    @State private var pendingDelete: Book?
    @State private var problem: String?
    @State private var message: String?
    @State private var filter = 0          // 0 全部 / 1 在读 / 2 未读

    private var reading: Book? {
        store.books
            .filter { $0.lastPage > 0 }
            .max { ($0.lastReadAt ?? $0.addedAt) < ($1.lastReadAt ?? $1.addedAt) }
    }

    private var shown: [Book] {
        switch filter {
        case 1: return store.books.filter { $0.lastPage > 0 }
        case 2: return store.books.filter { $0.lastPage == 0 }
        default: return store.books
        }
    }

    private var columns: [GridItem] {
        let n = c.dark ? 3 : 2
        return Array(repeating: GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9), count: n)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BigTitle(text: "书架", sub: subline, inset: false)
                    .padding(.horizontal, 18)

                actions
                    .padding(.horizontal, 18)

                if importing {
                    importingCard
                        .padding(.horizontal, 18)
                        .padding(.top, 10)
                }

                filterChips
                    .padding(.top, 10)

                if let reading {
                    SectionHeader(title: "继续阅读", inset: false)
                        .padding(.horizontal, 18)
                    ContinueCard(
                        title: reading.title,
                        meta: "第 \(reading.lastPage + 1) / \(reading.pageCount) 页",
                        label: CoverPalette.label(for: reading),
                        coverURL: nil,
                        localCover: CoverCache.image(at: store.coverURL(for: reading)),
                        seed: reading.title.hashValue,
                        inset: false
                    )
                    .padding(.horizontal, 18)
                    .onTapGesture { openBook = reading }
                }

                if store.books.isEmpty {
                    if !importing {
                        emptyState
                    }
                } else if shown.isEmpty {
                    EmptyHint(title: "这个筛选下没有书", message: "切回「全部」看看。", glyph: .shelf)
                } else {
                    SectionHeader(
                        title: filter == 0 ? "本地漫画" : "筛选结果",
                        more: "\(shown.count) 本",
                        inset: false
                    )
                    .padding(.horizontal, 18)
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                        ForEach(shown) { book in
                            bookCard(book)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 26)
                }
            }
            .padding(.top, 4)
        }
        .background(c.bg)
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
                        .font(.system(size: 12.5))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color.black.opacity(0.88))
                        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                        .padding(.horizontal, 24)
                }
            }
            .padding(.bottom, 18)
        }
        .fullScreenCover(item: $openBook) { book in
            ReaderView(book: book).environmentObject(store)
        }
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

    private var subline: String {
        let head = store.books.isEmpty ? "本地书库还是空的" : "本地书库 · \(store.books.count) 本"
        return head + " · " + appVersionText()
    }

    private var actions: some View {
        HStack(spacing: 8) {
            MiniButton(text: "导入漫画", accent: true) {
                problem = nil
                picker = .files
            }
            MiniButton(text: "导入文件夹") {
                problem = nil
                picker = .folder
            }
            Spacer(minLength: 0)
        }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                Chip(text: "全部 \(store.books.count)", selected: filter == 0) { filter = 0 }
                Chip(text: "在读 \(store.books.filter { $0.lastPage > 0 }.count)", selected: filter == 1) { filter = 1 }
                Chip(text: "未读 \(store.books.filter { $0.lastPage == 0 }.count)", selected: filter == 2) { filter = 2 }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 8)
        }
    }

    private var importingCard: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(importNote.isEmpty ? "正在导入，请稍候…" : importNote)
                .font(.system(size: 11.5))
                .foregroundColor(c.fg2)
                .lineLimit(2)
            ProgressView().progressViewStyle(.linear).tint(c.accent)
        }
        .padding(13)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.card2)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    /// 常驻的错误条。必须手动关掉，不能自动消失 —— 3.6 秒的 toast 在被选择器
    /// 盖住时等于没有，用户只会看到「点了没反应」。
    private func problemBanner(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                GlyphView(glyph: .spark, tint: c.bad, size: 13, strokeWidth: 1.8)
                Text("导入未完成")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundColor(c.fg)
                Spacer(minLength: 6)
                Button {
                    withAnimation { problem = nil }
                } label: {
                    GlyphView(glyph: .more, tint: c.fg3, size: 12, strokeWidth: 1.8)
                        .rotationEffect(.degrees(45))
                        .padding(6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Text(text)
                .font(.system(size: 11.5))
                .foregroundColor(c.fg2)
                .fixedSize(horizontal: false, vertical: true)
            Text("原文件还在原处，没有被动过。")
                .font(.system(size: 10))
                .foregroundColor(c.fg3)
        }
        .padding(13)
        .background(c.card2)
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(c.bad.opacity(0.4), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .padding(.horizontal, 18)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            GlyphView(glyph: .shelf, tint: c.fg3, size: 38, strokeWidth: 1.3)
            Text("书架还是空的")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(c.fg)
            Text("支持 ZIP / CBZ / RAR / CBR / 7Z / CB7 / TAR / PDF，\n也可以直接导入一个装满图片的文件夹。")
                .font(.system(size: 11.5))
                .foregroundColor(c.fg3)
                .multilineTextAlignment(.center)
            VStack(spacing: 3) {
                Text("导入没反应的话")
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundColor(c.fg2)
                Text("打开「文件」App → 我的 iPhone → 画质漫画，\n把压缩包放进去，回到这里会自动收进书架。")
                    .font(.system(size: 10.5))
                    .foregroundColor(c.fg3)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 30)
        .padding(.top, 54)
        .padding(.bottom, 40)
    }

    private func bookCard(_ book: Book) -> some View {
        let progress = book.pageCount > 0
            ? min(1, Double(book.lastPage) / Double(book.pageCount))
            : 0
        return VStack(alignment: .leading, spacing: 5) {
            ZStack(alignment: .bottom) {
                ZStack {
                    coverGradient(seed: book.title.hashValue, dark: c.dark)
                    if let cover = CoverCache.image(at: store.coverURL(for: book)) {
                        Image(uiImage: cover).resizable().scaledToFill()
                    } else {
                        Text(String(book.title.prefix(1)))
                            .font(.system(size: c.dark ? 26 : 34, weight: .bold))
                            .foregroundColor(placeholderInk(c.dark))
                    }
                }
                .aspectRatio(0.72, contentMode: .fit)
                .clipped()

                if progress > 0 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Color.clear
                            Rectangle()
                                .fill(c.accent)
                                .frame(width: geo.size.width * CGFloat(progress), height: 2.5)
                        }
                    }
                    .frame(height: 2.5)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .stroke(c.border, lineWidth: 0.5)
            )
            .overlay(alignment: .bottomTrailing) {
                Text(CoverPalette.label(for: book))
                    .font(.system(size: 8.5, weight: .semibold))
                    .foregroundColor(c.fg)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1.5)
                    .background(c.bg.opacity(0.76))
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .padding(5)
            }

            Text(book.title)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundColor(c.fg)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(book.lastPage > 0
                 ? "\(book.pageCount) 页 · 读到 \(book.lastPage + 1)"
                 : "\(book.pageCount) 页")
                .font(.system(size: 9.5))
                .foregroundColor(c.fg3)
                .lineLimit(1)
        }
        .contentShape(Rectangle())
        .onTapGesture { openBook = book }
        .onLongPressGesture { pendingDelete = book }
    }

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
