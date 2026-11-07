import SwiftUI
import UIKit

// ================================================================================== 主题

/// 外观三档。开关只放在「我的 → 外观」，阅读界面里不放。
enum ThemeMode: String, Codable, CaseIterable, Identifiable {
    case auto
    case day
    case night

    var id: String { rawValue }

    var label: String {
        switch self {
        case .auto: return "跟随系统"
        case .day: return "白天"
        case .night: return "夜晚"
        }
    }
}

/// 状态语义色，只用在圆点和小标签上。
enum Tone {
    case neutral
    case accent
    case ok
    case warn
    case bad

    func fg(_ c: AppColors) -> Color {
        switch self {
        case .neutral: return c.fg2
        case .accent: return c.accent
        case .ok: return c.ok
        case .warn: return c.warn
        case .bad: return c.bad
        }
    }

    func bg(_ c: AppColors) -> Color {
        switch self {
        case .neutral: return c.chip
        case .accent: return c.accent.opacity(0.14)
        case .ok: return c.okBg
        case .warn: return c.warnBg
        case .bad: return c.badBg
        }
    }
}

/// 全套界面色。纯黑 / 纯白打底，强调色只有一个：PicaComic 粉。
/// 数值与 Android 的 `AppTheme.kt` 一一对应，两端看起来必须是同一套。
struct AppColors {
    let dark: Bool
    let bg: Color
    let card: Color
    let card2: Color
    let border: Color
    let fg: Color
    let fg2: Color
    let fg3: Color
    let chip: Color
    let accent: Color
    let accentInk: Color
    let ok: Color
    let okBg: Color
    let warn: Color
    let warnBg: Color
    let bad: Color
    let badBg: Color

    static let night = AppColors(
        dark: true,
        bg: Color(hex: 0x000000),
        card: Color(hex: 0x000000),
        card2: Color(hex: 0x0A0A0A),
        border: Color(hex: 0x262626),
        fg: Color(hex: 0xFAFAFA),
        fg2: Color(hex: 0xA3A3A3),
        fg3: Color(hex: 0x6B6B6B),
        chip: Color(hex: 0x171717),
        accent: Color(hex: 0xFF6E9C),
        accentInk: Color(hex: 0x14060C),
        ok: Color(hex: 0x4ADE80),
        okBg: Color(hex: 0x4ADE80).opacity(0.13),
        warn: Color(hex: 0xFBBF24),
        warnBg: Color(hex: 0xFBBF24).opacity(0.13),
        bad: Color(hex: 0xF87171),
        badBg: Color(hex: 0xF87171).opacity(0.13)
    )

    static let day = AppColors(
        dark: false,
        // v3 沉浸式：内容底不再是纯白，是 #F4F4F7 的浅灰底，卡片浮在上面。
        // （和网页版交互稿一致；Android 的 AppTheme.kt 对齐时同步这两个值）
        bg: Color(hex: 0xF4F4F7),
        card: Color(hex: 0xFFFFFF),
        card2: Color(hex: 0xFFFFFF),
        border: Color(hex: 0xE4E4E9),
        fg: Color(hex: 0x111111),
        fg2: Color(hex: 0x636363),
        fg3: Color(hex: 0x9B9B9B),
        chip: Color(hex: 0xEAEAEE),
        accent: Color(hex: 0xE0336E),
        accentInk: Color(hex: 0xFFFFFF),
        ok: Color(hex: 0x15803D),
        okBg: Color(hex: 0x15803D).opacity(0.09),
        warn: Color(hex: 0xB45309),
        warnBg: Color(hex: 0xB45309).opacity(0.09),
        bad: Color(hex: 0xC0392B),
        badBg: Color(hex: 0xC0392B).opacity(0.09)
    )

    static func of(_ dark: Bool) -> AppColors { dark ? night : day }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: 1
        )
    }
}

private struct AppColorsKey: EnvironmentKey {
    static let defaultValue = AppColors.night
}

extension EnvironmentValues {
    /// 页面里统一用 `@Environment(\.appColors)` 取色，别直接读系统语义色。
    var appColors: AppColors {
        get { self[AppColorsKey.self] }
        set { self[AppColorsKey.self] = newValue }
    }
}

