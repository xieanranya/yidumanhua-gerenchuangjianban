import Foundation
import UIKit
import ImageIO
import AVFoundation

/// 离线导出工具：整本 CBZ（STORE 方式 zip）+ 单张页图（JPG / PNG / WebP）。
///
/// 页图本身已是压缩格式（JPEG/WebP 内容），zip 用 STORE（不二次压缩）即可，
/// 写文件流式追加，内存只占单页大小。
enum ExportKit {

    enum PageFormat: String, CaseIterable, Identifiable {
        case jpg, png, webp
        var id: String { rawValue }
        var label: String {
            switch self {
            case .jpg: return "JPG"
            case .png: return "PNG"
            case .webp: return "WebP"
            }
        }
        var ext: String { rawValue }
        var mime: String {
            switch self {
            case .jpg: return "image/jpeg"
            case .png: return "image/png"
            case .webp: return "image/webp"
            }
        }
    }

    // MARK: - 整本 CBZ

    /// 把章节页图按顺序打成 CBZ（zip）。entries 已按阅读顺序排好，name 用「章节目录/页文件名」。
    /// 流式写文件，任何规模的整本都不会撑内存。
    static func exportCBZ(entries: [(name: String, file: URL)], dest: URL) throws {
        FileManager.default.createFile(atPath: dest.path, contents: nil)
        guard let fh = try? FileHandle(forWritingTo: dest) else {
            throw NSError(domain: "export", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "无法创建导出文件"])
        }
        defer { try? fh.close() }

        var central: [Data] = []
        var offset: UInt32 = 0
        let (dTime, dDate) = dosDateTime()

        for e in entries {
            let data = (try? Data(contentsOf: e.file)) ?? Data()
            guard !data.isEmpty else { continue }
            let nameData = e.name.data(using: .utf8) ?? Data()
            let crc = crc32(data)

            var local = Data()
            local.appendLE(UInt32(0x04034b50))     // local file header
            local.appendLE(UInt16(20))             // version needed
            local.appendLE(UInt16(0x0800))         // UTF-8 文件名
            local.appendLE(UInt16(0))              // method: STORE
            local.appendLE(dTime)
            local.appendLE(dDate)
            local.appendLE(crc)
            local.appendLE(UInt32(data.count))     // compressed size
            local.appendLE(UInt32(data.count))     // uncompressed size
            local.appendLE(UInt16(nameData.count))
            local.appendLE(UInt16(0))              // extra len
            fh.write(local)
            fh.write(nameData)
            fh.write(data)

            var cen = Data()
            cen.appendLE(UInt32(0x02014b50))       // central directory header
            cen.appendLE(UInt16(20))               // version made by
            cen.appendLE(UInt16(20))               // version needed
            cen.appendLE(UInt16(0x0800))
            cen.appendLE(UInt16(0))
            cen.appendLE(dTime)
            cen.appendLE(dDate)
            cen.appendLE(crc)
            cen.appendLE(UInt32(data.count))
            cen.appendLE(UInt32(data.count))
            cen.appendLE(UInt16(nameData.count))
            cen.appendLE(UInt16(0))                // extra
            cen.appendLE(UInt16(0))                // comment
            cen.appendLE(UInt16(0))                // disk number
            cen.appendLE(UInt16(0))                // internal attrs
            cen.appendLE(UInt32(0))                // external attrs
            cen.appendLE(offset)
            cen.append(nameData)
            central.append(cen)

            offset &+= UInt32(local.count + nameData.count + data.count)
        }

        let cdStart = offset
        var cdSize: UInt32 = 0
        for c in central {
            fh.write(c)
            cdSize &+= UInt32(c.count)
        }

        var eocd = Data()
        eocd.appendLE(UInt32(0x06054b50))
        eocd.appendLE(UInt16(0))                   // disk
        eocd.appendLE(UInt16(0))                   // disk with cd
        eocd.appendLE(UInt16(central.count))       // entries (this disk)
        eocd.appendLE(UInt16(central.count))       // entries total
        eocd.appendLE(cdSize)
        eocd.appendLE(cdStart)
        eocd.appendLE(UInt16(0))                   // comment len
        fh.write(eocd)
    }

    // MARK: - 单张页图

    static func encodePage(_ image: UIImage, format: PageFormat) -> Data? {
        switch format {
        case .jpg: return image.jpegData(compressionQuality: 0.95)
        case .png: return image.pngData()
        case .webp: return webpData(image) ?? image.jpegData(compressionQuality: 0.95)
        }
    }

    /// iOS 14+ 的 ImageIO 支持 WebP 编码；失败由调用方回退 JPEG。
    private static func webpData(_ image: UIImage) -> Data? {
        guard let cg = image.cgImage else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            out, AVFileType.webP.rawValue as CFString, 1, nil
        ) else { return nil }
        CGImageDestinationAddImage(dest, cg, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    // MARK: - 页映射（全局页序号 → 章节页文件）

    /// 全局第 globalIndex 页（0 起）对应的已下载页文件；不存在或越界返回 nil。
    static func pageFileURL(
        chapters: [DownloadManager.DownloadChapter],
        bookDir: URL,
        globalIndex: Int
    ) -> URL? {
        var i = globalIndex
        for ch in chapters {
            let total = max(ch.total, 0)
            if total > 0 && i < total {
                let f = bookDir
                    .appendingPathComponent(ch.dir, isDirectory: true)
                    .appendingPathComponent(DownloadManager.pageFileName(i))
                return FileManager.default.fileExists(atPath: f.path) ? f : nil
            }
            i -= total
            if i < 0 { return nil }
        }
        return nil
    }

    // MARK: - 工具

    /// MS-DOS 时间（zip 头要求，本地时区）
    private static func dosDateTime() -> (time: UInt16, date: UInt16) {
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date())
        let year = max(1980, c.year ?? 1980)
        let date = UInt16(((year - 1980) << 9) | ((c.month ?? 1) << 5) | (c.day ?? 1))
        let time = UInt16(((c.hour ?? 0) << 11) | ((c.minute ?? 0) << 5) | ((c.second ?? 0) / 2))
        return (time, date)
    }

    private static let crcTable: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    private static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for byte in data {
            c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8)
        }
        return c ^ 0xFFFFFFFF
    }
}

private extension Data {
    mutating func appendLE(_ v: UInt16) {
        append(UInt8(v & 0xFF))
        append(UInt8((v >> 8) & 0xFF))
    }
    mutating func appendLE(_ v: UInt32) {
        append(UInt8(v & 0xFF))
        append(UInt8((v >> 8) & 0xFF))
        append(UInt8((v >> 16) & 0xFF))
        append(UInt8((v >> 24) & 0xFF))
    }
}

// ================================================================================== 分享面板

/// 系统分享面板：导出的文件从这里「存储到文件 / 存储图像 / AirDrop」。
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
