package com.enhance.comic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.ReaderPrefs
import com.enhance.comic.model.ReadingMode
import com.enhance.comic.model.ScaleFilter
import com.enhance.comic.model.ScaleMode

// ===================================================================================== theme

/**
 * One palette for the whole reader chrome. Two instances exist: the near-black one from the
 * reference reader's dark mode and a paper-white one for reading outdoors.
 */
internal data class Skin(
    val chrome: Color,
    val bar: Color,
    val panel: Color,
    val menu: Color,
    val fg: Color,
    val dim: Color,
    val hair: Color,
    val track: Color,
    val accent: Color,
    val onAccent: Color = Color.White,
)

internal val DarkSkin = Skin(
    chrome = Color(0xFF000000),
    bar = Color(0xFF0A0A0A),
    panel = Color(0xFF0F0F0F),
    menu = Color(0xFF111111),
    fg = Color(0xFFFAFAFA),
    dim = Color(0xFFA3A3A3),
    hair = Color(0xFF262626),
    track = Color(0x33FFFFFF),
    accent = Color(0xFFFF6E9C),
    onAccent = Color(0xFF14060C),
)

internal val LightSkin = Skin(
    chrome = Color(0xFFFFFFFF),
    bar = Color(0xFFFFFFFF),
    panel = Color(0xFFF7F7F7),
    menu = Color(0xFFFFFFFF),
    fg = Color(0xFF111111),
    dim = Color(0xFF636363),
    hair = Color(0xFFE2E2E2),
    track = Color(0x22000000),
    accent = Color(0xFFE0336E),
    onAccent = Color(0xFFFFFFFF),
)

internal fun skinFor(light: Boolean): Skin = if (light) LightSkin else DarkSkin

/** Reading panels, one per tool icon. */
internal enum class ReaderPanel { NONE, GAP, FILTER, TURN, LAYOUT, BOOKMARK, SETTINGS }

// ==================================================================================== glyphs

internal enum class Glyph {
    GAP, FILTER, TURN, LAYOUT, BOOKMARK, GEAR, LIST, THEME,
    BACK, MORE, CHECK, LEFT, RIGHT, SUN, CROP, SPARK,
    /** 底部四个 Tab 与通用图标 */
    HOME, DISCOVER, SHELF, USER, SEARCH,
}

/**
 * Thin line icons drawn by hand in a 24x24 grid. Material's filled glyphs at 20dp read as
 * heavy blobs next to the reference reader's hairlines, so every icon here is a stroke.
 */
