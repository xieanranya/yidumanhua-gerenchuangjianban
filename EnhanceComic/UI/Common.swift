import SwiftUI
import UIKit
import ImageIO

// ======================================================================= 本地书库的封面缓存

/// 本地书封面在磁盘上；滚动书架时不能每次都重新解码 JPEG。
enum CoverCache {
    private static var storage: [String: UIImage] = [:]

    static func image(at url: URL?) -> UIImage? {
        guard let url else { return nil }
        if let hit = storage[url.path] { return hit }
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        storage[url.path] = image
        return image
    }

    static func invalidate(_ url: URL) {
        storage.removeValue(forKey: url.path)
    }
}

// ======================================================================= 网络图片（在线模块）

/// 在线图片加载器。
///
/// 为什么不用 `AsyncImage`：它一个请求头都不带（不少站点要 UA、有些要 Referer），
/// 也没有超时 —— 连不上的图会一直转圈，还看不到原因；更没有解码缓存，
/// 同一页滚回来要重解一次。禁漫 / 拷贝的图挂在 CDN 上，丢包时就永远停在菊花上，
/// 用户看到的正是「黑屏两个菊花，而且特别卡」。
/// 这里自己发请求：带 UA、15 秒连接 / 25 秒总超时、解码后按显示尺寸下采样、
/// NSCache 缓存、同一个 URL 的并发请求合并成一次，失败能立刻说话。
final class RemoteImageLoader {
    static let shared = RemoteImageLoader()

