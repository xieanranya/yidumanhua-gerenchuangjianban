package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LinearProgressIndicator
import com.enhance.comic.data.ColorizeEngine
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.ReaderLayout
import com.enhance.comic.model.ReaderPrefs

/**
 * 在线阅读器自己的界面（不复用本地那套）。
 *
 * 本地阅读器是「两行常驻工具栏 + 一排小图标」，那是给本地画布配的；
 * 在线是网页/条漫，顶上两行会一直压着内容，密度也挤。
 *
 * 这里的结构是**两层**：点 ⚙ 先看到「阅读设置」目录（每个功能一行，带当前值），
 * 点进去只看到那一个功能（左上角返回、右上角完成）——
 * 一屏只干一件事，不会像之前那样把滑杆和开关全堆在一起。
 */

// v7 配色（计划书）：画布 #101217 / 面板 #191C23 / 文字 #F2F3F6 / 当前值 #B6BBC8 / 强调淡紫 #CDBBFF
private val OnlineAccent = Color(0xFFCDBBFF)
private val OnlineCanvas = Color(0xFF101217)
private val OnlineSheetBg = Color(0xFF191C23)
private val OnlineFg = Color(0xFFF2F3F6)
private val OnlineValue = Color(0xFFB6BBC8)
private val OnlineDisabled = Color(0xFF4A4D58)
private val OnlineBarBg = Color(0xCC000000)
private val OnlineCard = Color(0xFF242428)
private val OnlineHair = Color(0x1FFFFFFF)
private val OnlineDim = Color(0x99FFFFFF)
private val OnlineHint = Color(0x66FFFFFF)
private val OnlineOnAccent = Color(0xFF1A1526)

/** v7 顶栏：返回 + 章节名/书名 + 页码胶囊。衬底是画布色的顶部渐变。 */
@Composable
internal fun OnlineTopBar(
    title: String,
    subtitle: String,
    pageLabel: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        OnlineCanvas.copy(alpha = 0.96f),
                        OnlineCanvas.copy(alpha = 0.60f),
                        Color.Transparent,
                    )
                )
            )
            .statusBarsPadding()
            .height(58.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.BACK, Color.White, 21.dp) }

        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                text = title.ifBlank { "阅读" },
                color = OnlineFg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = OnlineValue,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            Modifier
                .background(Color(0x14FFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(pageLabel, color = OnlineValue, fontSize = 10.5.sp, maxLines = 1)
        }
    }
}

/**
 * v7 底部栏：进度行（页码 + 细进度条 + 章节序号）+「上一话 / 目录 / 设置 / 下一话」四键。
 * 翻页类布局进度条可拖跳页；条漫类只做展示。衬底是画布色的底部渐变。
 */
@Composable
internal fun OnlineBottomBar(
    current: Int,
    total: Int,
    chapterLabel: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    chaptersAvailable: Boolean,
    seekable: Boolean,
    onSeek: (Int) -> Unit,
    onPrev: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        OnlineCanvas.copy(alpha = 0.78f),
                        OnlineCanvas.copy(alpha = 0.97f),
                    )
                )
            )
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${min(current + 1, max(total, 1))} / $total",
                color = OnlineValue,
                fontSize = 9.5.sp,
                maxLines = 1,
            )
            val ratio = if (total > 1) ((current + 1f) / total).coerceIn(0f, 1f) else 1f
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .height(20.dp)
                    .then(if (seekable && total > 1) Modifier.pointerSeek(total, onSeek) else Modifier),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x24FFFFFF), RoundedCornerShape(1.5.dp)),
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(ratio)
                        .height(3.dp)
                        .background(OnlineAccent, RoundedCornerShape(1.5.dp)),
                )
            }
            Text(
                chapterLabel,
                color = OnlineValue,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BarKey("上一话", Glyph.LEFT, hasPrev, onPrev)
            BarKey("目录", Glyph.LIST, chaptersAvailable, onContents)
            BarKey("设置", Glyph.GEAR, true, onSettings)
            BarKey("下一话", Glyph.RIGHT, hasNext, onNext)
        }
    }
}