/// 封面没生成出来时的占位块：按书名哈希取色相，饱和度压得很低，
/// 夜里是暗色块、白天是浅色块，都不抢真封面的风头。
func coverGradient(seed: Int, dark: Bool) -> LinearGradient {
    let raw = ((seed &* 47) % 360 + 360) % 360
    let a = Double(raw) / 360.0
    let b = Double((raw + 40) % 360) / 360.0
    let colors: [Color] = dark
        ? [Color(hue: a, saturation: 0.24, brightness: 0.23),
           Color(hue: b, saturation: 0.20, brightness: 0.12)]
        : [Color(hue: a, saturation: 0.20, brightness: 0.87),
           Color(hue: b, saturation: 0.16, brightness: 0.77)]
    return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
}

func placeholderInk(_ dark: Bool) -> Color {
    dark ? Color.white.opacity(0.26) : Color.black.opacity(0.34)
}

/// 把主题选到 Material/SwiftUI 的控件上，免得系统控件还是默认蓝。
extension View {
    func appTint(_ c: AppColors) -> some View { self.tint(c.accent) }
}

// ================================================================== v3 沉浸式头图
//
// 视觉规则（网页版交互稿 ui-preview/v3-interactive.html）：
//   封面大图一直铺到状态栏后面，白色大标题压图，内容卡片悬浮在头图边缘，
//   头图底部渐变融进内容底色。上滚时图比内容滚得慢（视差下沉），标题随之淡出。

/// 沉浸头图。宿主 ScrollView 必须挂：
///   `.coordinateSpace(name: "immersiveScroll")` + `.ignoresSafeArea(edges: .top)`
struct ImmersiveHeader<Background: View>: View {

    var height: CGFloat = 286
    let title: String
    var subtitle: String? = nil
    @ViewBuilder let background: () -> Background

    @Environment(\.appColors) private var c

    var body: some View {
        GeometryReader { geo in
            let minY = geo.frame(in: .named("immersiveScroll")).minY
            let stretch = max(0, minY)            // 下拉：头图跟着手指拉长
            let sink = max(0, -minY)              // 上滚的滚动量
            let drift = min(sink * 0.42, 56)      // 图比内容滚得慢，最多沉 56pt
            ZStack(alignment: .bottomLeading) {
                background()
                    // 底料多留 64pt：上滚下沉时图顶永远不会露进视口，不会出现缝
                    .frame(width: geo.size.width, height: height + stretch + 64)
                    .clipped()
                    .offset(y: -stretch + drift)

                // 固定遮罩（不跟视差）：顶部压暗保证状态栏可读，底部融进内容底色
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.40), location: 0),
                        .init(color: .black.opacity(0.15), location: 0.30),
                        .init(color: .clear, location: 0.50),
                        .init(color: fadeColor, location: 0.98),
                    ],
                    startPoint: .top, endPoint: .bottom
                )
                .allowsHitTesting(false)

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 29, weight: .bold))
                        .foregroundColor(.white)
                        .tracking(-0.7)
                        .shadow(color: .black.opacity(0.5), radius: 9, y: 2)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 11))
                            .foregroundColor(.white.opacity(0.85))
                            .shadow(color: .black.opacity(0.4), radius: 5, y: 1)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 50)
                .opacity(1 - min(1, sink / 150))
            }
            .frame(width: geo.size.width, height: height, alignment: .bottom)
        }
        .frame(height: height)
    }

    /// 头图底部融进的内容底色：夜里纯黑、白天浅灰
    private var fadeColor: Color { c.dark ? .black : Color(hex: 0xF4F4F7) }
}

/// 头图上的毛玻璃圆钮（搜索 / 返回 / 账号）。
struct GlassCircleLabel: View {
    let glyph: Glyph
    var body: some View {
        GlyphView(glyph: glyph, tint: .white, size: 15, strokeWidth: 1.9)
            .frame(width: 33, height: 33)
            .background(Circle().fill(Color.black.opacity(0.26)))
            .background(.ultraThinMaterial, in: Circle())
            .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 1))
    }
}

struct GlassCircleButton: View {
    let glyph: Glyph
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            GlassCircleLabel(glyph: glyph)
        }
        .buttonStyle(.plain)
    }
}

