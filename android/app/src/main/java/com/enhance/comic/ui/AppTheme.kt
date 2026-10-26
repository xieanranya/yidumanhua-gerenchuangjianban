package com.enhance.comic.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 外观三档。开关只放在「我的 → 外观」，阅读界面里不放。 */
enum class ThemeMode(val label: String) {
    AUTO("跟随系统"),
    DAY("白天"),
    NIGHT("夜晚"),
}

/**
 * 全套界面色。纯黑 / 纯白打底（用户否掉过紫黑、蓝黑、棕黑），
 * 强调色只有一个：PicaComic 粉。
 */
data class AppColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val card2: Color,
    val border: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val chip: Color,
    val accent: Color,
    val accentInk: Color,
    val ok: Color,
    val okBg: Color,
    val warn: Color,
    val warnBg: Color,
    val bad: Color,
    val badBg: Color,
)

val NightColors = AppColors(
    dark = true,
    bg = Color(0xFF000000),
    card = Color(0xFF000000),
    card2 = Color(0xFF0A0A0A),
    border = Color(0xFF262626),
    fg = Color(0xFFFAFAFA),
    fg2 = Color(0xFFA3A3A3),
    fg3 = Color(0xFF6B6B6B),
    chip = Color(0xFF171717),
    accent = Color(0xFFFF6E9C),
    accentInk = Color(0xFF14060C),
    ok = Color(0xFF4ADE80),
    okBg = Color(0x214ADE80),
    warn = Color(0xFFFBBF24),
    warnBg = Color(0x21FBBF24),
    bad = Color(0xFFF87171),
    badBg = Color(0x21F87171),
)

val DayColors = AppColors(
    dark = false,
    bg = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    card2 = Color(0xFFF7F7F7),
    border = Color(0xFFE2E2E2),
    fg = Color(0xFF111111),
    fg2 = Color(0xFF636363),
    fg3 = Color(0xFF9B9B9B),
    chip = Color(0xFFF4F4F4),
    accent = Color(0xFFE0336E),
    accentInk = Color(0xFFFFFFFF),
    ok = Color(0xFF15803D),
    okBg = Color(0x1715803D),
    warn = Color(0xFFB45309),
    warnBg = Color(0x17B45309),
    bad = Color(0xFFC0392B),
    badBg = Color(0x17C0392B),
)

fun colorsFor(dark: Boolean): AppColors = if (dark) NightColors else DayColors

val LocalColors = staticCompositionLocalOf { NightColors }

/** Material 组件（进度条、输入框、对话框）跟着同一套色走，免得出现白框紫按钮。 */
fun schemeFor(dark: Boolean): ColorScheme {
    val c = colorsFor(dark)
    return if (dark) {
        darkColorScheme(
            primary = c.accent,
            onPrimary = c.accentInk,
            primaryContainer = Color(0x33FF6E9C),
            onPrimaryContainer = c.accent,
            secondary = c.fg2,
            onSecondary = c.bg,
            secondaryContainer = c.card2,
            onSecondaryContainer = c.fg2,
            background = c.bg,
            onBackground = c.fg,
            surface = c.bg,
            onSurface = c.fg,
            surfaceVariant = c.card2,
            onSurfaceVariant = c.fg2,
            surfaceContainer = c.card2,
            surfaceContainerHigh = c.chip,
            surfaceContainerLow = c.card,
            outline = c.fg3,
            outlineVariant = c.border,
            error = c.bad,
            onError = c.bg,
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            onPrimary = c.accentInk,
            primaryContainer = Color(0x1FE0336E),
            onPrimaryContainer = c.accent,
            secondary = c.fg2,
            onSecondary = c.bg,
            secondaryContainer = c.card2,
            onSecondaryContainer = c.fg2,
            background = c.bg,
            onBackground = c.fg,
            surface = c.bg,
            onSurface = c.fg,
            surfaceVariant = c.card2,
            onSurfaceVariant = c.fg2,
            surfaceContainer = c.card2,
            surfaceContainerHigh = c.chip,
            surfaceContainerLow = c.card,
            outline = c.fg3,
            outlineVariant = c.border,
            error = c.bad,
            onError = c.bg,
        )
    }
}

/**
 * 封面没生成出来时的占位块：按书名哈希取一个色相，饱和度压得很低，
 * 夜里是暗色块、白天是浅色块，都不会抢真封面的风头。
 */
fun coverBrush(seed: Int, dark: Boolean): Brush {
    val hue = ((seed * 47) % 360 + 360) % 360
    return if (dark) {
        Brush.linearGradient(
            listOf(
                Color.hsl(hue.toFloat(), 0.24f, 0.23f),
                Color.hsl(((hue + 40) % 360).toFloat(), 0.20f, 0.12f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.hsl(hue.toFloat(), 0.20f, 0.87f),
                Color.hsl(((hue + 40) % 360).toFloat(), 0.16f, 0.77f),
            ),
        )
    }
}

fun placeholderInk(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.34f)