    private let cache = NSCache<NSString, UIImage>()
    private let lock = NSLock()
    private var running: [String: Task<UIImage?, Never>] = [:]

    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 25
        cfg.requestCachePolicy = .returnCacheDataElseLoad
        cfg.httpMaximumConnectionsPerHost = 6
        cfg.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 128 << 20)
        return URLSession(configuration: cfg)
    }()

    private static let ua =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private init() {
        cache.countLimit = 240
        cache.totalCostLimit = 96 << 20
    }

    func cached(
        _ url: String, maxPixel: CGFloat, headers: [String: String]? = nil, num: Int = 0, crop: Int = 0,
        colorize: Int = 0
    ) -> UIImage? {
        cache.object(forKey: Self.cacheKey(url, maxPixel, headers, num, crop, colorize) as NSString)
    }

    /// maxPixel 是物理像素上限：图再大也只解到这个尺寸（省内存也省 CPU）
    /// - headers: 源在 onImageLoad 里给的请求头（Referer 等），不给会被防盗链拒
    /// - num: 禁漫的分块乱序段数，>1 时按段倒序还原（不还原整页是错位横条）
    func image(
        _ url: String,
        referer: String? = nil,
        headers: [String: String]? = nil,
        num: Int = 0,
        maxPixel: CGFloat = 1200,
        crop: Int = 0,
        colorize: Int = 0
    ) async -> UIImage? {
        guard !url.isEmpty else { return nil }
        let key = Self.cacheKey(url, maxPixel, headers, num, colorize)
        if let hit = cache.object(forKey: key as NSString) { return hit }

        lock.lock()
        if let task = running[key] {
            lock.unlock()
            return await task.value
        }
        let task = Task<UIImage?, Never>.detached(priority: .userInitiated) { () -> UIImage? in
            await RemoteImageLoader.fetch(
                url,
                referer: referer,
                headers: headers,
                num: num,
                maxPixel: maxPixel,
                crop: crop,
                colorize: colorize,
                session: RemoteImageLoader.session
            )
        }
        running[key] = task
        lock.unlock()

        let image = await task.value

        lock.lock()
        running.removeValue(forKey: key)
        lock.unlock()

        if let image {
            cache.setObject(image, forKey: key as NSString, cost: Self.cost(image))
        }
        return image
    }

    private static func cacheKey(
        _ url: String, _ maxPixel: CGFloat, _ headers: [String: String]? = nil, _ num: Int = 0,
        _ colorize: Int = 0
    ) -> String {
        let bucket = Int(max(320, maxPixel) / 320)
        // 请求头会改变拿到的图（Referer 不同可能一个是 403），必须进 key，否则会串图
        var h = 0
        if let headers, !headers.isEmpty {
            for k in headers.keys.sorted() { h = h &* 31 &+ k.hashValue &+ (headers[k]?.hashValue ?? 0) }
        }
        // colorize 进 key：上色过和原图是两张图，混用会让开关看起来没反应
        return "\(bucket)|\(num)|\(colorize)|\(h)|\(url)"
    }

    private static func cost(_ image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 4 << 20 }
        return cg.bytesPerRow * cg.height
    }

    private static func fetch(
        _ url: String,
        referer: String?,
        headers: [String: String]?,
        num: Int,
        maxPixel: CGFloat,
        crop: Int,
        colorize: Int,
        session: URLSession
    ) async -> UIImage? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.timeoutInterval = 25
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
        if let referer, !referer.isEmpty {
            req.setValue(referer, forHTTPHeaderField: "Referer")
        }
        // 源自己声明的头优先（它会覆盖上面那几个默认值）
        var hasReferer = !(referer ?? "").isEmpty
        if let headers {
            for (k, v) in headers where !v.isEmpty {
                let lk = k.lowercased()
                if lk == "accept-encoding" || lk == "connection" || lk == "host" || lk == "content-length" { continue }
                if lk == "referer" { hasReferer = true }
                req.setValue(v, forHTTPHeaderField: k)
            }
        }
        // 没拿到 Referer 的（封面/缩略图），用图片自己的站补一个：防盗链多数只校验同站
        if !hasReferer {
            req.setValue("\(u.scheme ?? "https")://\(u.host ?? "")/", forHTTPHeaderField: "Referer")
        }
        do {
            let (data, resp) = try await session.data(for: req)
            if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                return nil
            }
            guard !data.isEmpty else { return nil }
            guard let img = downsample(data, maxPixel: maxPixel) else { return nil }
            let straight = num > 1 ? (descramble(img, num: num) ?? img) : img
            // 裁白边放在最后：先还原分块，再按真实画面找边界
            let cropped = crop > 0 ? cropWhite(straight, level: crop - 1) : straight
            // 黑白上色在裁白边之后：模型吃的是「干净的画面」
            guard colorize > 0 else { return cropped }
            return await ColorizeEngine.colorize(cropped)
        } catch {
            return nil
        }
    }

    /// 自动裁白边：只切「整行 / 整列都是白」的那种边。
    ///
    /// 在线源的图经常四周留白，铺到手机上就是左右两条白杠，画面又小又空。
    /// 画面里的白色气泡不会被切 —— 只要那一行有一个像素不是白的，整行就保留。
    ///
    /// - Parameter level: 0 保守 / 1 中 / 2 激进
    static func cropWhite(_ image: UIImage, level: Int) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let w = cg.width
        let h = cg.height
        guard w >= 16, h >= 16 else { return image }

        let bytesPerRow = w * 4
        var buffer = [UInt8](repeating: 0, count: bytesPerRow * h)
        guard let ctx = CGContext(
            data: &buffer,
            width: w,
            height: h,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return image }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))

        let th: UInt8 = level <= 0 ? 232 : (level >= 2 ? 250 : 244)

        func white(_ x: Int, _ y: Int) -> Bool {
            let o = y * bytesPerRow + x * 4
            return buffer[o] >= th && buffer[o + 1] >= th && buffer[o + 2] >= th
        }
        func rowWhite(_ y: Int) -> Bool {
            var x = 0
            while x < w {
                if !white(x, y) { return false }
                x += 3
            }
            return true
        }
        func colWhite(_ x: Int) -> Bool {
            var y = 0
            while y < h {
                if !white(x, y) { return false }
                y += 3
            }
            return true
        }

        var top = 0
        while top < h - 1 && rowWhite(top) { top += 1 }
        var bottom = h - 1
        while bottom > top && rowWhite(bottom) { bottom -= 1 }
        var left = 0
        while left < w - 1 && colWhite(left) { left += 1 }
        var right = w - 1
        while right > left && colWhite(right) { right -= 1 }

        let nw = right - left + 1
        let nh = bottom - top + 1
        // 裁掉一半以上多半是误判（纯白页 / 白底文字页），宁可不裁
        if CGFloat(nw) < CGFloat(w) * 0.45 || CGFloat(nh) < CGFloat(h) * 0.45 { return image }
        if left == 0 && top == 0 && nw == w && nh == h { return image }
        guard let cropped = cg.cropping(to: CGRect(x: left, y: top, width: nw, height: nh)) else {
            return image
        }
        return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
    }

    /// 禁漫把一页按高度切成 num 段再倒序拼回去。还原 = 第 num-1 段放最上、第 0 段放最下。
    /// 段划分与源里 modifyImage 的算法一致（最后一段吃掉余数）。
    private static func descramble(_ image: UIImage, num: Int) -> UIImage? {
        guard num > 1, let cg = image.cgImage else { return image }
        let w = cg.width
        let h = cg.height
        let blockSize = h / num
        guard w > 0, h > 0, blockSize > 0 else { return image }
        let remainder = h % num
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
            space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return image }

        var y = 0
        for i in stride(from: num - 1, through: 0, by: -1) {
            let start = i * blockSize
            let end = start + blockSize + (i != num - 1 ? 0 : remainder)
            let ch = end - start
            guard ch > 0, let band = cg.cropping(to: CGRect(x: 0, y: start, width: w, height: ch)) else { continue }
            // CGImage 的 crop 原点是左上；CGContext 的原点在左下，所以要翻一次
            ctx.draw(band, in: CGRect(x: 0, y: h - y - ch, width: w, height: ch))
            y += ch
        }
        guard let out = ctx.makeImage() else { return image }
        return UIImage(cgImage: out, scale: image.scale, orientation: image.imageOrientation)
    }

    private static func downsample(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        let srcOpts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, srcOpts) else { return nil }
        let thumb: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(64, maxPixel),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, thumb as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cg)
    }
}