@Composable
private fun RowScope.BarKey(label: String, icon: Glyph, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(66.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphView(icon, if (enabled) OnlineFg else OnlineDisabled, 17.dp)
        Text(
            label,
            color = if (enabled) OnlineFg else OnlineDisabled,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ==================================================================== 设置（两层）

private const val PAGE_HOME = ""
private const val PAGE_LAYOUT = "layout"
private const val PAGE_DIR = "dir"
private const val PAGE_GAP = "gap"
private const val PAGE_CROP = "crop"
private const val PAGE_COLORIZE = "colorize"
private const val PAGE_BRIGHT = "bright"
private const val PAGE_BLUE = "blue"
private const val PAGE_FILTER = "filter"

/**
 * 底部设置面板（v7）：「阅读 / 图像 / 显示」三组 tab，每次只显示两三项。
 * 章节挪到工具栏「目录」键（OnlineChapterSheet）。
 *
 * page 为空 = 当前组列表；否则只画那一个功能的小界面。切换不做转场动画 ——
 * 阅读页一直在跑图片解码，少一层动画少一份卡顿。
 */
@Composable
internal fun OnlineSettingsSheet(
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    onPrefs: (ReaderPrefs) -> Unit,
    onEnhance: (EnhanceSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(PAGE_HOME) }
    var tab by remember { mutableStateOf(0) }
    val sheetScope = rememberCoroutineScope()

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
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(OnlineSheetBg)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp).padding(bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp)),
                )
            }

            when (page) {
                PAGE_HOME -> SettingsHome(
                    prefs,
                    enhance,
                    onOpen = { page = it },
                    onDone = onDismiss,
                    tab = tab,
                    onTab = { tab = it },
                )

                PAGE_LAYOUT -> SheetScreen("阅读器", { page = PAGE_HOME }, onDismiss) {
                    ReaderLayout.entries.forEach { layout ->
                        SheetOption(
                            name = layout.label,
                            desc = layout.desc,
                            selected = prefs.onlineLayout == layout,
                        ) { onPrefs(prefs.copy(onlineLayout = layout)) }
                    }
                }

                PAGE_DIR -> SheetScreen("阅读方向", { page = PAGE_HOME }, onDismiss) {
                    SheetOption("左到右", "第一页在左边", !prefs.rightToLeft) {
                        onPrefs(prefs.copy(rightToLeft = false))
                    }
                    SheetOption("右到左", "第一页在右边（日漫习惯）", prefs.rightToLeft) {
                        onPrefs(prefs.copy(rightToLeft = true))
                    }
                    Spacer(Modifier.height(10.dp))
                    SheetNote(
                        if (prefs.onlineLayout == ReaderLayout.WEBTOON ||
                            prefs.onlineLayout == ReaderLayout.FREE
                        ) {
                            "当前是竖条模式（上下滚），方向只在相册 / 双页 / 滑动里生效。"
                        } else {
                            "左右翻的时候从哪一边开始。"
                        },
                    )
                }

                PAGE_GAP -> SheetScreen("页间距", { page = PAGE_HOME }, onDismiss) {
                    SheetNote("上下两页之间留多少空隙。0 就是无缝拼接（看条漫最常用），往右拉会看到明显的间隔。")
                    Spacer(Modifier.height(12.dp))
                    SheetSlider(
                        label = "空隙",
                        value = prefs.pageGapPercent.toFloat(),
                        range = 0f..20f,
                        valueText = if (prefs.pageGapPercent == 0) "无缝" else "${prefs.pageGapPercent}%",
                    ) { onPrefs(prefs.copy(pageGapPercent = it.toInt().coerceIn(0, 20))) }
                    Row(Modifier.fillMaxWidth()) {
                        Text("无缝", color = OnlineHint, fontSize = 10.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("20%", color = OnlineHint, fontSize = 10.5.sp)
                    }
                }

                PAGE_CROP -> SheetScreen("自动裁白边", { page = PAGE_HOME }, onDismiss) {
                    SheetNote("自动认出每页四周的白边并裁掉，让画面按手机屏幕宽度铺满 —— 不会再有左右两条白杠。")
                    Spacer(Modifier.height(6.dp))
                    SheetToggleRow("开启", prefs.autoCropOnline) {
                        onPrefs(prefs.copy(autoCropOnline = it))
                    }
                    if (prefs.autoCropOnline) {
                        Spacer(Modifier.height(8.dp))
                        SheetSlider(
                            label = "识别强度",
                            value = prefs.cropLevel.toFloat(),
                            range = 0f..2f,
                            valueText = listOf("保守", "中", "激进")[prefs.cropLevel.coerceIn(0, 2)],
                        ) { onPrefs(prefs.copy(cropLevel = it.toInt().coerceIn(0, 2))) }
                        Row(Modifier.fillMaxWidth()) {
                            Text("保守", color = OnlineHint, fontSize = 10.5.sp)
                            Spacer(Modifier.weight(1f))
                            Text("激进", color = OnlineHint, fontSize = 10.5.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SheetNote("只裁「整行 / 整列都是白」的那种边，画面里的白色气泡不会被切。")
                }

                PAGE_COLORIZE -> SheetScreen("黑白上色", { page = PAGE_HOME }, onDismiss) {
                    SheetToggleRow("开启", prefs.colorizeOnline) {
                        onPrefs(prefs.copy(colorizeOnline = it))
                        // 打开开关就自动下载所选风格的模型
                        if (it) {
                            val m = ColorizeEngine.Model.byId(prefs.colorizeStyle)
                            if (!ColorizeEngine.modelReady(m)) {
                                sheetScope.launch { ColorizeEngine.download(m) }
                            }
                        }
                    }
                    if (prefs.colorizeOnline) {
                        Spacer(Modifier.height(10.dp))
                        SheetNote("上色风格（切换即自动下载对应模型）")
                        Spacer(Modifier.height(4.dp))
                        SheetOptionRow(
                            "动漫 · 明快平涂",
                            "manga-colorization-v2 fp32，约 117MB",
                            prefs.colorizeStyle != "realistic",
                        ) {
                            onPrefs(prefs.copy(colorizeStyle = "anime"))
                            val m = ColorizeEngine.Model.ANIME
                            if (!ColorizeEngine.modelReady(m)) {
                                sheetScope.launch { ColorizeEngine.download(m) }
                            }
                        }
                        SheetOptionRow(
                            "写实 · 真实质感",
                            "DDColor，约 59MB",
                            prefs.colorizeStyle == "realistic",
                        ) {
                            onPrefs(prefs.copy(colorizeStyle = "realistic"))
                            val m = ColorizeEngine.Model.REALISTIC
                            if (!ColorizeEngine.modelReady(m)) {
                                sheetScope.launch { ColorizeEngine.download(m) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val cur = ColorizeEngine.Model.byId(prefs.colorizeStyle)
                        when (val st = ColorizeEngine.stateOf(cur)) {
                            is ColorizeEngine.ModelState.Downloading -> {
                                LinearProgressIndicator(
                                    progress = { ColorizeEngine.progressOf(cur) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = OnlineAccent,
                                    trackColor = Color(0x33FFFFFF),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "正在下载「" + cur.label + "」模型（"
                                        + (if (cur == ColorizeEngine.Model.ANIME) "约 117MB" else "约 59MB")
                                        + "，仅一次）… "
                                        + (ColorizeEngine.progressOf(cur) * 100).toInt() + "%",
                                    color = OnlineHint,
                                    fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                                SheetNote("下载完成前先显示原图。")
                            }
                            is ColorizeEngine.ModelState.Ready ->
                                SheetNote("「" + cur.label + "」模型已就绪。每页首次打开要等几秒，"
                                    + "看过的页有缓存秒开。彩色源和本地书不受影响。")
                            is ColorizeEngine.ModelState.Idle ->
                                MiniButton(
                                    "下载「" + cur.label + "」模型（"
                                        + (if (cur == ColorizeEngine.Model.ANIME) "约 117MB" else "约 59MB")
                                        + "，仅一次）",
                                    {
                                        sheetScope.launch { ColorizeEngine.download(cur) }
                                    },
                                    accent = true,
                                )
                            is ColorizeEngine.ModelState.Failed -> {
                                MiniButton("下载失败，点这里重试", {
                                    sheetScope.launch { ColorizeEngine.download(cur) }
                                })
                                Spacer(Modifier.height(8.dp))
                                SheetNote(st.message)
                            }
                        }
                    } else {
                        SheetNote("打开后黑白漫画会自动上色。动漫模型约 117MB / 写实约 59MB，"
                            + "选用哪种才下载哪种。彩色源和本地书不受影响。")
                    }
                }

                PAGE_BRIGHT -> SheetScreen("亮度", { page = PAGE_HOME }, onDismiss) {
                    SheetToggleRow("跟随系统", prefs.autoBrightness) {
                        onPrefs(prefs.copy(autoBrightness = it))
                    }
                    Spacer(Modifier.height(8.dp))
                    SheetSlider(
                        label = "屏幕亮度",
                        value = prefs.brightness.toFloat(),
                        range = 5f..100f,
                        valueText = if (prefs.autoBrightness) "跟随系统" else "${prefs.brightness}%",
                        enabled = !prefs.autoBrightness,
                    ) { onPrefs(prefs.copy(brightness = it.toInt().coerceIn(5, 100))) }
                    Spacer(Modifier.height(10.dp))
                    SheetNote("退出阅读页会恢复成原来的亮度，不会锁死系统设置。")
                }

                PAGE_BLUE -> SheetScreen("蓝光过滤", { page = PAGE_HOME }, onDismiss) {
                    SheetNote("整页盖一层暖色，夜里看着不刺眼。")
                    Spacer(Modifier.height(12.dp))
                    SheetSlider(
                        label = "强度",
                        value = prefs.blueLight.toFloat(),
                        range = 0f..100f,
                        valueText = if (prefs.blueLight == 0) "关" else "${prefs.blueLight}",
                    ) { onPrefs(prefs.copy(blueLight = it.toInt().coerceIn(0, 100))) }
                }

                PAGE_FILTER -> SheetScreen("画面", { page = PAGE_HOME }, onDismiss) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetChip("灰度", prefs.grayscale) {
                            onPrefs(prefs.copy(grayscale = !prefs.grayscale))
                        }
                        SheetChip("反色", prefs.invert) {
                            onPrefs(prefs.copy(invert = !prefs.invert))
                        }
                        SheetChip("常亮", prefs.keepScreenOn) {
                            onPrefs(prefs.copy(keepScreenOn = !prefs.keepScreenOn))
                        }
                        SheetChip("锐化", enhance.sharpen) {
                            onEnhance(enhance.copy(sharpen = !enhance.sharpen))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SheetNote("灰度 / 反色直接作用在在线图片上，改完立刻能看出来。")
                }
            }
        }
    }
}

/** 当前组的两三行（v7 三组 tab）：名称左 / 当前值右 / 细箭头。 */
@Composable
private fun SettingsHome(
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    onOpen: (String) -> Unit,
    onDone: () -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SheetHead(title = "阅读设置", onBack = null, onDone = onDone)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("阅读", "图像", "显示").forEachIndexed { i, name ->
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (tab == i) Color(0x14FFFFFF) else Color(0x05FFFFFF))
                        .clickable { onTab(i) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name,
                        color = if (tab == i) OnlineFg else OnlineValue,
                        fontSize = 13.sp,
                        fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
        ) {
            when (tab) {
                0 -> {
                    RowEntry("阅读器",
                        ReaderLayout.entries.firstOrNull { it == prefs.onlineLayout }?.label ?: "Webtoon",
                    ) { onOpen(PAGE_LAYOUT) }
                    RowEntry("阅读方向", if (prefs.rightToLeft) "右到左" else "左到右") {
                        onOpen(PAGE_DIR)
                    }
                    RowEntry("页间距",
                        if (prefs.pageGapPercent == 0) "无缝" else "${prefs.pageGapPercent}%",
                        last = true,
                    ) { onOpen(PAGE_GAP) }
                }
                1 -> {
                    RowEntry("自动裁白边",
                        if (prefs.autoCropOnline) {
                            "开 · " + listOf("保守", "中", "激进")[prefs.cropLevel.coerceIn(0, 2)]
                        } else {
                            "关"
                        },
                    ) { onOpen(PAGE_CROP) }
                    RowEntry("黑白上色",
                        when (ColorizeEngine.stateOf(ColorizeEngine.Model.byId(prefs.colorizeStyle))) {
                            is ColorizeEngine.ModelState.Downloading ->
                                "开 · 下载" + (if (prefs.colorizeStyle == "realistic") "写实" else "动漫") + "模型"
                            else ->
                                if (prefs.colorizeOnline)
                                    "开 · " + (if (prefs.colorizeStyle == "realistic") "写实" else "动漫")
                                else "关"
                        },
                    ) { onOpen(PAGE_COLORIZE) }
                    val filterOn = listOfNotNull(
                        "灰度".takeIf { prefs.grayscale },
                        "反色".takeIf { prefs.invert },
                        "锐化".takeIf { enhance.sharpen },
                    )
                    RowEntry("画面", if (filterOn.isEmpty()) "正常" else filterOn.joinToString(" · "), last = true) {
                        onOpen(PAGE_FILTER)
                    }
                }
                else -> {
                    RowEntry("亮度",
                        if (prefs.autoBrightness) "跟随系统" else "${prefs.brightness}%",
                    ) { onOpen(PAGE_BRIGHT) }
                    RowEntry("蓝光过滤",
                        if (prefs.blueLight == 0) "关" else "${prefs.blueLight}",
                        last = true,
                    ) { onOpen(PAGE_BLUE) }
                }
            }
        }
    }
}

@Composable
/** 组内行（v7）：名称左 / 当前值右（#B6BBC8）/ 细箭头，发丝线分隔。 */
private fun RowEntry(name: String, value: String, last: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = OnlineFg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = OnlineValue,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 6.dp).weight(1.6f, fill = false),
        )
        Text("›", color = Color(0x55FFFFFF), fontSize = 14.sp)
    }
    if (!last) HLine()
}

/** 子界面外壳：返回 / 标题 / 完成 + 可滚内容。 */
@Composable
private fun SheetScreen(
    title: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SheetHead(title = title, onBack = onBack, onDone = onDone)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
        ) { content() }
    }
}

