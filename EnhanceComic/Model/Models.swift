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
}

struct EnhanceSettings: Codable, Equatable {
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

struct ReaderPrefs: Codable, Equatable {
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
