import Foundation

/// RAR / 7z / TAR 的读写，走 iOS 系统自带的 `libarchive`。
///
/// 为什么是 dlopen 而不是链接：iOS SDK 里确实有 `usr/lib/libarchive.2.tbd`（18.5 SDK 实测），
/// 说明真机上这个 dylib 是存在的，而且它自带 rar / rar5 / 7z / tar / gzip / bzip2 / xz 全套
/// 解压器。但它不是公开 API，链接进来会让 App 在启动时强依赖它；用 dlopen 的话，万一某个
/// 系统版本里没有，功能降级成「不支持这个格式」，App 照常启动，不会崩。
///
/// 之前 RAR 一直是 iOS 的缺口，就是因为要内置 unrar 的 C++ 源码（六十来个文件 + C++ 桥接）。
/// 系统既然自带解码器，就不必了。
final class LibArchive {

    struct Entry {
        let name: String
        let size: Int
    }

    static let shared = LibArchive()

    private typealias NewFn = @convention(c) () -> UnsafeMutableRawPointer?
    private typealias Int1Fn = @convention(c) (UnsafeMutableRawPointer?) -> Int32
    private typealias OpenFn = @convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?, Int) -> Int32
    private typealias NextFn = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutablePointer<UnsafeMutableRawPointer?>?) -> Int32
    private typealias ReadFn = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, Int) -> Int
    private typealias PathFn = @convention(c) (UnsafeMutableRawPointer?) -> UnsafePointer<CChar>?
    private typealias SizeFn = @convention(c) (UnsafeMutableRawPointer?) -> Int64
    private typealias StringFn = @convention(c) (UnsafeMutableRawPointer?) -> UnsafePointer<CChar>?

    private var handle: UnsafeMutableRawPointer?
    private var readNew: NewFn?
    private var supportFilterAll: Int1Fn?
    private var supportFormatAll: Int1Fn?
    private var supportFormatRar: Int1Fn?
    private var supportFormatRar5: Int1Fn?
    private var supportFormat7zip: Int1Fn?
    private var supportFormatTar: Int1Fn?
    private var openFilename: OpenFn?
    private var nextHeader: NextFn?
    private var readData: ReadFn?
    private var dataSkip: Int1Fn?
    private var readFree: Int1Fn?
    private var entryPathname: PathFn?
    private var entrySize: SizeFn?
    private var errorString: StringFn?

    /// `false` 表示这台设备上取不到系统 libarchive，RAR / 7Z / TAR 都不可用。
    var isAvailable: Bool { readNew != nil && openFilename != nil && nextHeader != nil }

    private init() {
        // 老系统和不同版本上路径可能差一个后缀，挨个试。
        let candidates = [
            "/usr/lib/libarchive.2.dylib",
            "/usr/lib/libarchive.dylib",
            "/usr/lib/libarchive.2.0.dylib",
        ]
        for path in candidates where handle == nil {
            handle = dlopen(path, RTLD_NOW)
        }
        guard let handle else { return }

        func symbol<T>(_ name: String, _ type: T.Type) -> T? {
            guard let pointer = dlsym(handle, name) else { return nil }
            return unsafeBitCast(pointer, to: T.self)
        }

        readNew = symbol("archive_read_new", NewFn.self)
        supportFilterAll = symbol("archive_read_support_filter_all", Int1Fn.self)
        supportFormatAll = symbol("archive_read_support_format_all", Int1Fn.self)
        supportFormatRar = symbol("archive_read_support_format_rar", Int1Fn.self)
        supportFormatRar5 = symbol("archive_read_support_format_rar5", Int1Fn.self)
        supportFormat7zip = symbol("archive_read_support_format_7zip", Int1Fn.self)
        supportFormatTar = symbol("archive_read_support_format_tar", Int1Fn.self)
        openFilename = symbol("archive_read_open_filename", OpenFn.self)
        nextHeader = symbol("archive_read_next_header", NextFn.self)
        readData = symbol("archive_read_data", ReadFn.self)
        dataSkip = symbol("archive_read_data_skip", Int1Fn.self)
        readFree = symbol("archive_read_free", Int1Fn.self)
        entryPathname = symbol("archive_entry_pathname", PathFn.self)
        entrySize = symbol("archive_entry_size", SizeFn.self)
        errorString = symbol("archive_error_string", StringFn.self)
    }

    // MARK: - Calls

    /// 列出所有条目。`onEntry` 返回 false 就提前结束。
    private func walk(_ url: URL, onEntry: (Entry) -> Bool) {
        guard isAvailable, let readNew, let openFilename, let nextHeader else { return }
        guard let archive = readNew() else { return }
        defer { _ = readFree?(archive) }

        _ = supportFilterAll?(archive)
        // 逐个注册，比 format_all 更可控：老系统上缺少某个 format 也不会整体失效。
        _ = supportFormatRar?(archive)
        _ = supportFormatRar5?(archive)
        _ = supportFormat7zip?(archive)
        _ = supportFormatTar?(archive)

        guard openFilename(archive, url.path, 1 << 16) == 0 else { return }
        var entryPointer: UnsafeMutableRawPointer?
        while nextHeader(archive, &entryPointer) == 0 {
            guard let entryPointer, let entryPathname else { continue }
            guard let raw = entryPathname(entryPointer) else { continue }
            let name = String(cString: raw)
            let size = Int(entrySize?(entryPointer) ?? 0)
            if !onEntry(Entry(name: name, size: size)) { return }
            _ = dataSkip?(archive)
        }
    }

    func list(_ url: URL) -> [Entry] {
        var out: [Entry] = []
        walk(url) { entry in
            out.append(entry)
            return true
        }
        return out
    }

    /// 指定名字的条目内容（内部重新打开一次归档，扫到该条目为止）。
    /// 用名字而不是序号：解压器返回的是归档内顺序，而阅读器按自然序排列页，
    /// 两者不一定一致，用序号会对错页。
    func readEntry(_ url: URL, named wanted: String, limit: Int = Int.max) -> Data? {
        guard isAvailable, let readNew, let openFilename, let nextHeader, let readData else {
            return nil
        }
        guard let archive = readNew() else { return nil }
        defer { _ = readFree?(archive) }
        _ = supportFilterAll?(archive)
        _ = supportFormatRar?(archive)
        _ = supportFormatRar5?(archive)
        _ = supportFormat7zip?(archive)
        _ = supportFormatTar?(archive)
        guard openFilename(archive, url.path, 1 << 16) == 0 else { return nil }

        var entryPointer: UnsafeMutableRawPointer?
        while nextHeader(archive, &entryPointer) == 0 {
            guard let entryPointer, let entryPathname else { continue }
            guard let raw = entryPathname(entryPointer) else { continue }
            let name = String(cString: raw)
            if name == wanted {
                let declared = Int(entrySize?(entryPointer) ?? 0)
                let capacity = declared > 0 ? min(declared, limit) : min(max(limit, 65_536), 1 << 26)
                var buffer = Data()
                buffer.reserveCapacity(min(capacity, 1 << 24))
                let chunk = 256 * 1024
                var scratch = [UInt8](repeating: 0, count: chunk)
                while buffer.count < limit {
                    let want = min(chunk, limit - buffer.count)
                    let read = scratch.withUnsafeMutableBytes { dst -> Int in
                        readData(archive, dst.baseAddress, want)
                    }
                    if read <= 0 { break }
                    buffer.append(contentsOf: scratch[0..<read])
                }
                return buffer.isEmpty ? nil : buffer
            }
            _ = dataSkip?(archive)
        }
        return nil
    }

    /// 把归档里的图片全部解到目录，返回图片数量。7z / tar 用这条路径（solid block 没法按页读）。
    func extractImages(_ url: URL, to directory: URL) -> Int {
        guard isAvailable, let readNew, let openFilename, let nextHeader, let readData else { return 0 }
        guard let archive = readNew() else { return 0 }
        defer { _ = readFree?(archive) }
        _ = supportFilterAll?(archive)
        _ = supportFormatRar?(archive)
        _ = supportFormatRar5?(archive)
        _ = supportFormat7zip?(archive)
        _ = supportFormatTar?(archive)
        guard openFilename(archive, url.path, 1 << 16) == 0 else { return 0 }

        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let root = directory.standardizedFileURL.path
        var count = 0
        var entryPointer: UnsafeMutableRawPointer?
        while nextHeader(archive, &entryPointer) == 0 {
            guard let entryPointer, let entryPathname else { continue }
            guard let raw = entryPathname(entryPointer) else { continue }
            let name = String(cString: raw)
            guard PageFiles.isImage(name) else {
                _ = dataSkip?(archive)
                continue
            }
            let relative = name.replacingOccurrences(of: "\\", with: "/")
            let target = directory.appendingPathComponent(relative).standardizedFileURL
            // 拒绝 ../ 逃逸。
            guard target.path.hasPrefix(root) else {
                _ = dataSkip?(archive)
                continue
            }
            try? FileManager.default.createDirectory(
                at: target.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            FileManager.default.createFile(atPath: target.path, contents: nil)
            guard let output = try? FileHandle(forWritingTo: target) else {
                _ = dataSkip?(archive)
                continue
            }
            defer { try? output.close() }

            let chunk = 256 * 1024
            var scratch = [UInt8](repeating: 0, count: chunk)
            var wrote = 0
            while true {
                let read = scratch.withUnsafeMutableBytes { dst -> Int in
                    readData(archive, dst.baseAddress, chunk)
                }
                if read <= 0 { break }
                output.write(Data(scratch[0..<read]))
                wrote += read
            }
            if wrote > 0 { count += 1 } else { try? FileManager.default.removeItem(at: target) }
        }
        return count
    }
}