@Composable
private fun SheetHead(title: String, onBack: (() -> Unit)?, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(48.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { GlyphView(Glyph.BACK, Color.White, 19.dp) }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.size(48.dp).clickable(onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Text("完成", color = OnlineAccent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SheetOption(name: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x1ACDBBFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (selected) OnlineAccent else Color.White,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            if (desc.isNotBlank()) {
                Text(desc, color = OnlineDim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (selected) GlyphView(Glyph.CHECK, OnlineAccent, 18.dp)
    }
}

@Composable
private fun SheetToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .width(46.dp)
                .height(27.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) OnlineAccent else Color(0x24FFFFFF))
                .clickable { onChange(!checked) },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(21.dp)
                    .background(Color.White, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun SheetChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) OnlineAccent else Color.Transparent)
            .border(1.dp, if (on) OnlineAccent else OnlineHair, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (on) OnlineOnAccent else Color.White,
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
            Text(valueText, color = if (enabled) OnlineAccent else OnlineDim, fontSize = 11.sp)
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

@Composable
private fun SheetNote(text: String) {
    Text(text, color = OnlineHint, fontSize = 11.5.sp, lineHeight = 17.sp)
}

// ==================================================================== 目录

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
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(OnlineSheetBg)
                .navigationBarsPadding()
                .heightIn(max = 560.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp)),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("目录", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${chapters.size} 话", color = OnlineDim, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "完成",
                    color = OnlineAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                )
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(chapters.size) { i ->
                    val item = chapters[i]
                    val active = i == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) Color(0x1ACDBBFF) else Color.Transparent)
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
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SheetOptionRow(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (selected) OnlineAccent else Color.White.copy(alpha = 0.86f),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(desc, color = OnlineHint, fontSize = 10.5.sp)
        }
        if (selected) Text("✓", color = OnlineAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 点 / 拖都能跳页（底部进度条用）。 */
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

// ==================================================================== 常驻进度条

/**
 * 贴底常驻进度条：随当前页实时更新，点住拖拽预览（拇指圆点 + 页码气泡），
 * 松手跳到目标页。触控区只有细线附近的 22dp 高条，不挡漫画底部的点击，
 * 也不随工具栏隐藏。淡紫 = v7 强调色。
 */
@Composable
internal fun OnlineProgressRail(current: Int, total: Int, onSeek: (Int) -> Unit) {
    if (total <= 0) return
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0) }
    val shown = (if (dragging) dragValue else current).coerceIn(0, max(total - 1, 0))
    val ratio = if (total > 1) ((shown + 1f) / total).coerceIn(0f, 1f) else 1f

    fun pageAt(x: Float, w: Float): Int =
        (((x.coerceIn(0f, w) / max(w, 1f)) * max(total - 1, 1)).roundToInt()).coerceIn(0, total - 1)

    BoxWithConstraints(Modifier.fillMaxWidth().height(if (dragging) 56.dp else 22.dp)) {
        val w = maxWidth
        if (dragging) {
            val thumbX = w * ratio
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (thumbX - 34.dp).coerceIn(0.dp, (w - 68.dp).coerceAtLeast(0.dp)))
                    .background(OnlineSheetBg.copy(alpha = 0.97f), RoundedCornerShape(50))
                    .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "${shown + 1} / $total",
                    color = OnlineFg,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(total) {
                    detectTapGestures { o -> onSeek(pageAt(o.x, size.width.toFloat())) }
                }
                .pointerInput(total) {
                    detectDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            dragValue = pageAt(o.x, size.width.toFloat())
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragValue = pageAt(change.position.x, size.width.toFloat())
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek(dragValue)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color(0x26FFFFFF)),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(ratio)
                    .height(2.5.dp)
                    .background(OnlineAccent),
            )
            if (dragging) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = w * ratio - 7.dp)
                        .size(14.dp)
                        .shadow(4.dp, CircleShape)
                        .background(OnlineAccent, CircleShape),
                )
            }
        }
    }
}
