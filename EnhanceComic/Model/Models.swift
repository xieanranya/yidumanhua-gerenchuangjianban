import Foundation
import CoreGraphics

enum BookType: String, Codable {
    case zip
    case rar
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

/// How a paged image is scaled into the viewport before the finger's own zoom applies.
enum ScaleMode: String, Codable, CaseIterable, Identifiable {
    case fit
    case width
    case height
    case original

    var id: String { rawValue }

    var label: String {
        switch self {
        case .fit: return "适应屏幕"
        case .width: return "适应宽度"
        case .height: return "适应高度"
        case .original: return "原始尺寸"
        }
    }
}

/// What a tap in one of the three screen zones does.
enum TapAction: String, Codable, CaseIterable, Identifiable {
    case prevPage
    case nextPage
    case prevChapter
    case nextChapter
    case toggleChrome
    case openMenu
    case disabled

    var id: String { rawValue }

    var label: String {
        switch self {
        case .prevPage: return "上一页"
        case .nextPage: return "下一页"
        case .prevChapter: return "上一话"
        case .nextChapter: return "下一话"
        case .toggleChrome: return "显示 / 隐藏栏"
        case .openMenu: return "打开菜单"
        case .disabled: return "无"
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
    /// Optional user-given name per bookmarked source page index.
    var bookmarkNames: [Int: String] = [:]
    /// File name of the cached cover inside the book's folder, nil when not generated yet.
    var coverName: String? = nil
    /// 原始容器名（"7Z" / "TAR"）：这两种格式导入时会解包成文件夹，书架上仍显示原格式。
    var sourceLabel: String = ""

    init(
        id: UUID = UUID(),
        title: String,
        type: BookType,
        relativePath: String,
        pageCount: Int,
        addedAt: Date = Date(),
        lastPage: Int = 0,
        bookmarks: [Int] = [],
        bookmarkNames: [Int: String] = [:],
        coverName: String? = nil,
        sourceLabel: String = ""
    ) {
        self.id = id
        self.title = title
        self.type = type
        self.relativePath = relativePath
        self.pageCount = pageCount
        self.addedAt = addedAt
        self.lastPage = lastPage
        self.bookmarks = bookmarks
        self.bookmarkNames = bookmarkNames
        self.coverName = coverName
        self.sourceLabel = sourceLabel
    }

    // Hand-written coding so libraries saved by earlier builds (which had neither
    // bookmarkNames nor a few of the newer fields) still decode instead of being lost.
    enum CodingKeys: String, CodingKey {
        case id, title, type, relativePath, pageCount, addedAt, lastPage, bookmarks, bookmarkNames, coverName, sourceLabel
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(UUID.self, forKey: .id)) ?? UUID()
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        type = (try? c.decode(BookType.self, forKey: .type)) ?? .zip
        relativePath = (try? c.decode(String.self, forKey: .relativePath)) ?? ""
        pageCount = (try? c.decode(Int.self, forKey: .pageCount)) ?? 0
        addedAt = (try? c.decode(Date.self, forKey: .addedAt)) ?? Date()
        lastPage = (try? c.decode(Int.self, forKey: .lastPage)) ?? 0
        bookmarks = (try? c.decode([Int].self, forKey: .bookmarks)) ?? []
        bookmarkNames = (try? c.decode([Int: String].self, forKey: .bookmarkNames)) ?? [:]
        coverName = try? c.decodeIfPresent(String.self, forKey: .coverName)
        sourceLabel = (try? c.decode(String.self, forKey: .sourceLabel)) ?? ""
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(title, forKey: .title)
        try c.encode(type, forKey: .type)
        try c.encode(relativePath, forKey: .relativePath)
        try c.encode(pageCount, forKey: .pageCount)
        try c.encode(addedAt, forKey: .addedAt)
        try c.encode(lastPage, forKey: .lastPage)
        try c.encode(bookmarks, forKey: .bookmarks)
        try c.encode(bookmarkNames, forKey: .bookmarkNames)
        try c.encodeIfPresent(coverName, forKey: .coverName)
        try c.encode(sourceLabel, forKey: .sourceLabel)
    }
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
    /// Shows two pages side by side in paged mode when the screen is landscape.
    var doublePage = false
    /// Two pages even in portrait (页面布局 → 强制并排).
    var forceSpread = false
    /// Gap between pages in the scroll modes, percent of the viewport.
    var pageGapPercent = 0

    // ---- 滤镜 / 显示
    var grayscale = false
    var autoContrast = false
    var invert = false
    /// 蓝光过滤强度 0–100，0 为关闭。旧版本存的是布尔开关，读到 true 时映射成 55。
    var blueLight = 0

    // ---- 设置
    var keepScreenOn = true
    var autoBrightness = true
    var brightness = 50.0
    /// 0 = 纯黑, 1 = 深灰, 2 = 米白
    var background = 0
    var doubleTapZoom = true
    /// 浅色栏体（原版的白色工具栏）；false 为深色。
    var chromeLight = false
    var scaleMode: ScaleMode = .fit
    var tapLeftAction: TapAction = .prevPage
    var tapCenterAction: TapAction = .toggleChrome
    var tapRightAction: TapAction = .nextPage

    init() {}

    enum CodingKeys: String, CodingKey {
        case readingMode, rightToLeft, splitWidePages, autoCropWhiteBorders, doublePage, forceSpread
        case pageGapPercent, grayscale, autoContrast, invert, blueLight
        case keepScreenOn, autoBrightness, brightness, background, doubleTapZoom, chromeLight
        case scaleMode, tapLeftAction, tapCenterAction, tapRightAction
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        readingMode = (try? c.decode(ReadingMode.self, forKey: .readingMode)) ?? .paged
        rightToLeft = (try? c.decode(Bool.self, forKey: .rightToLeft)) ?? true
        splitWidePages = (try? c.decode(Bool.self, forKey: .splitWidePages)) ?? true
        autoCropWhiteBorders = (try? c.decode(Bool.self, forKey: .autoCropWhiteBorders)) ?? false
        doublePage = (try? c.decode(Bool.self, forKey: .doublePage)) ?? false
        forceSpread = (try? c.decode(Bool.self, forKey: .forceSpread)) ?? false
        pageGapPercent = (try? c.decode(Int.self, forKey: .pageGapPercent)) ?? 0
        grayscale = (try? c.decode(Bool.self, forKey: .grayscale)) ?? false
        autoContrast = (try? c.decode(Bool.self, forKey: .autoContrast)) ?? false
        invert = (try? c.decode(Bool.self, forKey: .invert)) ?? false
        blueLight = Self.readBlueLight(c)
        keepScreenOn = (try? c.decode(Bool.self, forKey: .keepScreenOn)) ?? true
        autoBrightness = (try? c.decode(Bool.self, forKey: .autoBrightness)) ?? true
        brightness = (try? c.decode(Double.self, forKey: .brightness)) ?? 50
        background = (try? c.decode(Int.self, forKey: .background)) ?? 0
        doubleTapZoom = (try? c.decode(Bool.self, forKey: .doubleTapZoom)) ?? true
        chromeLight = (try? c.decode(Bool.self, forKey: .chromeLight)) ?? false
        scaleMode = (try? c.decode(ScaleMode.self, forKey: .scaleMode)) ?? .fit
        tapLeftAction = (try? c.decode(TapAction.self, forKey: .tapLeftAction)) ?? .prevPage
        tapCenterAction = (try? c.decode(TapAction.self, forKey: .tapCenterAction)) ?? .toggleChrome
        tapRightAction = (try? c.decode(TapAction.self, forKey: .tapRightAction)) ?? .nextPage
    }

    /// `blueLight` used to be a plain on/off switch. Read either shape so an existing
    /// preferences file does not silently lose the setting.
    private static func readBlueLight(_ c: KeyedDecodingContainer<CodingKeys>) -> Int {
        if let value = try? c.decode(Int.self, forKey: .blueLight) {
            return min(max(value, 0), 100)
        }
        if let on = try? c.decode(Bool.self, forKey: .blueLight) {
            return on ? 55 : 0
        }
        return 0
    }
}

struct PageRef: Hashable, Identifiable {
    let index: Int
    let half: Int
    /// width / height of this display page (already halved for split pages).
    ///
    /// Carried on the ref instead of asking the session: layout runs on the main thread on
    /// every scroll frame, and a lock round-trip there is what made flipping feel sticky.
    let aspect: CGFloat

    init(index: Int, half: Int, aspect: CGFloat = 1 / 1.42) {
        self.index = index
        self.half = half
        self.aspect = aspect.isFinite && aspect > 0.02 ? aspect : 1 / 1.42
    }

    var id: Int { index * 3 + half }
    var isSplit: Bool { half != 0 }
}

func buildPageRefs(aspects: [CGFloat], splitWidePages: Bool) -> [PageRef] {
    var out: [PageRef] = []
    out.reserveCapacity(aspects.count)
    for (i, aspect) in aspects.enumerated() {
        let base = aspect.isFinite && aspect > 0.02 ? aspect : 1 / 1.42
        if splitWidePages && base > 1.55 {
            out.append(PageRef(index: i, half: 1, aspect: base * 0.5))
            out.append(PageRef(index: i, half: 2, aspect: base * 0.5))
        } else {
            out.append(PageRef(index: i, half: 0, aspect: base))
        }
    }
    return out
}

/// Index of the first display page belonging to source page `sourceIndex`.
func refIndexOfSource(_ refs: [PageRef], _ sourceIndex: Int) -> Int {
    refs.firstIndex { $0.index == sourceIndex } ?? 0
}
