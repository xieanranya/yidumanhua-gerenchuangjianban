package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.ReaderLayout
import com.enhance.comic.model.ReaderPrefs

/**
 * 在线阅读器自己的界面（不复用本地那套）。
 *
 * 本地阅读器那套是「两行常驻工具栏 + 一排小图标 + 文字标签」，那是给本地画布配的：
 * 本地显示的是已经解好码的整页，栏体占掉一点也无所谓。
 * 在线是网页/条漫，顶上加两行会一直压着内容，而且那套 skin 的密度在手机上看着很挤。
 *
 * 所以这里换一种做法：
 *  - 顶栏只有一行（返回 / 标题 / 目录 / 设置），点画面才出来，4 秒自己收；
 *  - 所有设置收进**底部弹出面板**，不占阅读空间；
 *  - 配色固定在深色（阅读页背景是黑的），accent 用品牌粉，不跟主题走。
 */

private val OnlineAccent = Color(0xFFFF6E9C)
private val OnlineBarBg = Color(0xCC000000)
private val OnlineSheetBg = Color(0xF218181B)
private val OnlineHair = Color(0x1FFFFFFF)
private val OnlineDim = Color(0x99FFFFFF)

/** 一行顶栏：返回 + 标题/页码 + 目录 + 设置。 */
@Composable
internal fun OnlineTopBar(
    title: String,
    pageLabel: String,
    onBack: () -> Unit,
    onChapters: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OnlineBarBg)
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.BACK, Color.White, 21.dp) }

        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                text = title.ifBlank { "阅读" },
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(pageLabel, color = OnlineDim, fontSize = 10.5.sp, maxLines = 1)
        }

        if (onChapters != null) {
            Box(
                Modifier.size(44.dp).clickable(onClick = onChapters),
                contentAlignment = Alignment.Center,
            ) { GlyphView(Glyph.LIST, Color.White, 20.dp) }
        }
        Box(
            Modifier.size(44.dp).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.GEAR, Color.White, 20.dp) }
    }
}

