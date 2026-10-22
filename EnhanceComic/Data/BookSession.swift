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

/// An opened book: page count, aspect ratios and page decoding.
final class BookSession {

    let book: Book
    private let url: URL
    private var zip: ZipReader?
    private var zipEntries: [ZipEntry] = []
    private var imageURLs: [URL] = []
    private var pdf: CGPDFDocument?

    var pageCount: Int {
        if let pdf { return pdf.numberOfPages }
        if !zipEntries.isEmpty { return zipEntries.count }
        return imageURLs.count
    }

    init?(book: Book, libraryDir: URL) {
        self.book = book
        let fileURL = libraryDir.appendingPathComponent(book.relativePath)
        self.url = fileURL

        switch book.type {
        case .zip:
            guard let reader = try? ZipReader(url: fileURL) else { return nil }
            let pages = reader.entries
                .filter { PageFiles.isImage($0.name) }
                .sorted { PageFiles.naturalLess($0.name, $1.name) }
            guard !pages.isEmpty else { return nil }
            self.zip = reader
            self.zipEntries = pages

        case .pdf:
            guard let doc = CGPDFDocument(fileURL as CFURL), doc.numberOfPages > 0 else {
                return nil
            }
            self.pdf = doc

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
        }
    }

    /// width / height of a source page, 0.7 when unknown.
    func aspect(at index: Int) -> CGFloat {
        if let pdf {
            guard let page = pdf.page(at: index + 1) else { return 0.7 }
            let box = page.getBoxRect(.mediaBox)
            return box.height > 0 ? box.width / box.height : 0.7
        }
        guard let source = imageSource(at: index),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as NSDictionary?
        else { return 0.7 }
        let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue ?? 0
        let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue ?? 0
        guard w > 1, h > 1 else { return 0.7 }
        return CGFloat(w / h)
    }

    func image(at index: Int, maxWidth: CGFloat) -> CGImage? {
        if pdf != nil { return renderPDF(index: index, maxWidth: maxWidth) }
        guard let source = imageSource(at: index) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(64, Int(maxWidth)),
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    // MARK: - Private

    private func imageSource(at index: Int) -> CGImageSource? {
        if !zipEntries.isEmpty {
            guard index >= 0, index < zipEntries.count,
                  let data = zip?.data(for: zipEntries[index])
            else { return nil }
            return CGImageSourceCreateWithData(data as CFData, nil)
        }
        guard index >= 0, index < imageURLs.count else { return nil }
        return CGImageSourceCreateWithURL(imageURLs[index] as CFURL, nil)
    }

    private func renderPDF(index: Int, maxWidth: CGFloat) -> CGImage? {
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
