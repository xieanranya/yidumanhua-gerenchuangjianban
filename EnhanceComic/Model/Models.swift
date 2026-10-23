import Foundation
import CoreGraphics

enum BookType: String, Codable {
    case zip
    case pdf
    case folder
}

enum ReadingMode: String, Codable, CaseIterable, Identifiable {
    case paged
    case vertical
    case horizontal

    var id: String { rawValue }

    var label: String {
        switch self {
        case .paged: return "翻页"
        case .vertical: return "纵向滚动"
        case .horizontal: return "横向滚动"
        }
    }
}

enum ScaleFilter: Int, Codable, CaseIterable, Identifiable {
    case nearest = 0
    case bilinear
    case bicubic
    case lanczos3

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .nearest: return "最近邻"
        case .bilinear: return "双线性"
        case .bicubic: return "双三次"
        case .lanczos3: return "Lanczos3"
        }
    }
}

struct Book: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var title: String
    var type: BookType
    /// Path relative to the library directory.
    var relativePath: String
    var pageCount: Int
    var addedAt: Date = Date()
    var lastPage: Int = 0
    var bookmarks: [Int] = []
    /// File name of the cached cover inside the book's folder, nil when not generated yet.
    var coverName: String? = nil
}

/// A run of consecutive pages that live in the same folder inside an archive.
struct Chapter: Identifiable, Hashable {
    let title: String
    let start: Int
    let count: Int

    var id: Int { start }
    var endExclusive: Int { start + count }
    func contains(_ page: Int) -> Bool { page >= start && page < endExclusive }

    static func build(from names: [String]) -> [Chapter] {
        guard !names.isEmpty else { return [] }
        let dirs = names.map { ($0 as NSString).deletingLastPathComponent }
        let distinct = Array(Set(dirs))
        if distinct.count < 2 {
            return [Chapter(title: singleTitle(dirs[0]), start: 0, count: names.count)]
        }
        var out: [Chapter] = []
        var runStart = 0
        for i in 1...names.count {
            if i == names.count || dirs[i] != dirs[runStart] {
                out.append(
                    Chapter(
                        title: dirTitle(dirs[runStart], fallback: out.count + 1),
                        start: runStart,
                        count: i - runStart
                    )
                )
                runStart = i
            }
        }
        if out.count < 2 {
            return [Chapter(title: singleTitle(dirs[0]), start: 0, count: names.count)]
        }
        return out
    }

    private static func dirTitle(_ dir: String, fallback: Int) -> String {
        let last = (dir as NSString).lastPathComponent
        return last.isEmpty ? "第 \(fallback) 段" : last
    }

    private static func singleTitle(_ dir: String) -> String {
        (dir as NSString).lastPathComponent
    }
}

struct EnhanceSettings: Codable, Equatable, Hashable {
    var sharpen = false
    var sharpenAmount = 100.0
    var denoise = false
    var denoiseRadius = 2
    var denoiseBlend = 100.0
    var contrastPass = false
    var contrastBlend = 100.0
    var scaleFilter: ScaleFilter = .bicubic
    var brightness = 100.0
    var contrastLevel = 100.0
    var gamma = 100.0

    var sharpenF: Float { Float(sharpenAmount / 100.0 * 2.5) }
    var denoiseBlendF: Float { Float(0.05 + denoiseBlend / 100.0 * 0.95) }
    var contrastBlendF: Float { Float(0.1 + contrastBlend / 100.0 * 0.9) }
    var brightnessF: Float { Float(brightness / 100.0) }
    var contrastF: Float { Float(contrastLevel / 100.0) }
    var gammaF: Float { Float(max(0.05, gamma / 100.0)) }
}

struct ReaderPrefs: Codable, Equatable, Hashable {
    var readingMode: ReadingMode = .paged
    var rightToLeft = true
    var splitWidePages = true
    var autoCropWhiteBorders = false
}

struct PageRef: Hashable, Identifiable {
    let index: Int
    let half: Int

    var id: Int { index * 3 + half }
    var isSplit: Bool { half != 0 }
}

func buildPageRefs(aspects: [CGFloat], splitWidePages: Bool) -> [PageRef] {
    var out: [PageRef] = []
    out.reserveCapacity(aspects.count)
    for (i, aspect) in aspects.enumerated() {
        if splitWidePages && aspect > 1.55 {
            out.append(PageRef(index: i, half: 1))
            out.append(PageRef(index: i, half: 2))
        } else {
            out.append(PageRef(index: i, half: 0))
        }
    }
    return out
}

/// Index of the first display page belonging to source page `sourceIndex`.
func refIndexOfSource(_ refs: [PageRef], _ sourceIndex: Int) -> Int {
    refs.firstIndex { $0.index == sourceIndex } ?? 0
}