@Composable
internal fun GlyphView(
    glyph: Glyph,
    tint: Color,
    size: Dp = 22.dp,
    stroke: Dp = 1.5.dp,
) {
    Canvas(Modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val s = stroke.toPx()
        fun pt(x: Float, y: Float) = Offset(x * k, y * k)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = tint,
            start = pt(x1, y1),
            end = pt(x2, y2),
            strokeWidth = s,
            cap = StrokeCap.Round,
        )
        fun path(vararg pts: Float, close: Boolean = false) {
            val p = Path()
            p.moveTo(pts[0] * k, pts[1] * k)
            var i = 2
            while (i + 1 < pts.size) {
                p.lineTo(pts[i] * k, pts[i + 1] * k)
                i += 2
            }
            if (close) p.close()
            drawPath(p, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun arc(cx: Float, cy: Float, r: Float, from: Float, sweep: Float, filled: Boolean) =
            drawArc(
                color = tint,
                startAngle = from,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = pt(cx - r, cy - r),
                size = Size(2 * r * k, 2 * r * k),
                style = if (filled) Fill else Stroke(width = s),
            )
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(
            color = tint,
            radius = r * k,
            center = pt(cx, cy),
            style = Stroke(width = s),
        )
        fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * k, pt(cx, cy))

        when (glyph) {
            Glyph.GAP -> {
                line(4f, 6.5f, 20f, 6.5f)
                line(4f, 17.5f, 20f, 17.5f)
                line(12f, 9.2f, 12f, 14.8f)
                path(9.9f, 11.2f, 12f, 9.1f, 14.1f, 11.2f)
                path(9.9f, 12.8f, 12f, 14.9f, 14.1f, 12.8f)
            }

            Glyph.FILTER -> {
                val p = Path()
                p.moveTo(12f * k, 3.2f * k)
                p.cubicTo(12f * k, 3.2f * k, 6f * k, 10.2f * k, 6f * k, 14.6f * k)
                p.cubicTo(6f * k, 17.9f * k, 8.7f * k, 20.6f * k, 12f * k, 20.6f * k)
                p.cubicTo(15.3f * k, 20.6f * k, 18f * k, 17.9f * k, 18f * k, 14.6f * k)
                p.cubicTo(18f * k, 10.2f * k, 12f * k, 3.2f * k, 12f * k, 3.2f * k)
                p.close()
                drawPath(p, tint, style = Stroke(width = s, join = StrokeJoin.Round))
            }

            Glyph.TURN -> {
                line(3.6f, 9f, 20.4f, 9f)
                path(17.7f, 6.3f, 20.4f, 9f, 17.7f, 11.7f)
                line(20.4f, 15f, 3.6f, 15f)
                path(6.3f, 12.3f, 3.6f, 15f, 6.3f, 17.7f)
            }

            Glyph.LAYOUT -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(3.6f, 4.2f),
                    size = Size(16.8f * k, 15.6f * k),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * k, 2f * k),
                    style = Stroke(width = s),
                )
                line(12f, 4.2f, 12f, 19.8f)
            }

            Glyph.BOOKMARK -> path(7f, 4f, 17f, 4f, 17f, 20.2f, 12f, 16.2f, 7f, 20.2f, close = true)

            Glyph.GEAR -> {
                ring(12f, 12f, 3.1f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = kotlin.math.cos(a).toFloat()
                    val sn = kotlin.math.sin(a).toFloat()
                    line(12f + c * 5.4f, 12f + sn * 5.4f, 12f + c * 7.6f, 12f + sn * 7.6f)
                }
            }

            Glyph.LIST -> {
                for (y in floatArrayOf(6.8f, 12f, 17.2f)) {
                    dot(5f, y, 1.15f)
                    line(8.6f, y, 20f, y)
                }
            }

            Glyph.THEME -> {
                ring(12f, 12f, 7.2f)
                arc(12f, 12f, 7.2f, -90f, 180f, filled = true)
            }

            Glyph.BACK -> path(14.6f, 4.4f, 7.4f, 12f, 14.6f, 19.6f)

            Glyph.MORE -> {
                dot(12f, 5.2f, 1.45f)
                dot(12f, 12f, 1.45f)
                dot(12f, 18.8f, 1.45f)
            }

            Glyph.CHECK -> path(4.8f, 12.6f, 9.8f, 17.4f, 19.2f, 7f)

            Glyph.LEFT -> path(14.4f, 5f, 7.6f, 12f, 14.4f, 19f)

            Glyph.RIGHT -> path(9.6f, 5f, 16.4f, 12f, 9.6f, 19f)

            Glyph.SUN -> {
                ring(12f, 12f, 3.7f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = kotlin.math.cos(a).toFloat()
                    val sn = kotlin.math.sin(a).toFloat()
                    line(12f + c * 6.1f, 12f + sn * 6.1f, 12f + c * 8.3f, 12f + sn * 8.3f)
                }
            }

            Glyph.CROP -> {
                path(7f, 3.4f, 7f, 17f, 20.6f, 17f)
                path(3.4f, 7f, 17f, 7f, 17f, 20.6f)
            }

            Glyph.SPARK -> {
                val p = Path()
                p.moveTo(12f * k, 3.2f * k)
                p.lineTo(13.9f * k, 9.4f * k)
                p.lineTo(20.2f * k, 11.2f * k)
                p.lineTo(13.9f * k, 13f * k)
                p.lineTo(12f * k, 19.2f * k)
                p.lineTo(10.1f * k, 13f * k)
                p.lineTo(3.8f * k, 11.2f * k)
                p.lineTo(10.1f * k, 9.4f * k)
                p.close()
                drawPath(p, tint, style = Stroke(width = s, join = StrokeJoin.Round))
            }

            Glyph.HOME -> {
                path(3.4f, 10.2f, 12f, 3.4f, 20.6f, 10.2f)
                path(5.9f, 9.2f, 5.9f, 20.4f, 18.1f, 20.4f, 18.1f, 9.2f)
                line(10.2f, 20.4f, 10.2f, 14.6f)
                line(13.8f, 20.4f, 13.8f, 14.6f)
            }

            Glyph.DISCOVER -> {
                ring(12f, 12f, 8.4f)
                path(15.6f, 8.4f, 13.2f, 13.2f, 8.4f, 15.6f, 10.8f, 10.8f, close = true)
            }

            Glyph.SHELF -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(3.8f, 4.4f),
                    size = Size(6.6f * k, 15.2f * k),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * k, 1.2f * k),
                    style = Stroke(width = s),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = pt(13.6f, 4.4f),
                    size = Size(6.6f * k, 15.2f * k),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * k, 1.2f * k),
                    style = Stroke(width = s),
                )
            }

            Glyph.USER -> {
                ring(12f, 8.2f, 3.7f)
                val p = Path()
                p.moveTo(4.4f * k, 20.6f * k)
                p.cubicTo(5f * k, 16.4f * k, 8.1f * k, 14.4f * k, 12f * k, 14.4f * k)
                p.cubicTo(15.9f * k, 14.4f * k, 19f * k, 16.4f * k, 19.6f * k, 20.6f * k)
                drawPath(p, tint, style = Stroke(width = s, cap = StrokeCap.Round))
            }

            Glyph.SEARCH -> {
                ring(10.8f, 10.8f, 6.3f)
                line(15.4f, 15.4f, 20.2f, 20.2f)
            }
        }
    }
}