/// 头图的氛围渐变（没有真封面时兜底）：斜向渐变 + 右上高光，跟封面图一个气质。
struct ImmersivePoster: View {
    let colors: [Color]
    var body: some View {
        ZStack {
            LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
            RadialGradient(
                colors: [Color.white.opacity(0.30), Color.white.opacity(0.0)],
                center: UnitPoint(x: 0.74, y: 0.06),
                startRadius: 0, endRadius: 330
            )
        }
    }
}

/// 头图渐变色板（对照网页版交互稿的 g1–g9）。
enum PosterPalette {
    static let violet  = [Color(hex: 0x3B2F63), Color(hex: 0x7B4BB7), Color(hex: 0xC86DD7)] // 发现
    static let rose    = [Color(hex: 0x5B1E36), Color(hex: 0xC0336D), Color(hex: 0xFF8BB0)] // 我的
    static let forest  = [Color(hex: 0x123C2B), Color(hex: 0x1F9E6E), Color(hex: 0x7FF2C3)] // 首页兜底
    static let iris    = [Color(hex: 0x2C1A4D), Color(hex: 0x6A4FBF), Color(hex: 0xD3A5FF)] // 书架兜底
    static let ember   = [Color(hex: 0x6B3212), Color(hex: 0xE07F2E), Color(hex: 0xFFD08A)]
    static let steel   = [Color(hex: 0x232526), Color(hex: 0x414345), Color(hex: 0x8E9EAB)]
    static let azure   = [Color(hex: 0x0F3460), Color(hex: 0x3A7BD5), Color(hex: 0x7FD7FF)]
    static let crimson = [Color(hex: 0x7A1230), Color(hex: 0xE0455E), Color(hex: 0xFFB08A)]
    static let ocean   = [Color(hex: 0x0B2545), Color(hex: 0x1D7FA8), Color(hex: 0x88E0F0)]
    static let all: [[Color]] = [violet, rose, forest, iris, ember, steel, azure, crimson, ocean]

    /// 按任意 seed 取一组稳定色（同一源/同一本书每次进来颜色一致）
    static func bySeed(_ seed: Int) -> [Color] {
        all[((seed % all.count) + all.count) % all.count]
    }
}

// ================================================================== 状态栏白字

/// 全局唯一的状态栏控制器。挂在 AppRootView 背景里，读 ui.statusBarLight。
/// 不能按页面挂多个 —— 系统只会问其中最上层那个，push 之后就乱了；
/// 单点 + 页面 onAppear/onDisappear 显式声明，行为完全可控。
final class StatusBarLightControllerVC: UIViewController {
    var light: Bool = false {
        didSet {
            if light != oldValue { setNeedsStatusBarAppearanceUpdate() }
        }
    }
    override var preferredStatusBarStyle: UIStatusBarStyle {
        light ? .lightContent : .default
    }
}

struct StatusBarLightController: UIViewControllerRepresentable {
    let light: Bool

    func makeUIViewController(context: Context) -> StatusBarLightControllerVC {
        let vc = StatusBarLightControllerVC()
        vc.light = light
        vc.view.backgroundColor = .clear
        return vc
    }

    func updateUIViewController(_ vc: StatusBarLightControllerVC, context: Context) {
        vc.light = light
    }
}

extension View {
    /// 声明本页面可见期间的状态栏样式。沉浸头图页用默认 light = true（白字压图）；
    /// 从沉浸页弹出的普通 sheet 用 light = false，关闭时自动交还。
    func immersiveStatusBar(_ ui: UiStore, light: Bool) -> some View {
        onAppear { ui.statusBarLight = light }
            .onDisappear { ui.statusBarLight = !light }
    }

    func immersiveStatusBar(_ ui: UiStore) -> some View {
        immersiveStatusBar(ui, light: true)
    }
}

/// 头图悬浮圆钮的顶部起点：状态栏实际高度 + 8pt。
/// 固定值会在灵动岛机型（54pt）上压到岛，老机型（44pt）又空一截。
func safeTop() -> CGFloat {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    for scene in scenes {
        for window in scene.windows where window.isKeyWindow {
            return window.safeAreaInsets.top
        }
    }
    return 54
}
