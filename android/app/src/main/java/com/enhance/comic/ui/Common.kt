package com.enhance.comic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 页面大标题，跟顶栏分开：大标题随内容滚，顶栏只有返回/操作。 */
@Composable
fun BigTitle(
    text: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    inset: Boolean = true,
) {
    val c = LocalColors.current
    Column(
        modifier.padding(
            start = if (inset) 18.dp else 0.dp,
            end = if (inset) 18.dp else 0.dp,
            top = 6.dp,
            bottom = 12.dp,
        ),
    ) {
        Text(
            text = text,
            color = c.fg,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
        if (sub != null) {
            Spacer(Modifier.height(3.dp))
            Text(sub, color = c.fg3, fontSize = 11.5.sp)
        }
    }
}

@Composable
fun SearchStub(text: String, onClick: () -> Unit, inset: Boolean = true) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = if (inset) 16.dp else 0.dp)
            .fillMaxWidth()
            .height(38.dp)
            .background(c.chip, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphView(Glyph.SEARCH, c.fg3, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.fg3, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    more: String? = null,
    onMore: (() -> Unit)? = null,
    inset: Boolean = true,
) {
    val c = LocalColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (inset) 18.dp else 0.dp,
                end = if (inset) 18.dp else 0.dp,
                top = 16.dp,
                bottom = 9.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = c.fg,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (more != null) {
            Text(
                "$more ›",
                color = c.fg3,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 168.dp)
                    .then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier),
            )
        }
    }
}

/**
 * 分区的「全部条目」列表页：发现页点「12 本 ›」进来看这一区的完整内容。
 * 聚合发现页和单源发现页共用，点击行为由调用方决定（聚合走 Work 合并、单源直进详情）。
 */
@Composable
fun SectionListScreen(
    title: String,
    comics: List<ComicRow>,
    onBack: () -> Unit,
    onOpenComic: (ComicRow) -> Unit,
) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        SubTopBar(title, onBack)
        if (comics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这一区没有内容", color = c.fg3, fontSize = 12.sp)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGapV),
        ) {
            itemsIndexed(comics, key = { i, it -> "sl$i-" + it.id }) { _, comic ->
                ComicCell(
                    cover = comic.cover,
                    title = comic.title,
                    sub = comic.sub,
                    seed = comic.title.hashCode(),
                    onClick = { onOpenComic(comic) },
                )
            }
        }
    }
}

enum class Tone { OK, WARN, BAD, PLAIN, ACCENT }

