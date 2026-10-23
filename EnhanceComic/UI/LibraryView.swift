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
        case .pdf: return "PDF"
        case .folder: return "文件夹"
        }
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
}

struct LibraryView: View {

    @EnvironmentObject private var store: LibraryStore

    @State private var showFilePicker = false
    @State private var showFolderPicker = false
    @State private var importing = false
    @State private var openBook: Book?
    @State private var pendingDelete: Book?
    @State private var message: String?

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
        .task { await store.backfillCovers() }
        .overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.system(size: 13))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.82))
                    .clipShape(Capsule())
                    .padding(.bottom, 26)
                    .transition(.opacity)
            }
        }
        .fullScreenCover(item: $openBook) { book in
            ReaderView(book: book).environmentObject(store)
        }
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.zip, .pdf],
            allowsMultipleSelection: true
        ) { result in
            guard case let .success(urls) = result, !urls.isEmpty else { return }
            importing = true
            Task { @MainActor in
                let outcome = await store.importDocuments(urls)
                importing = false
                if outcome.failed > 0 {
                    showMessage("有 \(outcome.failed) 个文件无法识别")
                }
            }
        }
        .fileImporter(
            isPresented: $showFolderPicker,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            guard case let .success(urls) = result, let url = urls.first else { return }
            importing = true
            Task { @MainActor in
                let book = await store.importFolder(url)
                importing = false
                if book == nil { showMessage("这个文件夹里没有可用的图片") }
            }
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
                    ? "本地书本 · GPU 画质增强"
                    : "本地书库 · 共 \(store.books.count) 本 · GPU 画质增强"
            )
            .font(.system(size: 12))
            .foregroundColor(.secondary)
        }
        .padding(.top, 4)
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                showFilePicker = true
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
                showFolderPicker = true
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
            Spacer(minLength: 0)
        }
    }

    private var importingCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("正在导入，请稍候…")
                .font(.system(size: 12))
                .foregroundColor(.secondary)
            ProgressView().progressViewStyle(.linear)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
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
            Text("支持 ZIP / CBZ / PDF，\n也可以直接导入一个装满图片的文件夹。")
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
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
                    Text(CoverPalette.typeLabel(book.type))
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

    private func showMessage(_ text: String) {
        withAnimation { message = text }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            withAnimation { message = nil }
        }
    }
}