// ================================================================================= top chrome

/** A tool icon plus its 9sp caption, as in the reference reader's strip. */
@Composable
private fun ToolItem(
    glyph: Glyph,
    label: String,
    active: Boolean,
    skin: Skin,
    onClick: () -> Unit,
) {
    val tint = if (active) skin.accent else skin.fg
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 5.dp),
    ) {
        GlyphView(glyph, tint, size = 21.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (active) skin.accent else skin.dim,
            fontSize = 8.5.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ReaderTopChrome(
    title: String,
    pageLabel: String,
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    panel: ReaderPanel,
    marked: Boolean,
    hasChapters: Boolean,
    onPanel: (ReaderPanel) -> Unit,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onOpenChapters: () -> Unit,
    onAddBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onPrefs: (ReaderPrefs) -> Unit,
    onEnhance: (EnhanceSettings) -> Unit,
    /** 在线看的：没有翻页方式/双页/书签这些东西（竖向滚动，也没有本地书签表） */
    online: Boolean = false,
) {
    val skin = skinFor(prefs.chromeLight)

    Column(Modifier.fillMaxWidth().background(skin.chrome).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { GlyphView(Glyph.BACK, skin.fg, 21.dp) }

            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = title,
                    color = skin.fg,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = pageLabel, color = skin.dim, fontSize = 10.5.sp, maxLines = 1)
            }

            if (!online) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onMenu),
                    contentAlignment = Alignment.Center,
                ) { GlyphView(Glyph.MORE, skin.fg, 21.dp) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).background(skin.bar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolItem(Glyph.GAP, "页面间距", panel == ReaderPanel.GAP, skin) {
                    onPanel(if (panel == ReaderPanel.GAP) ReaderPanel.NONE else ReaderPanel.GAP)
                }
                ToolItem(
                    Glyph.FILTER,
                    "滤镜",
                    panel == ReaderPanel.FILTER ||
                        prefs.grayscale || prefs.invert || prefs.blueLight > 0 ||
                        enhance.sharpen || enhance.contrastPass,
                    skin,
                ) { onPanel(if (panel == ReaderPanel.FILTER) ReaderPanel.NONE else ReaderPanel.FILTER) }
                if (!online) {
                    ToolItem(Glyph.TURN, "翻页方式", panel == ReaderPanel.TURN, skin) {
                        onPanel(if (panel == ReaderPanel.TURN) ReaderPanel.NONE else ReaderPanel.TURN)
                    }
                    ToolItem(
                        Glyph.LAYOUT,
                        "页面布局",
                        panel == ReaderPanel.LAYOUT || prefs.doublePage || prefs.scaleMode != ScaleMode.FIT,
                        skin,
                    ) { onPanel(if (panel == ReaderPanel.LAYOUT) ReaderPanel.NONE else ReaderPanel.LAYOUT) }
                    ToolItem(Glyph.BOOKMARK, "书签", marked, skin) {
                        onPanel(
                            if (panel == ReaderPanel.BOOKMARK) ReaderPanel.NONE else ReaderPanel.BOOKMARK,
                        )
                    }
                }
                if (hasChapters) {
                    ToolItem(Glyph.LIST, "目录", false, skin) { onOpenChapters() }
                }
                ToolItem(Glyph.GEAR, "设置", panel == ReaderPanel.SETTINGS, skin) {
                    onPanel(
                        if (panel == ReaderPanel.SETTINGS) ReaderPanel.NONE else ReaderPanel.SETTINGS,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            // 右侧的 › 是原版那个「还有更多图标」的提示，滚动后仍然保留位置。
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(52.dp),
                contentAlignment = Alignment.Center,
            ) { GlyphView(Glyph.RIGHT, skin.dim, 14.dp) }
        }

        if (panel != ReaderPanel.NONE) {
            Box(Modifier.fillMaxWidth().background(skin.panel)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    PanelHeader(panel, skin) { onPanel(ReaderPanel.NONE) }
                    when (panel) {
                        ReaderPanel.GAP -> GapPanel(prefs, skin, onPrefs)
                        ReaderPanel.FILTER -> FilterPanel(prefs, enhance, skin, onPrefs, onEnhance)
                        ReaderPanel.TURN -> TurnPanel(prefs, skin, onPrefs)
                        ReaderPanel.LAYOUT -> LayoutPanel(prefs, skin, onPrefs)
                        ReaderPanel.BOOKMARK -> BookmarkPanel(skin, onAddBookmark, onShowBookmarks)
                        ReaderPanel.SETTINGS -> SettingsPanel(prefs, skin, onPrefs)
                        ReaderPanel.NONE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(panel: ReaderPanel, skin: Skin, onClose: () -> Unit) {
    val name = when (panel) {
        ReaderPanel.GAP -> "页面间距"
        ReaderPanel.FILTER -> "滤镜"
        ReaderPanel.TURN -> "翻页方式"
        ReaderPanel.LAYOUT -> "页面布局"
        ReaderPanel.BOOKMARK -> "书签"
        ReaderPanel.SETTINGS -> "设置"
        ReaderPanel.NONE -> ""
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = skin.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .height(28.dp)
                .clickable(onClick = onClose)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("关闭", color = skin.dim, fontSize = 11.sp)
        }
    }
}

// ============================================================================ small widgets

@Composable
internal fun SkinSwitch(checked: Boolean, skin: Skin, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(20.dp)
            .background(if (checked) skin.accent else skin.track, RoundedCornerShape(10.dp))
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(14.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/** Flat slider: a 2dp track and a 12dp thumb, no Material ripple or elevation. */
@Composable
internal fun SkinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    skin: Skin,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val span = (range.endInclusive - range.start).coerceAtLeast(0.0001f)
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    val widthPx = remember { mutableIntStateOf(1) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx.intValue = it.width }
            .pointerInput(range, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun emit(x: Float) {
                        val f = (x / widthPx.intValue.toFloat()).coerceIn(0f, 1f)
                        onChange(range.start + f * span)
                    }
                    emit(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            emit(change.position.x)
                            change.consume()
                        } else {
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val k = size.width
            drawLine(
                color = if (enabled) skin.track else skin.track.copy(alpha = 0.05f),
                start = Offset(0f, cy),
                end = Offset(k, cy),
                strokeWidth = 2.5f.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = if (enabled) skin.accent else skin.dim,
                start = Offset(0f, cy),
                end = Offset(k * frac, cy),
                strokeWidth = 2.5f.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = if (enabled) skin.accent else skin.dim,
                radius = 6.dp.toPx(),
                center = Offset(k * frac, cy),
            )
        }
    }
}

@Composable
private fun SkinCheckRow(
    label: String,
    checked: Boolean,
    skin: Skin,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (checked) skin.accent else Color.Transparent,
                    RoundedCornerShape(3.dp),
                )
                .border(
                    if (checked) 0.dp else 1.2.dp,
                    if (checked) Color.Transparent else skin.dim,
                    RoundedCornerShape(3.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) GlyphView(Glyph.CHECK, Color.White, 12.dp, 2.dp)
        }
        Spacer(Modifier.width(9.dp))
        Text(label, color = skin.fg, fontSize = 12.sp, maxLines = 1)
        if (hint != null) {
            Spacer(Modifier.width(6.dp))
            Text(hint, color = skin.dim, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun InlineSwitchRow(
    label: String,
    checked: Boolean,
    skin: Skin,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = skin.fg, fontSize = 11.5.sp, maxLines = 1)
        Spacer(Modifier.width(7.dp))
        SkinSwitch(checked, skin, onChange)
    }
}

/** Segmented control used for enumerations (scale filter, scale mode). */
@Composable
private fun SkinSeg(
    labels: List<String>,
    selected: Int,
    skin: Skin,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(skin.bar, RoundedCornerShape(4.dp))
            .border(1.dp, skin.hair, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(
                        if (on) skin.accent.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(3.dp),
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) skin.accent else skin.dim,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SkinTextButton(label: String, skin: Skin, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .background(skin.bar, RoundedCornerShape(4.dp))
            .border(1.dp, skin.hair, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = skin.fg, fontSize = 11.5.sp)
    }
}

// ===================================================================================== panels

@Composable
private fun GapPanel(prefs: ReaderPrefs, skin: Skin, onPrefs: (ReaderPrefs) -> Unit) {
    var value by remember { mutableFloatStateOf(prefs.pageGapPercent.toFloat()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("页面之间留白", color = skin.dim, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("${value.toInt()}%", color = skin.accent, fontSize = 11.5.sp)
    }
    SkinSlider(value, 0f..30f, skin) {
        value = it
        onPrefs(prefs.copy(pageGapPercent = it.toInt()))
    }
    Text(
        "竖向连续阅读保持 0% 才是无缝的；只有分页模式才需要留白。",
        color = skin.dim,
        fontSize = 9.5.sp,
    )
}

@Composable
private fun FilterPanel(
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    skin: Skin,
    onPrefs: (ReaderPrefs) -> Unit,
    onEnhance: (EnhanceSettings) -> Unit,
) {
    Text("缩放插值", color = skin.dim, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    SkinSeg(
        labels = listOf("线性", "Bicubic", "Lanczos"),
        selected = when (enhance.scaleFilter) {
            ScaleFilter.NEAREST, ScaleFilter.BILINEAR -> 0
            ScaleFilter.BICUBIC -> 1
            ScaleFilter.LANCZOS3 -> 2
        },
        skin = skin,
    ) { i ->
        onEnhance(
            enhance.copy(
                scaleFilter = when (i) {
                    0 -> ScaleFilter.BILINEAR
                    1 -> ScaleFilter.BICUBIC
                    else -> ScaleFilter.LANCZOS3
                },
            ),
        )
    }
    Spacer(Modifier.height(6.dp))
    SkinCheckRow("黑白", prefs.grayscale, skin, Modifier.fillMaxWidth()) {
        onPrefs(prefs.copy(grayscale = it))
    }
    SkinCheckRow("自动对比度", enhance.contrastPass, skin, Modifier.fillMaxWidth()) {
        onEnhance(enhance.copy(contrastPass = it))
    }
    SkinCheckRow(
        "锐化（画质增强）",
        enhance.sharpen,
        skin,
        Modifier.fillMaxWidth(),
        hint = "低清源更明显",
    ) { onEnhance(enhance.copy(sharpen = it)) }
    // 强度和降噪原来只在「我的 → 阅读设置」那个旧面板里，现在并到这里，
    // 阅读器一套面板就是全部设置，不再有第二个入口。
    if (enhance.sharpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("锐化强度", color = skin.dim, fontSize = 10.5.sp)
            Spacer(Modifier.weight(1f))
            Text("${enhance.sharpenAmount}", color = skin.accent, fontSize = 10.5.sp)
        }
        SkinSlider(
            value = enhance.sharpenAmount.toFloat(),
            range = 0f..100f,
            skin = skin,
            modifier = Modifier.fillMaxWidth(),
        ) { onEnhance(enhance.copy(sharpenAmount = it.toInt().coerceIn(0, 100))) }
    }
    SkinCheckRow(
        "降噪（Kuwahara）",
        enhance.denoise,
        skin,
        Modifier.fillMaxWidth(),
        hint = "低清源更干净，翻页稍慢",
    ) { onEnhance(enhance.copy(denoise = it)) }
    SkinCheckRow("反色", prefs.invert, skin, Modifier.fillMaxWidth()) {
        onPrefs(prefs.copy(invert = it))
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("蓝光过滤", color = skin.dim, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(
            if (prefs.blueLight > 0) "${prefs.blueLight}%" else "关",
            color = if (prefs.blueLight > 0) skin.accent else skin.dim,
            fontSize = 11.sp,
        )
    }
    SkinSlider(
        value = prefs.blueLight.toFloat(),
        range = 0f..100f,
        skin = skin,
        modifier = Modifier.fillMaxWidth(),
    ) { onPrefs(prefs.copy(blueLight = it.toInt().coerceIn(0, 100))) }
    Text("压低屏幕蓝光，暖色夜间阅读；0% 即关闭。", color = skin.dim, fontSize = 9.5.sp)
}

@Composable
private fun TurnPanel(prefs: ReaderPrefs, skin: Skin, onPrefs: (ReaderPrefs) -> Unit) {
    val options = listOf(
        Triple("右翻页", Glyph.RIGHT, ReadingMode.PAGED to false),
        Triple("左翻页", Glyph.LEFT, ReadingMode.PAGED to true),
        Triple("右翻·垂直", Glyph.GAP, ReadingMode.VERTICAL to false),
        Triple("左翻·垂直", Glyph.GAP, ReadingMode.VERTICAL to true),
        Triple("右翻·水平", Glyph.TURN, ReadingMode.HORIZONTAL to false),
        Triple("左翻·水平", Glyph.TURN, ReadingMode.HORIZONTAL to true),
    )
    options.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, glyph, value) ->
                val on = prefs.readingMode == value.first && prefs.rightToLeft == value.second
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .background(
                            if (on) skin.accent.copy(alpha = 0.14f) else skin.bar,
                            RoundedCornerShape(4.dp),
                        )
                        .border(
                            1.dp,
                            if (on) skin.accent else skin.hair,
                            RoundedCornerShape(4.dp),
                        )
                        .clickable {
                            onPrefs(prefs.copy(readingMode = value.first, rightToLeft = value.second))
                        }
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlyphView(glyph, if (on) skin.accent else skin.fg, 18.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = if (on) skin.accent else skin.dim, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LayoutPanel(prefs: ReaderPrefs, skin: Skin, onPrefs: (ReaderPrefs) -> Unit) {
    Text("缩放方式", color = skin.dim, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    SkinSeg(
        labels = ScaleMode.entries.map { it.label },
        selected = ScaleMode.entries.indexOf(prefs.scaleMode),
        skin = skin,
    ) { i -> onPrefs(prefs.copy(scaleMode = ScaleMode.entries[i])) }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        InlineSwitchRow("双页", prefs.doublePage, skin) {
            onPrefs(prefs.copy(doublePage = it, forceSpread = if (it) prefs.forceSpread else false))
        }
        InlineSwitchRow("强制并排", prefs.forceSpread, skin) {
            onPrefs(prefs.copy(forceSpread = it, doublePage = it || prefs.doublePage))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        InlineSwitchRow("拆宽页", prefs.splitWidePages, skin) {
            onPrefs(prefs.copy(splitWidePages = it))
        }
        InlineSwitchRow("自动裁白边", prefs.autoCropWhiteBorders, skin) {
            onPrefs(prefs.copy(autoCropWhiteBorders = it))
        }
    }
}

@Composable
private fun BookmarkPanel(skin: Skin, onAdd: () -> Unit, onList: () -> Unit) {
    Text(
        text = "书签会记住当前页，并从书架直接跳回。",
        color = skin.dim,
        fontSize = 10.5.sp,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SkinTextButton("添加书签", skin, onAdd)
        SkinTextButton("书签列表", skin, onList)
    }
}

@Composable
private fun SettingsPanel(prefs: ReaderPrefs, skin: Skin, onPrefs: (ReaderPrefs) -> Unit) {
    var value by remember { mutableFloatStateOf(prefs.brightness.toFloat()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("亮度", color = skin.dim, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        InlineSwitchRow("自动", prefs.autoBrightness, skin) {
            onPrefs(prefs.copy(autoBrightness = it))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (prefs.autoBrightness) "跟随系统" else "${value.toInt()}%",
            color = skin.accent,
            fontSize = 11.sp,
        )
    }
    SkinSlider(value, 5f..100f, skin, enabled = !prefs.autoBrightness) {
        value = it
        onPrefs(prefs.copy(brightness = it.toInt()))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("背景", color = skin.dim, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        for (i in 0..2) {
            Swatch(i, prefs.background, skin) { onPrefs(prefs.copy(background = i)) }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        InlineSwitchRow("常亮", prefs.keepScreenOn, skin) {
            onPrefs(prefs.copy(keepScreenOn = it))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        InlineSwitchRow("双击缩放", prefs.doubleTapZoom, skin) {
            onPrefs(prefs.copy(doubleTapZoom = it))
        }
        InlineSwitchRow("音量键", prefs.volumeKeyPaging, skin) {
            onPrefs(prefs.copy(volumeKeyPaging = it))
        }
        InlineSwitchRow("浅色栏", prefs.chromeLight, skin) {
            onPrefs(prefs.copy(chromeLight = it))
        }
    }
}

@Composable
private fun Swatch(index: Int, selected: Int, skin: Skin, onPick: () -> Unit) {
    val color = when (index) {
        0 -> Color(0xFF0B0B0D)
        1 -> Color(0xFF16161A)
        else -> Color(0xFFEDEDE6)
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color, RoundedCornerShape(2.dp))
            .border(
                1.5.dp,
                if (index == selected) skin.accent else skin.hair,
                RoundedCornerShape(2.dp),
            )
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        if (index == selected) {
            GlyphView(
                Glyph.CHECK,
                if (index == 2) Color.Black else Color.White,
                12.dp,
                2.dp,
            )
        }
    }
}

// =============================================================================== bottom chrome

@Composable
internal fun ReaderBottomChrome(
    pageLabel: String,
    sliderValue: Float,
    total: Int,
    light: Boolean,
    onChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val skin = skinFor(light)
    Column(Modifier.fillMaxWidth().background(skin.chrome)) {
        Text(
            text = pageLabel,
            color = skin.dim,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(34.dp).clickable(onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) { GlyphView(Glyph.LEFT, skin.dim, 16.dp) }
            SkinSlider(
                value = sliderValue.coerceIn(0f, maxOf(1, total - 1).toFloat()),
                range = 0f..maxOf(1, total - 1).toFloat(),
                skin = skin,
                modifier = Modifier.weight(1f),
            ) {
                onChange(it)
                onSeek(it.toInt())
            }
            Box(
                modifier = Modifier.size(34.dp).clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) { GlyphView(Glyph.RIGHT, skin.dim, 16.dp) }
        }
    }
}

// ======================================================================================= menu

internal data class MenuEntry(
    val label: String,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** The original's overflow menu: a solid slab under the title bar, not a rounded popup. */
@Composable
internal fun ReaderMenuPanel(
    entries: List<MenuEntry>,
    light: Boolean,
    onDismiss: () -> Unit,
) {
    val skin = skinFor(light)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 46.dp)
                .widthIn(min = 236.dp, max = 280.dp)
                .background(skin.menu)
                .navigationBarsPadding(),
        ) {
            entries.forEachIndexed { i, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable {
                            onDismiss()
                            entry.onClick()
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.label,
                        color = skin.fg,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (entry.checked == true) {
                        GlyphView(Glyph.CHECK, skin.accent, 16.dp, 1.8.dp)
                    }
                }
                if (i < entries.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(skin.hair.copy(alpha = 0.6f)))
                }
            }
        }
    }
}

// ==================================================================================== dialogs

/** Dark dialog matching the chrome; Material's Dialog would come back in white. */
@Composable
internal fun SkinDialog(
    title: String,
    light: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val skin = skinFor(light)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .background(skin.menu, RoundedCornerShape(6.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(title, color = skin.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("关闭", color = skin.accent, fontSize = 12.sp) }
            }
        }
    }
}

/** Radio list used by the orientation and tap-zone dialogs. */
@Composable
internal fun SkinRadioRow(
    label: String,
    selected: Boolean,
    skin: Skin,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(
                    1.4.dp,
                    if (selected) skin.accent else skin.dim,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).background(skin.accent, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) skin.accent else skin.fg, fontSize = 12.5.sp)
    }
}

/** Single-line input with the reader's own colours; Material's field is a white box. */
@Composable
internal fun SkinTextField(
    value: String,
    skin: Skin,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = skin.fg, fontSize = 13.sp),
        cursorBrush = SolidColor(skin.accent),
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(skin.bar, RoundedCornerShape(4.dp))
            .border(1.dp, skin.hair, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = skin.dim, fontSize = 12.sp)
                inner()
            }
        },
    )
}

// ---------------------------------------------------------------------------- 对比

/**
 * 长按查看未处理的原图。锐化到底有没有用，让眼睛判断比看参数可靠。
 */