@Composable
fun Pill(text: String, tone: Tone, fontSize: Float = 10.5f) {
    val c = LocalColors.current
    val (fg, bg) = when (tone) {
        Tone.OK -> c.ok to c.okBg
        Tone.WARN -> c.warn to c.warnBg
        Tone.BAD -> c.bad to c.badBg
        Tone.PLAIN -> c.fg2 to c.chip
        Tone.ACCENT -> c.accent to c.accent.copy(alpha = 0.13f)
    }
    Text(
        text = text,
        color = fg,
        fontSize = fontSize.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 小圆角按钮样式的文字（登录 / 切换）。 */
@Composable
fun MiniButton(text: String, onClick: () -> Unit, accent: Boolean = false) {
    val c = LocalColors.current
    Text(
        text = text,
        color = if (accent) c.accentInk else c.fg,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (accent) c.accent else c.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Text(
        text = text,
        color = if (selected) c.accentInk else c.fg2,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.accent else c.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** 封面占位：真封面没出来之前就是一块按书名取色的渐变，不显示灰色空框。 */
@Composable
fun CoverBox(
    seed: Int,
    title: String,
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    fontSize: Float = 21f,
) {
    val c = LocalColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(coverBrush(seed, c.dark)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(2),
            color = placeholderInk(c.dark),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 网格里的书：封面 + 两行标题 + 一行脚注。 */
@Composable
fun ComicCell(
    cover: String,
    title: String,
    sub: String,
    seed: Int,
    onClick: () -> Unit,
    small: Boolean = true,
    /** 右上角角标，跨源聚合时显示「3 源」 */
    badge: String = "",
) {
    val c = LocalColors.current
    val radius = if (small) 8.dp else 11.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(radius))
                .background(coverBrush(seed, c.dark)),
        ) {
            if (cover.isNotBlank()) {
                RemoteImage(
                    url = cover,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = title.take(2),
                    color = placeholderInk(c.dark),
                    fontSize = (if (small) 18f else 21f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    color = c.fg,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(c.bg.copy(alpha = 0.78f))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp),
                )
            }
        }
        Spacer(Modifier.height(if (small) 6.dp else 7.dp))
        Text(
            text = title,
            color = c.fg,
            fontSize = (if (small) 11f else 12.5f).sp,
            lineHeight = (if (small) 14.5f else 16.5f).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(if (small) 29.dp else 33.dp),
        )
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                color = c.fg3,
                fontSize = (if (small) 10f else 10.5f).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun HLine(modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Box(modifier.fillMaxWidth().height(1.dp).background(c.border))
}

@Composable
fun LoadingBox(height: Dp = 120.dp) {
    val c = LocalColors.current
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = c.accent,
        )
    }
}

/** 空状态：图标 + 一句话 + 一句下一步，不放大段说明。 */
@Composable
internal fun EmptyHint(title: String, message: String, icon: Glyph = Glyph.SEARCH) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphView(icon, c.fg3.copy(alpha = 0.5f), 40.dp, 1.4.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        Text(
            message,
            color = c.fg2,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 带一行小字的「提示条」，用于源状态、部分源失败这些情况。 */
@Composable
fun NoteBar(text: String, tone: Tone, inset: Boolean = true) {
    val c = LocalColors.current
    val (fg, bg) = when (tone) {
        Tone.OK -> c.ok to c.okBg
        Tone.WARN -> c.warn to c.warnBg
        Tone.BAD -> c.bad to c.badBg
        else -> c.fg2 to c.chip
    }
    Row(
        modifier = Modifier
            .padding(horizontal = if (inset) 18.dp else 0.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = fg, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/** 竖排「封面 + 文字 + 操作」的横卡，首页/书架的继续阅读都用它。 */
@Composable
fun ContinueCard(
    seed: Int,
    title: String,
    meta: String,
    action: String,
    onClick: () -> Unit,
    coverBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    coverUrl: String? = null,
    inset: Boolean = true,
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = if (inset) 18.dp else 0.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(c.card2)
            .border(1.dp, c.border, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(52.dp)
                .height(70.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(coverBrush(seed, c.dark)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                coverBitmap != null -> Image(
                    bitmap = coverBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                !coverUrl.isNullOrBlank() -> RemoteImage(
                    url = coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                else -> Text(
                    title.take(2),
                    color = placeholderInk(c.dark),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.fg,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(meta, color = c.fg3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            action,
            color = c.accentInk,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.accent)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 三列网格的间距，各家复用一份，改起来只改这里。 */
val GridGap = 9.dp
val GridGapV = 11.dp
val GridPad = 18.dp

/** 本地封面（导入时缓存的 jpg）在后台线程解出来；null 表示走渐变占位。 */
@Composable
fun rememberFileCover(path: String): androidx.compose.ui.graphics.ImageBitmap? =
    androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = path,
    ) {
        value = if (path.isEmpty()) {
            null
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(path)
                    if (f.isFile) {
                        android.graphics.BitmapFactory.decodeFile(path)
                            ?.asImageBitmap()
                    } else {
                        null
                    }
                }.getOrNull()
            }
        }
    }.value

/** 版本号直接读自安装包，装完一眼就能确认是哪个版本。 */
@Composable
fun rememberAppVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        runCatching {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            "v${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("")
    }
}

/**
 * 阅读页的真正全屏：把状态栏和导航栏一起收起来，从边缘上滑才临时出现。
 *
 * 以前阅读页只是「盖住了页面」，系统栏还在 —— 顶上一条时间/电量、底下一条手势条，
 * 看着就不像全屏阅读。进来隐藏、退出还原，两个阅读器（本地/在线）共用。
 */
@Composable
fun ImmersiveFullscreen(enabled: Boolean = true) {
    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(enabled, view) {
        val window = (context as? android.app.Activity)?.window
        if (!enabled || window == null) {
            onDispose { }
        } else if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController
            val oldBehavior = controller?.systemBarsBehavior
            controller?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(android.view.WindowInsets.Type.systemBars())
            onDispose {
                controller?.show(android.view.WindowInsets.Type.systemBars())
                if (oldBehavior != null) controller.systemBarsBehavior = oldBehavior
            }
        } else {
            @Suppress("DEPRECATION")
            val old = view.systemUiVisibility
            @Suppress("DEPRECATION")
            view.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            onDispose {
                @Suppress("DEPRECATION")
                view.systemUiVisibility = old
            }
        }
    }
}