/**
 * 底部设置面板：阅读器类型 / 方向 / 间距 / 显示。
 *
 * 点面板外面任意处收起；面板本身可上下滚（小屏 + 大字号时放不下）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnlineSettingsSheet(
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    onPrefs: (ReaderPrefs) -> Unit,
    onEnhance: (EnhanceSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 半透明遮罩，点一下关掉
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(OnlineSheetBg)
                .navigationBarsPadding()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 18.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))

            SheetLabel("阅读器")
            Spacer(Modifier.height(9.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReaderLayout.entries.forEach { layout ->
                    SheetChip(layout.label, prefs.onlineLayout == layout) {
                        onPrefs(prefs.copy(onlineLayout = layout))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetLabel("阅读方向")
                Spacer(Modifier.weight(1f))
                SheetChip("左到右", !prefs.rightToLeft) {
                    onPrefs(prefs.copy(rightToLeft = false))
                }
                Spacer(Modifier.width(8.dp))
                SheetChip("右到左", prefs.rightToLeft) {
                    onPrefs(prefs.copy(rightToLeft = true))
                }
            }
            if (prefs.onlineLayout == ReaderLayout.WEBTOON ||
                prefs.onlineLayout == ReaderLayout.FREE
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "竖条模式是上下滚的，方向只在相册 / 双页 / 滑动里生效",
                    color = Color(0x66FFFFFF),
                    fontSize = 10.5.sp,
                )
            }
            Spacer(Modifier.height(18.dp))

            SheetSlider(
                label = "页面间距",
                value = prefs.pageGapPercent.toFloat(),
                range = 0f..20f,
                valueText = "${prefs.pageGapPercent}%",
            ) { onPrefs(prefs.copy(pageGapPercent = it.toInt().coerceIn(0, 20))) }
            Spacer(Modifier.height(10.dp))

            SheetSlider(
                label = "亮度",
                value = prefs.brightness.toFloat(),
                range = 5f..100f,
                valueText = if (prefs.autoBrightness) "跟随系统" else "${prefs.brightness}%",
                enabled = !prefs.autoBrightness,
            ) { onPrefs(prefs.copy(brightness = it.toInt().coerceIn(5, 100))) }
            Spacer(Modifier.height(10.dp))

            SheetSlider(
                label = "蓝光过滤",
                value = prefs.blueLight.toFloat(),
                range = 0f..100f,
                valueText = if (prefs.blueLight == 0) "关" else "${prefs.blueLight}",
            ) { onPrefs(prefs.copy(blueLight = it.toInt().coerceIn(0, 100))) }
            Spacer(Modifier.height(16.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetChip("灰度", prefs.grayscale) {
                    onPrefs(prefs.copy(grayscale = !prefs.grayscale))
                }
                SheetChip("反色", prefs.invert) {
                    onPrefs(prefs.copy(invert = !prefs.invert))
                }
                SheetChip("自动亮度", prefs.autoBrightness) {
                    onPrefs(prefs.copy(autoBrightness = !prefs.autoBrightness))
                }
                SheetChip("常亮", prefs.keepScreenOn) {
                    onPrefs(prefs.copy(keepScreenOn = !prefs.keepScreenOn))
                }
                SheetChip("锐化", enhance.sharpen) {
                    onEnhance(enhance.copy(sharpen = !enhance.sharpen))
                }
            }
        }
    }
}

/** 目录：同一套视觉的底部列表。 */
@Composable
internal fun OnlineChapterSheet(
    chapters: List<Pair<String, String>>,
    index: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(OnlineSheetBg)
                .navigationBarsPadding()
                .heightIn(max = 560.dp)
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 10.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetLabel("目录")
                Spacer(Modifier.width(8.dp))
                Text("${chapters.size} 话", color = OnlineDim, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    color = OnlineDim,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.heightIn(max = 440.dp),
            ) {
                items(chapters.size) { i ->
                    val item = chapters[i]
                    val active = i == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color(0x1FFFF6E9C) else Color.Transparent)
                            .clickable {
                                if (!active) onPick(i)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.second.ifBlank { "第 ${i + 1} 话" },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (active) OnlineAccent else Color.White,
                            fontSize = 13.sp,
                        )
                        if (active) Text("当前", color = OnlineAccent, fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}

/**
 * 底部翻页滑条（相册 / 双页才有）。
 *
 * 和本地那套的差别：这里只有一条细线 + 两端页码，没有 thumb ——
 * 拖动时手指就是 thumb，视觉上更干净。
 */
@Composable
internal fun OnlineSeekBar(
    current: Int,
    total: Int,
    onSeek: (Int) -> Unit,
) {
    if (total <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OnlineBarBg)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${current + 1}", color = Color.White, fontSize = 11.sp)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .height(24.dp)
                .pointerSeek(total, onSeek),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(1.dp)),
            )
            Box(
                Modifier
                    .fillMaxWidth(((current + 1f) / total).coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(OnlineAccent, RoundedCornerShape(1.dp)),
            )
        }
        Text("/ $total", color = OnlineDim, fontSize = 11.sp)
    }
}

// ------------------------------------------------------------------ 小零件

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SheetChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) OnlineAccent else Color.Transparent)
            .border(
                1.dp,
                if (on) OnlineAccent else OnlineHair,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (on) Color(0xFF16080C) else Color.White,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun SheetSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                color = if (enabled) OnlineAccent else OnlineDim,
                fontSize = 11.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = OnlineAccent,
                activeTrackColor = OnlineAccent,
                inactiveTrackColor = Color(0x33FFFFFF),
                disabledThumbColor = Color(0x44FFFFFF),
                disabledActiveTrackColor = Color(0x33FFFFFF),
                disabledInactiveTrackColor = Color(0x22FFFFFF),
            ),
        )
    }
}

/** 点/拖都能跳页，且不画 thumb。 */
private fun Modifier.pointerSeek(total: Int, onSeek: (Int) -> Unit): Modifier =
    this
        .pointerInput(total) {
            detectTapGestures { o ->
                onSeek(((o.x / size.width) * total).toInt().coerceIn(0, total - 1))
            }
        }
        .pointerInput(total) {
            detectDragGestures { change, _ ->
                change.consume()
                onSeek(((change.position.x / size.width) * total).toInt().coerceIn(0, total - 1))
            }
        }
