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

    func cached(_ url: String, maxPixel: CGFloat) -> UIImage? {
        cache.object(forKey: Self.cacheKey(url, maxPixel) as NSString)
    }

    /// maxPixel 是物理像素上限：图再大也只解到这个尺寸（省内存也省 CPU）
    func image(_ url: String, referer: String? = nil, maxPixel: CGFloat = 1200) async -> UIImage? {
        guard !url.isEmpty else { return nil }
        let key = Self.cacheKey(url, maxPixel)
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
                maxPixel: maxPixel,
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

    private static func cacheKey(_ url: String, _ maxPixel: CGFloat) -> String {
        let bucket = Int(max(320, maxPixel) / 320)
        return "\(bucket)|\(url)"
    }

    private static func cost(_ image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 4 << 20 }
        return cg.bytesPerRow * cg.height
    }

    private static func fetch(
        _ url: String,
        referer: String?,
        maxPixel: CGFloat,
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
        do {
            let (data, resp) = try await session.data(for: req)
            if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                return nil
            }
            guard !data.isEmpty else { return nil }
            return downsample(data, maxPixel: maxPixel)
        } catch {
            return nil
        }
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

    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(
                        image.size.width / max(image.size.height, 1),
                        contentMode: .fit
                    )
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
        .task(id: "\(url)|\(attempt)") { await load() }
    }

    private func load() async {
        let width = UIScreen.main.bounds.width * max(scale, 2)
        if let hit = RemoteImageLoader.shared.cached(url, maxPixel: width) {
            image = hit
            failed = false
            return
        }
        let got = await RemoteImageLoader.shared.image(url, referer: referer, maxPixel: width)
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

struct Pill: View {
    let text: String
    var tone: Tone = .neutral
    var fontSize: CGFloat = 10

    @Environment(\.appColors) private var c

    var body: some View {
        Text(text)
            .font(.system(size: fontSize, weight: .medium))
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