/// 一张网络图。封面这类位置不显示菊花（失败就留底下的色块），章节页要显示。
struct RemoteImage: View {
    let url: String
    var referer: String? = nil
    var contentMode: ContentMode = .fill
    var maxPixel: CGFloat = 1200
    var spinner: Bool = false

    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else if spinner && !failed {
                ProgressView().tint(.white.opacity(0.55))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: "\(url)|\(attempt)") { await load() }
    }

    private func load() async {
        guard !url.isEmpty else {
            failed = true
            return
        }
        if let hit = RemoteImageLoader.shared.cached(url, maxPixel: maxPixel) {
            image = hit
            failed = false
            return
        }
        let got = await RemoteImageLoader.shared.image(url, referer: referer, maxPixel: maxPixel)
        if Task.isCancelled { return }
        image = got
        failed = (got == nil)
    }
}

/// 在线章节的一页：尺寸未知时先按 0.72 的宽高比占位，图到了换成真实比例。
/// 用固定 360pt 的占位块换真实高度，会让整条 LazyVStack 每来一张图就重排一次，
/// 100 多页连着来就是「很卡」的来源。
struct OnlinePageImage: View {
    let url: String
    let index: Int
    var referer: String? = nil
    var headers: [String: String]? = nil
    var descramble: Int = 0
    /// true = 适应屏幕（相册/双页）；false = 按宽度铺满（Webtoon）
    var fit: Bool = false
    /// 0 = 不裁白边；1/2/3 = 裁（等级 + 1）
    var crop: Int = 0
    /// 0 = 原图；1 = 黑白上色
    var colorize: Int = 0

    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        Group {
            if let image {
                if fit {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(
                            image.size.width / max(image.size.height, 1),
                            contentMode: .fit
                        )
                }
            } else if failed {
                VStack(spacing: 9) {
                    Text("第 \(index + 1) 页没取到")
                        .font(.system(size: 13))
                        .foregroundColor(.white.opacity(0.72))
                    Button {
                        failed = false
                        attempt += 1
                    } label: {
                        Text("点这里重试")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(Color(red: 1, green: 0.43, blue: 0.61))
                    }
                    .buttonStyle(.plain)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 260)
            } else {
                Color.black
                    .aspectRatio(0.72, contentMode: .fit)
                    .overlay(ProgressView().tint(.white.opacity(0.4)))
            }
        }
        .frame(maxWidth: .infinity)
        .task(id: "\(url)|\(descramble)|\(crop)|\(colorize)|\(attempt)") { await load() }
    }

    private func load() async {
        let width = UIScreen.main.bounds.width * max(scale, 2)
        if let hit = RemoteImageLoader.shared.cached(
            url, maxPixel: width, headers: headers, num: descramble, crop: crop, colorize: colorize
        ) {
            image = hit
            failed = false
            return
        }
        let got = await RemoteImageLoader.shared.image(
            url, referer: referer, headers: headers, num: descramble, maxPixel: width, crop: crop,
            colorize: colorize
        )
        if Task.isCancelled { return }
        image = got
        failed = (got == nil)
    }
}

