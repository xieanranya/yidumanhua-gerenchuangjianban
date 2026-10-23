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
        var failed: Int
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
    @discardableResult
    private func generateCover(for book: Book) -> String? {
        guard let session = session(for: book),
              let page = session.image(at: 0, maxWidth: 480),
              let data = UIImage(cgImage: page).jpegData(compressionQuality: 0.86)
        else { return nil }
        let name = "cover.jpg"
        let url = folder(for: book).appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
            return name
        } catch {
            return nil
        }
    }

    /// Fills in covers for books imported by an earlier build.
    func backfillCovers() async {
        let missing = books.filter { $0.coverName == nil }
        guard !missing.isEmpty else { return }
        let dir = libraryDir

        let generated: [(UUID, String)] = await Task.detached(priority: .utility) {
            var out: [(UUID, String)] = []
            for book in missing {
                guard let session = BookSession(book: book, libraryDir: dir),
                      let page = session.image(at: 0, maxWidth: 480),
                      let data = UIImage(cgImage: page).jpegData(compressionQuality: 0.7)
                else { continue }
                let target = book.type == .folder
                    ? dir.appendingPathComponent(book.relativePath, isDirectory: true)
                    : dir.appendingPathComponent(book.relativePath).deletingLastPathComponent()
                let url = target.appendingPathComponent("cover.jpg")
                if (try? data.write(to: url, options: .atomic)) != nil {
                    out.append((book.id, "cover.jpg"))
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

    // MARK: - Import

    func importDocuments(_ urls: [URL]) async -> ImportResult {
        var added = 0
        var failed = 0
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let book = importOne(url) {
                books.append(book)
                added += 1
            } else {
                failed += 1
            }
        }
        save()
        return ImportResult(added: added, failed: failed)
    }

    @discardableResult
    func importFolder(_ url: URL) async -> Book? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let folderName = UUID().uuidString
        let folder = libraryDir.appendingPathComponent(folderName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let contents = try FileManager.default.contentsOfDirectory(
                at: url,
                includingPropertiesForKeys: nil
            )
            var copied = 0
            for file in contents where PageFiles.isImage(file.lastPathComponent) {
                let destination = folder.appendingPathComponent(file.lastPathComponent)
                if (try? FileManager.default.copyItem(at: file, to: destination)) != nil {
                    copied += 1
                }
            }
            guard copied > 0 else {
                try? FileManager.default.removeItem(at: folder)
                return nil
            }
            let book = Book(
                title: url.lastPathComponent,
                type: .folder,
                relativePath: folderName,
                pageCount: copied
            )
            var withCover = book
            withCover.coverName = generateCover(for: book)
            books.append(withCover)
            save()
            return withCover
        } catch {
            try? FileManager.default.removeItem(at: folder)
            return nil
        }
    }

    private func importOne(_ url: URL) -> Book? {
        let name = url.lastPathComponent
        let ext = (name as NSString).pathExtension.lowercased()
        let type: BookType
        switch ext {
        case "pdf": type = .pdf
        case "zip", "cbz": type = .zip
        default: return nil
        }

        let folderName = UUID().uuidString
        let folder = libraryDir.appendingPathComponent(folderName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let destination = folder.appendingPathComponent(name)
            try FileManager.default.copyItem(at: url, to: destination)
            let count = pageCount(type: type, url: destination)
            guard count > 0 else {
                try? FileManager.default.removeItem(at: folder)
                return nil
            }
            let book = Book(
                title: (name as NSString).deletingPathExtension,
                type: type,
                relativePath: "\(folderName)/\(name)",
                pageCount: count
            )
            var withCover = book
            withCover.coverName = generateCover(for: book)
            return withCover
        } catch {
            try? FileManager.default.removeItem(at: folder)
            return nil
        }
    }

    private func pageCount(type: BookType, url: URL) -> Int {
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
