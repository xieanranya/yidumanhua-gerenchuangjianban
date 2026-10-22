import Foundation
import CoreGraphics
import SwiftUI

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
            books.append(book)
            save()
            return book
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
            return Book(
                title: (name as NSString).deletingPathExtension,
                type: type,
                relativePath: "\(folderName)/\(name)",
                pageCount: count
            )
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
