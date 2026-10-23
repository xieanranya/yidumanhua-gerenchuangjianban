import Foundation
import CoreGraphics
import SwiftUI
import UIKit

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var books: [Book] = []
    @Published var prefs: ReaderPrefs = ReaderPrefs() {
        didSet { persist(prefs, key: Keys.prefs) }
    }
    @Published var enhance: EnhanceSettings = EnhanceSettings() {
        didSet { persist(enhance, key: Keys.enhance) }
    }

    let libraryDir: URL
    private let stateURL: URL

    struct ImportResult {
        var added: Int
        /// One human-readable line per file that could not be imported.
        var problems: [String]
    }

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        libraryDir = documents.appendingPathComponent("Library", isDirectory: true)
        stateURL = documents.appendingPathComponent("library.json")
        try? FileManager.default.createDirectory(
            at: libraryDir,
            withIntermediateDirectories: true
        )
        load()
    }

    // MARK: - Session

    func session(for book: Book) -> BookSession? {
        BookSession(book: book, libraryDir: libraryDir)
    }

    // MARK: - Covers

    /// Folder that holds a book's copied files.
    private func folder(for book: Book) -> URL {
        if book.type == .folder {
            return libraryDir.appendingPathComponent(book.relativePath, isDirectory: true)
        }
        return libraryDir.appendingPathComponent(book.relativePath).deletingLastPathComponent()
    }

    func coverURL(for book: Book) -> URL? {
        guard let name = book.coverName else { return nil }
        let url = folder(for: book).appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Renders page 1 into a small JPEG so the shelf shows real artwork.
    ///
    /// `nonisolated` on purpose: importing copies and decodes off the main actor, and a
    /// cover is part of that same pass. Touching anything `@MainActor` here would force the
    /// whole import back onto the main thread, which is what made the UI freeze before.
    nonisolated static func cover(for book: Book, libraryDir: URL) -> String? {
        guard let session = BookSession(book: book, libraryDir: libraryDir),
              let page = session.image(at: 0, maxWidth: 480),
              let data = UIImage(cgImage: page).jpegData(compressionQuality: 0.86)
        else { return nil }
        let dir = book.type == .folder
            ? libraryDir.appendingPathComponent(book.relativePath, isDirectory: true)
            : libraryDir.appendingPathComponent(book.relativePath).deletingLastPathComponent()
        let url = dir.appendingPathComponent("cover.jpg")
        guard (try? data.write(to: url, options: .atomic)) != nil else { return nil }
        return "cover.jpg"
    }

    /// Fills in covers for books imported by an earlier build.
    func backfillCovers() async {
        let missing = books.filter { $0.coverName == nil }
        guard !missing.isEmpty else { return }
        let dir = libraryDir

        let generated: [(UUID, String)] = await Task.detached(priority: .utility) {
            var out: [(UUID, String)] = []
            for book in missing {
                if let name = LibraryStore.cover(for: book, libraryDir: dir) {
                    out.append((book.id, name))
                }
            }
            return out
        }.value

        guard !generated.isEmpty else { return }
        for (id, name) in generated {
            if let index = books.firstIndex(where: { $0.id == id }) {
                books[index].coverName = name
            }
        }
        save()
    }

    /// Replaces the shelf cover with an arbitrary page of the book.
    @discardableResult
    func setCover(_ book: Book, page: Int) -> Bool {
        guard let session = session(for: book),
              page >= 0,
              page < session.pageCount,
              let image = session.image(at: page, maxWidth: 600),
              let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.86)
        else { return false }

        let name = "cover-page.jpg"
        let url = folder(for: book).appendingPathComponent(name)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return false }
        CoverCache.invalidate(url)
        guard var updated = books.first(where: { $0.id == book.id }) else { return false }
        updated.coverName = name
        update(updated)
        return true
    }

    // MARK: - Import

    /// One import run. Files and folders can arrive mixed; each item is sniffed on its own.
    ///
    /// The copy / count / cover pass runs on a detached task. It used to run inline on the
    /// main actor, so a few-hundred-MB zip blocked the UI for seconds and the "importing"
    /// card never even got a chance to draw — which reads exactly like "点了没反应".
    ///
    /// - Parameter removeSource: 删掉导入成功的源文件。只有扫描 App 自己的 Documents 目录时
    ///   才开，否则用户拖进来的包会被原地删掉。
    func importAny(_ urls: [URL], removeSource: Bool = false) async -> ImportResult {
        var scoped: [URL] = []
        for url in urls where url.startAccessingSecurityScopedResource() {
            scoped.append(url)
        }
        defer {
            for url in scoped { url.stopAccessingSecurityScopedResource() }
        }

        let dir = libraryDir
        let outcome = await Task.detached(priority: .userInitiated) {
            () -> ([Book], [String], [URL]) in
            var made: [Book] = []
            var problems: [String] = []
            var consumed: [URL] = []
            for url in urls {
                let result = LibraryStore.buildBook(from: url, libraryDir: dir)
                if let book = result.book {
                    made.append(book)
                    consumed.append(url)
                } else {
                    problems.append(result.reason)
                }
            }
            // 文档选择器 `asCopy: true` 给的是临时副本，导入完不管成败都得清掉，
            // 不然每导一次就在 tmp 里堆一份几百 MB 的压缩包。
            for url in urls where LibraryStore.isTemporaryPick(url) {
                try? FileManager.default.removeItem(at: url)
            }
            if removeSource {
                for url in consumed where !LibraryStore.isTemporaryPick(url) {
                    try? FileManager.default.removeItem(at: url)
                }
            }
            return (made, problems, consumed)
        }.value

        if !outcome.0.isEmpty {
            books.append(contentsOf: outcome.0)
            save()
        }
        return ImportResult(added: outcome.0.count, problems: outcome.1)
    }

    /// `true` when the URL lives in the app's temporary directory, i.e. it is a copy the
    /// document picker made for us and the original file is still wherever the user keeps it.
    nonisolated static func isTemporaryPick(_ url: URL) -> Bool {
        let tmp = FileManager.default.temporaryDirectory.standardizedFileURL.path
        return url.standardizedFileURL.path.hasPrefix(tmp)
    }

    /// 兜底入口：把压缩包直接放进 App 的文档目录（文件 App → 我的 iPhone → 画质漫画，
    /// 或电脑上用访达 / iTunes 拖进去），启动时自动收进书库。
    ///
    /// 这条路径完全不经过文件选择器，所以不会受选择器那堆毛病影响。
    func sweepDocumentsFolder() async {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        guard let items = try? FileManager.default.contentsOfDirectory(
            at: documents,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles, .skipsSubdirectoryDescendants]
        ) else { return }

        let candidates = items.filter { url in
            let isDir = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            guard !isDir else { return false }
            return ["zip", "cbz", "pdf", "rar"].contains(url.pathExtension.lowercased())
        }
        guard !candidates.isEmpty else { return }

        _ = await importAny(candidates, removeSource: true)
    }

    private struct BuildResult {
        var book: Book?
        var reason: String
    }

    private enum Sniffed {
        case zip
        case pdf
        case rar
        case unknown
    }

    /// Copies one picked item into the library, counts its pages and renders a cover.
    nonisolated private static func buildBook(from url: URL, libraryDir: URL) -> BuildResult {
        let name = url.lastPathComponent
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
            return BuildResult(
                book: nil,
                reason: "「\(name)」读不到，如果它还在 iCloud 里，请先下载到本机再导入"
            )
        }
        if isDirectory.boolValue {
            return buildFolder(from: url, libraryDir: libraryDir)
        }
        return buildFile(from: url, libraryDir: libraryDir)
    }

    nonisolated private static func buildFolder(from url: URL, libraryDir: URL) -> BuildResult {
        let folderName = UUID().uuidString
        let folder = libraryDir.appendingPathComponent(folderName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let contents = try coordinatedContents(of: url)
            var copied = 0
            for file in contents where PageFiles.isImage(file.lastPathComponent) {
                let destination = folder.appendingPathComponent(file.lastPathComponent)
                if (try? coordinatedCopy(from: file, to: destination)) != nil {
                    copied += 1
                }
            }
            guard copied > 0 else {
                try? FileManager.default.removeItem(at: folder)
                return BuildResult(
                    book: nil,
                    reason: "「\(url.lastPathComponent)」里没有 jpg / png / webp 图片"
                )
            }
            var book = Book(
                title: url.lastPathComponent,
                type: .folder,
                relativePath: folderName,
                pageCount: copied
            )
            book.coverName = cover(for: book, libraryDir: libraryDir)
            return BuildResult(book: book, reason: "")
        } catch {
            try? FileManager.default.removeItem(at: folder)
            return BuildResult(
                book: nil,
                reason: "「\(url.lastPathComponent)」读取失败：\(error.localizedDescription)"
            )
        }
    }

    nonisolated private static func buildFile(from url: URL, libraryDir: URL) -> BuildResult {
        let name = url.lastPathComponent
        let ext = (name as NSString).pathExtension.lowercased()
        let type: BookType
        switch sniff(url) {
        case .zip:
            type = .zip
        case .pdf:
            type = .pdf
        case .rar:
            return BuildResult(
                book: nil,
                reason: "「\(name)」是 RAR 压缩包，iOS 版还不支持，请先解压成 ZIP 或文件夹"
            )
        case .unknown:
            // No usable header (or unreadable): fall back to the extension so an unusual
            // zip still imports, while unsupported types get a real reason.
            if ext == "zip" || ext == "cbz" {
                type = .zip
            } else if ext == "pdf" {
                type = .pdf
            } else {
                return BuildResult(book: nil, reason: "「\(name)」不是 ZIP / CBZ / PDF")
            }
        }

        let folderName = UUID().uuidString
        let folder = libraryDir.appendingPathComponent(folderName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let destination = folder.appendingPathComponent(name)
            // 文档选择器给的临时副本直接拷；外部 URL（文件夹模式、分享进来）走协调器，
            // 否则 iCloud 占位文件会抛 "no such file"。
            if isTemporaryPick(url) {
                try FileManager.default.copyItem(at: url, to: destination)
            } else {
                try coordinatedCopy(from: url, to: destination)
            }
            let count = pageCount(type: type, url: destination)
            guard count > 0 else {
                try? FileManager.default.removeItem(at: folder)
                let hint = type == .zip
                    ? "压缩包里没有找到图片（支持 jpg / png / webp / gif / bmp / tiff）。如果它还在 iCloud 里，先在「文件」App 里下载到本机再试"
                    : "这个 PDF 打不开，可能加密或已损坏"
                return BuildResult(book: nil, reason: "「\(name)」\(hint)")
            }
            var book = Book(
                title: (name as NSString).deletingPathExtension,
                type: type,
                relativePath: "\(folderName)/\(name)",
                pageCount: count
            )
            book.coverName = cover(for: book, libraryDir: libraryDir)
            return BuildResult(book: book, reason: "")
        } catch {
            try? FileManager.default.removeItem(at: folder)
            return BuildResult(
                book: nil,
                reason: "「\(name)」复制失败：\(error.localizedDescription)。如果它在 iCloud 云盘，先长按下载到本机再导入"
            )
        }
    }

    // MARK: - Coordinated file access

    /// `NSFileCoordinator` 是访问文件提供商 / iCloud 内容的正规方式：它会先把占位文件
    /// 下载下来再回调，直接 `copyItem` 则会因为文件还没落到本地而失败。
    nonisolated private static func coordinatedCopy(from source: URL, to destination: URL) throws {
        var coordinationError: NSError?
        var thrown: Error?
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(
            readingItemAt: source,
            options: .withoutChanges,
            error: &coordinationError
        ) { readURL in
            do {
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.copyItem(at: readURL, to: destination)
            } catch {
                thrown = error
            }
        }
        if let thrown { throw thrown }
        if let coordinationError { throw coordinationError }
    }

    nonisolated private static func coordinatedContents(of folder: URL) throws -> [URL] {
        var coordinationError: NSError?
        var thrown: Error?
        var result: [URL] = []
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(
            readingItemAt: folder,
            options: .withoutChanges,
            error: &coordinationError
        ) { readURL in
            do {
                result = try FileManager.default.contentsOfDirectory(
                    at: readURL,
                    includingPropertiesForKeys: nil
                )
            } catch {
                thrown = error
            }
        }
        if let thrown { throw thrown }
        if let coordinationError { throw coordinationError }
        return result
    }

    /// Reads the first bytes, so a mislabelled file still opens and RAR gets an actual
    /// explanation instead of a generic "无法识别".
    nonisolated private static func sniff(_ url: URL) -> Sniffed {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .unknown }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 8), head.count >= 4 else { return .unknown }
        let bytes = [UInt8](head)
        if bytes[0] == 0x50, bytes[1] == 0x4B, bytes[2] == 0x03 || bytes[2] == 0x05
            || bytes[2] == 0x07 {
            return .zip
        }
        if bytes[0] == 0x25, bytes[1] == 0x50, bytes[2] == 0x44, bytes[3] == 0x46 {
            return .pdf
        }
        if bytes[0] == 0x52, bytes[1] == 0x61, bytes[2] == 0x72, bytes[3] == 0x21 {
            return .rar
        }
        return .unknown
    }

    nonisolated private static func pageCount(type: BookType, url: URL) -> Int {
        switch type {
        case .pdf:
            return CGPDFDocument(url as CFURL)?.numberOfPages ?? 0
        case .zip:
            guard let reader = try? ZipReader(url: url) else { return 0 }
            return reader.entries.filter { PageFiles.isImage($0.name) }.count
        case .folder:
            return 0
        }
    }

    // MARK: - Mutations

    func update(_ book: Book) {
        guard let index = books.firstIndex(where: { $0.id == book.id }) else { return }
        books[index] = book
        save()
    }

    func delete(_ book: Book) {
        books.removeAll { $0.id == book.id }
        let target: URL
        if book.type == .folder {
            target = libraryDir.appendingPathComponent(book.relativePath, isDirectory: true)
        } else {
            target = libraryDir
                .appendingPathComponent(book.relativePath)
                .deletingLastPathComponent()
        }
        try? FileManager.default.removeItem(at: target)
        save()
    }

    // MARK: - Persistence

    private func load() {
        if let data = try? Data(contentsOf: stateURL),
           let list = try? JSONDecoder().decode([Book].self, from: data) {
            books = list
        }
        if let data = UserDefaults.standard.data(forKey: Keys.prefs),
           let value = try? JSONDecoder().decode(ReaderPrefs.self, from: data) {
            prefs = value
        }
        if let data = UserDefaults.standard.data(forKey: Keys.enhance),
           let value = try? JSONDecoder().decode(EnhanceSettings.self, from: data) {
            enhance = value
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(books) else { return }
        try? data.write(to: stateURL, options: .atomic)
    }

    private func persist<T: Encodable>(_ value: T, key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private enum Keys {
        static let prefs = "readerPrefs"
        static let enhance = "enhanceSettings"
    }
}
