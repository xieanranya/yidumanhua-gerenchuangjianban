import Foundation
import CoreGraphics
import ImageIO

enum PageFiles {
    static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif", "tif", "tiff",
    ]

    static func isImage(_ name: String) -> Bool {
        guard !name.contains("__MACOSX") else { return false }
        let last = (name as NSString).lastPathComponent
        guard !last.hasPrefix(".") else { return false }
        let ext = (last as NSString).pathExtension.lowercased()
        return imageExtensions.contains(ext)
    }

    static func naturalLess(_ a: String, _ b: String) -> Bool {
        a.compare(b, options: [.numeric, .caseInsensitive, .widthInsensitive]) == .orderedAscending
    }
}

/// An opened book: page count, aspect ratios, page decoding and chapter runs.
///
/// Every accessor is serialised by `lock` because the reader decodes on a background task
/// while the aspect scan may still be running.
final class BookSession: @unchecked Sendable {

    let book: Book
    private let url: URL
    private var zip: ZipReader?
    private var zipEntries: [ZipEntry] = []
    /// RAR / CBR：走系统 libarchive 流式读，按名字定位条目（见 LibArchive.readEntry）。
    private var rarURL: URL?
    private var rarEntries: [LibArchive.Entry] = []
    private var rarCache: [Int: Data] = [:]
    private var rarOrder: [Int] = []
    private var imageURLs: [URL] = []
    private var pdf: CGPDFDocument?
    private let lock = NSLock()

    /// Consecutive same-folder runs; a single entry means "no chapter UI".
    private(set) var chapters: [Chapter] = []

    private(set) var aspects: [CGFloat] = []

    /// Source pixel width per page, used by the 原始尺寸 scale mode.
    private(set) var pixelWidths: [Int] = []

    var pageCount: Int {
        if let pdf { return pdf.numberOfPages }
        if !zipEntries.isEmpty { return zipEntries.count }
        if !rarEntries.isEmpty { return rarEntries.count }
        return imageURLs.count
    }

    var hasChapters: Bool { chapters.count > 1 }

    init?(book: Book, libraryDir: URL) {
        self.book = book
        let fileURL = libraryDir.appendingPathComponent(book.relativePath)
        self.url = fileURL

        var resolvedChapters: [Chapter] = []
        var count = 0

        switch book.type {
        case .zip:
            guard let reader = try? ZipReader(url: fileURL) else { return nil }
            let pages = reader.entries
                .filter { PageFiles.isImage($0.name) }
                .sorted { PageFiles.naturalLess($0.name, $1.name) }
            guard !pages.isEmpty else { return nil }
            self.zip = reader
            self.zipEntries = pages
            count = pages.count
            resolvedChapters = Chapter.build(from: pages.map { $0.name })

        case .rar:
            guard LibArchive.shared.isAvailable else { return nil }
            let entries = LibArchive.shared.list(fileURL)
                .filter { PageFiles.isImage($0.name) }
                .sorted { PageFiles.naturalLess($0.name, $1.name) }
            guard !entries.isEmpty else { return nil }
            self.rarURL = fileURL
            self.rarEntries = entries
            count = entries.count
            resolvedChapters = Chapter.build(from: entries.map { $0.name })

        case .pdf:
            guard let doc = CGPDFDocument(fileURL as CFURL), doc.numberOfPages > 0 else {
                return nil
            }
            self.pdf = doc
            count = doc.numberOfPages
            resolvedChapters = [Chapter(title: "", start: 0, count: count)]

        case .folder:
            let contents = (try? FileManager.default.contentsOfDirectory(
                at: fileURL,
                includingPropertiesForKeys: nil
            )) ?? []
            let pages = contents
                .filter { PageFiles.isImage($0.lastPathComponent) }
                .sorted { PageFiles.naturalLess($0.lastPathComponent, $1.lastPathComponent) }
            guard !pages.isEmpty else { return nil }
            self.imageURLs = pages
            count = pages.count
            resolvedChapters = [Chapter(title: "", start: 0, count: count)]
        }

        self.chapters = resolvedChapters
        self.aspects = [CGFloat](repeating: 0, count: count)
        self.pixelWidths = [Int](repeating: 0, count: count)
    }

    // MARK: - Aspects

    /// Reads every page header so the scroll layouts are stable before drawing.
    func scanAspects(onProgress: ((Int, Int) -> Void)? = nil) {
        let total = pageCount
        for i in 0..<total {
            lock.lock()
            if aspects[i] <= 0.01 || pixelWidths[i] == 0 {
                let measured = measureLocked(i)
                if aspects[i] <= 0.01 { aspects[i] = measured.aspect }
                if pixelWidths[i] == 0 { pixelWidths[i] = measured.width }
            }
            lock.unlock()
            if (i + 1) % 8 == 0 { onProgress?(i + 1, total) }
        }
        onProgress?(total, total)
    }

