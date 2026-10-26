import SwiftUI

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
        bg: Color(hex: 0xFFFFFF),
        card: Color(hex: 0xFFFFFF),
        card2: Color(hex: 0xF7F7F7),
        border: Color(hex: 0xE2E2E2),
        fg: Color(hex: 0x111111),
        fg2: Color(hex: 0x636363),
        fg3: Color(hex: 0x9B9B9B),
        chip: Color(hex: 0xF4F4F4),
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