enum CoverPalette {    static func typeLabel(_ type: BookType) -> String {
        switch type {
        case .zip: return "ZIP"
        case .rar: return "RAR"
        case .pdf: return "PDF"
        case .folder: return "文件夹"
        }
    }

    /// 7z / tar 解包成文件夹入库，书架上仍标原容器名，免得用户以为格式变了。
    static func label(for book: Book) -> String {
        book.sourceLabel.isEmpty ? typeLabel(book.type) : book.sourceLabel
    }
}

func appVersionText() -> String {
    let info = Bundle.main.infoDictionary
    let version = info?["CFBundleShortVersionString"] as? String ?? "?"
    let build = info?["CFBundleVersion"] as? String ?? "?"
    return "v\(version) (\(build))"
}

// ================================================================================= 公共组件

struct HLine: View {
    @Environment(\.appColors) private var c
    var body: some View {
        Rectangle().fill(c.border).frame(height: 0.5)
    }
}

/// 页面大标题。放在网格里时传 inset = false，避免和外层内边距叠成双份。
struct BigTitle: View {
    let text: String
    var sub: String? = nil
    var inset: Bool = true
    @Environment(\.appColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(text)
                .font(.system(size: 26, weight: .bold))
                .foregroundColor(c.fg)
                .tracking(-0.4)
            if let sub, !sub.isEmpty {
                Text(sub)
                    .font(.system(size: 11.5))
                    .foregroundColor(c.fg3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, inset ? 18 : 0)
        .padding(.top, 6)
        .padding(.bottom, 12)
    }
}

struct SearchStub: View {
    let text: String
    let onTap: () -> Void
    var inset: Bool = true
    @Environment(\.appColors) private var c

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                GlyphView(glyph: .search, tint: c.fg3, size: 15, strokeWidth: 1.6)
                Text(text).font(.system(size: 13)).foregroundColor(c.fg3)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 13)
            .frame(height: 40)
            .background(c.card2)
            .overlay(
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .stroke(c.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, inset ? 16 : 0)
    }
}