    /// width / height of a source page, 0.7 when unknown.
    func aspect(at index: Int) -> CGFloat {
        lock.lock()
        defer { lock.unlock() }
        guard index >= 0, index < aspects.count else { return 0.7 }
        if aspects[index] > 0.01 { return aspects[index] }
        let measured = measureLocked(index)
        aspects[index] = measured.aspect
        if pixelWidths.indices.contains(index) {
            pixelWidths[index] = measured.width
        }
        return measured.aspect
    }

    /// Native pixel width of a source page; 0 when it cannot be read.
    func pixelWidth(at index: Int) -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard index >= 0, index < pixelWidths.count else { return 0 }
        if pixelWidths[index] > 0 { return pixelWidths[index] }
        let measured = measureLocked(index)
        if aspects.indices.contains(index) { aspects[index] = measured.aspect }
        pixelWidths[index] = measured.width
        return measured.width
    }

    private func measureLocked(_ index: Int) -> (aspect: CGFloat, width: Int) {
        if let pdf {
            guard let page = pdf.page(at: index + 1) else { return (0.7, 0) }
            let box = page.getBoxRect(.mediaBox)
            guard box.height > 0 else { return (0.7, 0) }
            return (box.width / box.height, Int(box.width))
        }
        // ZIP pages: inflate only the first slice of the stream. An image header is always
        // near the front, and measuring every page in full is what made opening a large
        // archive feel like a frozen spinner.
        if !zipEntries.isEmpty {
            guard index >= 0, index < zipEntries.count, let reader = zip else { return (0.7, 0) }
            if let head = reader.data(for: zipEntries[index], limit: 262_144),
               let size = BookSession.pixelSize(head) {
                return (CGFloat(size.width / size.height), Int(size.width))
            }
            guard let full = reader.data(for: zipEntries[index]),
                  let size = BookSession.pixelSize(full) else { return (0.7, 0) }
            return (CGFloat(size.width / size.height), Int(size.width))
        }
        if !rarEntries.isEmpty {
            guard index >= 0, index < rarEntries.count else { return (0.7, 0) }
            let name = rarEntries[index].name
            if let head = rarData(index, name: name, limit: 262_144),
               let size = BookSession.pixelSize(head) {
                return (CGFloat(size.width / size.height), Int(size.width))
            }
            guard let full = rarData(index, name: name),
                  let size = BookSession.pixelSize(full) else { return (0.7, 0) }
            return (CGFloat(size.width / size.height), Int(size.width))
        }
        guard let source = imageSourceLocked(at: index),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as NSDictionary?
        else { return (0.7, 0) }
        let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue ?? 0
        let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue ?? 0
        guard w > 1, h > 1 else { return (0.7, 0) }
        return (CGFloat(w / h), Int(w))
    }

    /// Pixel size from an image header alone, so a partially inflated page still measures.
    static func pixelSize(_ data: Data) -> (width: Double, height: Double)? {
        guard !data.isEmpty else { return nil }
        let source = CGImageSourceCreateIncremental(nil)
        CGImageSourceUpdateData(source, data as CFData, false)
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as NSDictionary?,
              let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
              w > 1, h > 1
        else { return nil }
        return (w, h)
    }

    // MARK: - Decoding

    /// Decoded + enhanced pages, keyed by page and render settings.
    ///
    /// Scrolling used to re-inflate and re-run the enhancer for every page it passed, so
    /// flipping back one page cost as much as opening it the first time. NSCache drops
    /// entries under memory pressure on its own.
    private let renderCache: NSCache<NSString, CGImage> = {
        let cache = NSCache<NSString, CGImage>()
        cache.countLimit = 40
        cache.totalCostLimit = 220 * 1024 * 1024
        return cache
    }()

    /// Everything that changes the pixels of a page, so a settings tweak invalidates cleanly.
    private func renderKey(
        _ index: Int,
        _ maxWidth: CGFloat,
        _ settings: EnhanceSettings,
        _ crop: Bool,
        _ half: Int,
        _ rightToLeft: Bool
    ) -> NSString {
        // Width is bucketed to 32px: a one-pixel layout change must not throw the page away.
        let bucket = Int(maxWidth / 32)
        return "\(index)|\(bucket)|\(settings.hashValue)|\(crop)|\(half)|\(rightToLeft)" as NSString
    }

    func render(
        at index: Int,
        maxWidth: CGFloat,
        settings: EnhanceSettings,
        crop: Bool,
        half: Int,
        rightToLeft: Bool
    ) -> CGImage? {
        let key = renderKey(index, maxWidth, settings, crop, half, rightToLeft)
        if let hit = renderCache.object(forKey: key) { return hit }
        guard let raw = image(at: index, maxWidth: maxWidth) else { return nil }
        let output = ImageEnhancer.process(
            raw,
            settings: settings,
            autoCrop: crop,
            half: half,
            rightToLeft: rightToLeft
        ) ?? raw
        let cost = output.bytesPerRow * output.height
        renderCache.setObject(output, forKey: key, cost: cost)
        return output
    }

    /// True when [render] would answer without touching the archive.
    func isRendered(
        at index: Int,
        maxWidth: CGFloat,
        settings: EnhanceSettings,
        crop: Bool,
        half: Int,
        rightToLeft: Bool
    ) -> Bool {
        renderCache.object(forKey: renderKey(index, maxWidth, settings, crop, half, rightToLeft)) != nil
    }

    func image(at index: Int, maxWidth: CGFloat) -> CGImage? {
        // PDF pages are drawn from a CGPDFDocument, which is not safe to touch from two
        // threads, so that path keeps its lock for the whole render.
        lock.lock()
        if pdf != nil {
            let page = renderPDFLocked(index: index, maxWidth: maxWidth)
            lock.unlock()
            return page
        }
        let payload = payloadLocked(index)
        lock.unlock()
        guard let payload else { return nil }

        let source: CGImageSource?
        switch payload {
        case .data(let data):
            source = CGImageSourceCreateWithData(data as CFData, nil)
        case .file(let url):
            source = CGImageSourceCreateWithURL(url as CFURL, nil)
        }
        guard let source else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(64, Int(maxWidth)),
        ]
        // Decoding happens outside the lock: several pages can be prepared at once instead
        // of queueing behind one another while the finger is still moving.
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    private enum Payload {
        case data(Data)
        case file(URL)
    }

    /// Raw bytes (or a file URL) for one page. Inflating a stream has to stay under the lock,
    /// but it is only part of the cost — image decoding is the expensive half and is done
    /// outside of it.
    private func payloadLocked(_ index: Int) -> Payload? {
        if !rarEntries.isEmpty {
            guard index >= 0, index < rarEntries.count else { return nil }
            return rarData(index, name: rarEntries[index].name).map(Payload.data)
        }
        if !zipEntries.isEmpty {
            guard index >= 0, index < zipEntries.count, let data = zip?.data(for: zipEntries[index])
            else { return nil }
            return .data(data)
        }
        guard index >= 0, index < imageURLs.count else { return nil }
        return .file(imageURLs[index])
    }

    func chapter(at page: Int) -> Chapter? {
        chapters.first { $0.contains(page) } ?? chapters.last
    }

    // MARK: - Private

    private func imageSourceLocked(at index: Int) -> CGImageSource? {
        if !rarEntries.isEmpty {
            guard index >= 0, index < rarEntries.count,
                  let data = rarData(index, name: rarEntries[index].name)
            else { return nil }
            return CGImageSourceCreateWithData(data as CFData, nil)
        }
        if !zipEntries.isEmpty {
            guard index >= 0, index < zipEntries.count,
                  let data = zip?.data(for: zipEntries[index])
            else { return nil }
            return CGImageSourceCreateWithData(data as CFData, nil)
        }
        guard index >= 0, index < imageURLs.count else { return nil }
        return CGImageSourceCreateWithURL(imageURLs[index] as CFURL, nil)
    }

    /// 读 RAR 里的一页。libarchive 是流式的，每次要重新扫到目标条目，所以留三页缓存 ——
    /// 翻回上一页、以及「先量尺寸再解码」这类重复访问都能直接命中。
    private func rarData(_ index: Int, name: String, limit: Int = Int.max) -> Data? {
        if limit == Int.max, let hit = rarCache[index] { return hit }
        guard let url = rarURL else { return nil }
        guard let data = LibArchive.shared.readEntry(url, named: name, limit: limit) else {
            return nil
        }
        if limit == Int.max {
            rarCache[index] = data
            rarOrder.removeAll { $0 == index }
            rarOrder.append(index)
            while rarOrder.count > 3 {
                let evicted = rarOrder.removeFirst()
                rarCache.removeValue(forKey: evicted)
            }
        }
        return data
    }

    private func renderPDFLocked(index: Int, maxWidth: CGFloat) -> CGImage? {
        guard let pdf, let page = pdf.page(at: index + 1) else { return nil }
        let box = page.getBoxRect(.mediaBox)
        guard box.width > 0, box.height > 0 else { return nil }

        let width = max(64, Int(min(maxWidth, box.width * 4)))
        let height = max(1, Int(CGFloat(width) * box.height / box.width))
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        context.setFillColor(CGColor(gray: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.scaleBy(x: CGFloat(width) / box.width, y: CGFloat(height) / box.height)
        context.translateBy(x: -box.minX, y: -box.minY)
        context.drawPDFPage(page)
        return context.makeImage()
    }
}