struct SectionHeader: View {
    let title: String
    var more: String? = nil
    var onMore: (() -> Void)? = nil
    var inset: Bool = true
    @Environment(\.appColors) private var c

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(c.fg)
                .lineLimit(1)
                .layoutPriority(1)
            Spacer(minLength: 0)
            if let more {
                Button {
                    onMore?()
                } label: {
                    Text(more + " ›")
                        .font(.system(size: 11))
                        .foregroundColor(c.fg3)
                        .lineLimit(1)
                }
                .buttonStyle(.plain)
                .disabled(onMore == nil)
            }
        }
        .padding(.horizontal, inset ? 18 : 0)
        .padding(.top, 16)
        .padding(.bottom, 9)
    }
}

/// 分区的「全部条目」列表页：发现页点「12 本 ›」进来看这一区的完整内容。
/// 聚合发现页和单源发现页共用；点击行为统一走跨源合并的 DetailView。
struct SectionListView: View {
    let title: String
    let comics: [ComicRow]
    let onBack: () -> Void

    @Environment(\.appColors) private var c

    private let columns = [
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
        GridItem(.flexible(minimum: 0, maximum: .infinity), spacing: 9),
    ]

    var body: some View {
        VStack(spacing: 0) {
            SubHeader(
                title: title,
                subtitle: "\(comics.count) 本",
                onBack: onBack,
                trailing: nil
            )
            HLine()
            if comics.isEmpty {
                Spacer()
                Text("这一区没有内容")
                    .font(.system(size: 12))
                    .foregroundColor(c.fg3)
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                        ForEach(Array(comics.enumerated()), id: \.offset) { _, comic in
                            NavigationLink {
                                DetailView(work: comic.asWork)
                            } label: {
                                ComicCell(
                                    title: comic.title,
                                    cover: comic.cover,
                                    sub: comic.sub.isEmpty ? nil : comic.sub,
                                    seed: comic.title.hashValue
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 12)
                    .padding(.bottom, 26)
                }
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

struct Pill: View {
    let text: String
    var tone: Tone = .neutral
    var fontSize: CGFloat = 10

    @Environment(\.appColors) private var c

    var body: some View {
        Text(text)            .font(.system(size: fontSize, weight: .medium))
            .foregroundColor(tone.fg(c))
            .lineLimit(1)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background(tone.bg(c))
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

struct MiniButton: View {
    let text: String
    var accent: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    @Environment(\.appColors) private var c

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(accent ? c.accentInk : c.fg)
                .padding(.horizontal, 13)
                .padding(.vertical, 7)
                .background(accent ? c.accent : c.chip)
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(accent ? Color.clear : c.border, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

struct Chip: View {
    let text: String
    let selected: Bool
    let action: () -> Void

    @Environment(\.appColors) private var c

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 12, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? c.accentInk : c.fg2)
                .lineLimit(1)
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(selected ? c.accent : c.chip)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// 网络封面：先铺一个哈希色块，图到了盖上去。图挂了就留色块 + 书名首字。
struct RemoteCover: View {
    let url: String
    let title: String
    let seed: Int
    var badge: String? = nil

    @Environment(\.appColors) private var c

    var body: some View {
        ZStack {
            coverGradient(seed: seed, dark: c.dark)
            // 首字一直垫在底下：图没来（或来不了）时它就是封面，图到了直接盖住。
            initial
            if !url.isEmpty {
                RemoteImage(url: url, contentMode: .fill, maxPixel: 600)
            }
        }
        .clipped()
        .overlay(alignment: .bottomTrailing) {
            if let badge, !badge.isEmpty {
                Text(badge)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundColor(c.fg)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1.5)
                    .background(c.bg.opacity(0.78))
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .padding(5)
            }
        }
    }

    private var initial: some View {
        Text(String(title.prefix(1)))
            .font(.system(size: 22, weight: .bold))
            .foregroundColor(placeholderInk(c.dark))
    }
}

/// 三列网格里的一格：封面 + 书名 +（可选）副标题。
struct ComicCell: View {
    let title: String
    let cover: String
    var sub: String? = nil
    var badge: String? = nil
    let seed: Int

    @Environment(\.appColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Color.clear
                .aspectRatio(0.72, contentMode: .fit)
                .overlay { RemoteCover(url: cover, title: title, seed: seed, badge: badge) }
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .stroke(c.border, lineWidth: 0.5)
                )
            Text(title)
                .font(.system(size: 11.5))
                .foregroundColor(c.fg)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let sub, !sub.isEmpty {
                Text(sub)
                    .font(.system(size: 9.5))
                    .foregroundColor(c.fg3)
                    .lineLimit(1)
            }
        }
    }
}

struct NoteBar: View {
    let text: String
    var tone: Tone = .neutral
    var inset: Bool = true

    @Environment(\.appColors) private var c

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(tone.fg(c))
                .frame(width: 5, height: 5)
            Text(text)
                .font(.system(size: 11))
                .foregroundColor(c.fg2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(tone.bg(c))
        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
        .padding(.horizontal, inset ? 18 : 0)
    }
}

struct EmptyHint: View {
    let title: String
    let message: String
    var glyph: Glyph = .search

    @Environment(\.appColors) private var c

    var body: some View {
        VStack(spacing: 9) {
            GlyphView(glyph: glyph, tint: c.fg3, size: 34, strokeWidth: 1.3)
                .padding(.bottom, 2)
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(c.fg)
            Text(message)
                .font(.system(size: 11.5))
                .foregroundColor(c.fg3)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 40)
        .padding(.vertical, 46)
    }
}

struct LoadingBox: View {
    var height: CGFloat = 120
    @Environment(\.appColors) private var c

    var body: some View {
        ProgressView()
            .tint(c.accent)
            .frame(maxWidth: .infinity)
            .frame(height: height)
    }
}

/// 「继续阅读」横卡：本地书和在线记录共用一个样子。
/// 本身不带点击（外面可能要用 NavigationLink 包一层），呼叫方自己决定怎么响应。
struct ContinueCard: View {
    let title: String
    let meta: String
    let label: String
    let coverURL: String?
    let localCover: UIImage?
    let seed: Int

    var inset: Bool = true

    @Environment(\.appColors) private var c

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                coverGradient(seed: seed, dark: c.dark)
                if let localCover {
                    Image(uiImage: localCover).resizable().scaledToFill()
                } else if let coverURL, !coverURL.isEmpty {
                    RemoteImage(url: coverURL, contentMode: .fill, maxPixel: 320)
                } else {
                    Text(String(title.prefix(1)))
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(placeholderInk(c.dark))
                }
            }
            .frame(width: 52, height: 70)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(c.accent)
                Text(title)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(c.fg)
                    .lineLimit(1)
                Text(meta)
                    .font(.system(size: 10.5))
                    .foregroundColor(c.fg3)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            GlyphView(glyph: .right, tint: c.fg3, size: 15, strokeWidth: 1.6)
        }
        .padding(10)
        .background(c.card2)
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(c.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .contentShape(Rectangle())
        .padding(.horizontal, inset ? 18 : 0)
    }
}

/// 二级页的顶栏：返回箭头 + 标题 + 右侧操作。工程里所有导航都用自绘顶栏，
/// 好处是颜色跟主题完全一致，不会突然冒出一条系统半透明导航条。
struct SubHeader: View {
    let title: String
    var subtitle: String? = nil
    let onBack: (() -> Void)?
    var trailing: AnyView? = nil

    @Environment(\.appColors) private var c

    var body: some View {
        HStack(spacing: 10) {
            if let onBack {
                Button(action: onBack) {
                    GlyphView(glyph: .back, tint: c.fg, size: 19, strokeWidth: 1.8)
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(c.fg)
                    .lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 10.5))
                        .foregroundColor(c.fg3)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let trailing { trailing }
        }
        .padding(.horizontal, 14)
        .padding(.top, 4)
        .padding(.bottom, 8)
    }
}
