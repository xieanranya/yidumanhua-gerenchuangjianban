package com.enhance.comic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.model.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线图片加载：自己写而不是引 Coil，一是少一个依赖（曾经因为它构建失败过），
 * 二是这些站点的图要带「源自己指定的请求头」（Referer 等），Coil 默认不带。
 *
 * 1.9.19 修的四处（都是「又慢又看不了」的直接原因）：
 *   ① 按屏幕宽度降采样：以前整张原图解进内存（禁漫常见 1200×1700、一张 8MB），
 *      一屏三四张就顶到 GC/OOM，滚动明显发涩。
 *   ② 分块还原（禁漫）：源把正文按高度切成 num 段倒序存，参数由 pageImage 给。
 *      不还原整页就是上下错位的横条，看着像图坏了。
 *   ③ 请求头透传：Komiic / NoyACG / 绅士漫画 不给 Referer 就是 403，图根本出不来。
 *   ④ 并发上限 4 + 预取后两页：几十个请求一起抢带宽反而更慢。
 */
object RemoteImageCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) { cache.put(key, bmp) }
    fun clear() = cache.evictAll()
}

/** 同时在飞的图片请求数。太多会把带宽和线程池抢光，反而更慢。 */
private val imageGate = Semaphore(4)

/** 同一个 URL 只下一次：预取和当前页几乎同时发起，不去重就会白下一遍 */
private val imageScope = kotlinx.coroutines.CoroutineScope(
    Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
)
private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Bitmap?>>()

private fun cacheKey(url: String, descramble: Int, targetWidth: Int, crop: Int = 0): String {
    val bucket = if (targetWidth <= 0) 0 else ((targetWidth + 159) / 160) * 160
    // crop 进 key：裁过和没裁过是两张不同的图，混用会让开关看起来没反应
    return "$bucket|$descramble|$crop|$url"
}

/**
 * 自动裁白边：只切「整行 / 整列都是白」的那种边。
 *
 * 在线源的图经常四周留白，铺到手机上就是左右两条白杠，画面又小又空。
 * 画面里的白色气泡不会被切 —— 只要那一行有一个像素不是白的，整行就保留。
 *
 * @param level 0 保守 / 1 中 / 2 激进（阈值越高越容易判成白）
 */
fun cropWhiteEdges(src: Bitmap, level: Int): Bitmap {
    val w = src.width
    val h = src.height
    if (w < 16 || h < 16) return src
    val pixels = try {
        IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    } catch (e: Throwable) {
        return src
    }
    val th = when (level) {
        0 -> 232
        2 -> 250
        else -> 244
    }

    fun white(x: Int, y: Int): Boolean {
        val c = pixels[y * w + x]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= th && g >= th && b >= th
    }

    fun rowWhite(y: Int): Boolean {
        var x = 0
        while (x < w) {
            if (!white(x, y)) return false
            x += 3
        }
        return true
    }

    fun colWhite(x: Int): Boolean {
        var y = 0
        while (y < h) {
            if (!white(x, y)) return false
            y += 3
        }
        return true
    }

    var top = 0
    while (top < h - 1 && rowWhite(top)) top++
    var bottom = h - 1
    while (bottom > top && rowWhite(bottom)) bottom--
    var left = 0
    while (left < w - 1 && colWhite(left)) left++
    var right = w - 1
    while (right > left && colWhite(right)) right--

    val nw = right - left + 1
    val nh = bottom - top + 1
    // 裁掉一半以上多半是误判（纯白页 / 白底文字页），宁可不裁
    if (nw < w * 0.45f || nh < h * 0.45f) return src
    if (left == 0 && top == 0 && nw == w && nh == h) return src
    return try {
        Bitmap.createBitmap(src, left, top, nw, nh)
    } catch (e: Throwable) {
        src
    }
}

/** 按长边算 2 的幂采样率，保证解出来的图不超过目标宽度的两倍 */
private fun sampleSizeFor(w: Int, h: Int, targetW: Int): Int {
    if (w <= 0 || h <= 0 || targetW <= 0) return 1
    var sample = 1
    var cur = maxOf(w, h)
    val need = targetW * 2
    while (cur / 2 >= need) {
        cur /= 2
        sample *= 2
    }
    return sample
}

/**
 * 禁漫把一页按高度切成 num 段再倒序拼回去，还原就是第 num-1 段放最上、第 0 段放最下。
 * 段划分与源里 modifyImage 的算法一致（最后一段吃掉余数）。
 */
private fun descramble(src: Bitmap, num: Int): Bitmap {
    if (num <= 1) return src
    val w = src.width
    val h = src.height
    val blockSize = h / num
    if (w <= 0 || h <= 0 || blockSize <= 0) return src
    val remainder = h % num
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var y = 0f
    for (i in num - 1 downTo 0) {
        val start = i * blockSize
        val end = start + blockSize + if (i != num - 1) 0 else remainder
        val ch = end - start
        if (ch <= 0) continue
        canvas.drawBitmap(src, Rect(0, start, w, end), RectF(0f, y, w.toFloat(), y + ch), paint)
        y += ch
    }
    return out
}

/**
 * @param headers 源在 onImageLoad 里给的请求头（Referer/UA 等），不给会被防盗链拒
 * @param descramble 打乱段数，0/1 表示不需要还原
 * @param targetWidth 目标显示宽度（px），0 = 用原图
 */
suspend fun loadRemoteBitmap(
    url: String,
    headers: Map<String, String>? = null,
    descramble: Int = 0,
    targetWidth: Int = 0,
    /** 0 = 不裁；1/2/3 = 裁白边（等级 + 1） */
    crop: Int = 0,
): Bitmap? {
    if (url.isBlank()) return null
    val key = cacheKey(url, descramble, targetWidth, crop)
    RemoteImageCache.get(key)?.let { return it }

    // 已经在下的直接等同一个任务，别重复开连接
    inflight[key]?.let { return it.await() }
    val job = imageScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        imageGate.withPermit {
            RemoteImageCache.get(key)?.let { return@withPermit it }
            withContext(Dispatchers.IO) { downloadAndDecode(url, headers, descramble, targetWidth, key) }
        }
    }
    val prev = inflight.putIfAbsent(key, job)
    if (prev != null) { job.cancel(); return prev.await() }
    job.start()
    return try {
        job.await()
    } finally {
        inflight.remove(key, job)
    }
}

private fun downloadAndDecode(
    url: String,
    headers: Map<String, String>?,
    descramble: Int,
    targetWidth: Int,
    key: String,
): Bitmap? = runCatching {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 12_000
    conn.readTimeout = 20_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    )
    conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
    var hasReferer = false
    headers?.forEach { (k, v) ->
        val lk = k.lowercase()
        if (lk == "referer") hasReferer = true
        if (v.isNotBlank() && lk != "accept-encoding" && lk != "connection" && lk != "host") {
            conn.setRequestProperty(k, v)
        }
    }
    // 源没声明 Referer 的（封面/缩略图走不到 onThumbnailLoad 的部分），
    // 用图片自己的站做 Referer —— 绝大多数防盗链只校验「同站」，这样就不会白板。
    if (!hasReferer) {
        runCatching {
            val u = URL(url)
            conn.setRequestProperty("Referer", "${u.protocol}://${u.host}/")
        }
    }
    val bytes = conn.inputStream.use { it.readBytes() }
    conn.disconnect()
    if (bytes.isEmpty()) return@runCatching null

    // 先量尺寸，再按需降采样 —— 别把原图整张吃进内存
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
    val fixed = if (descramble > 1) descramble(raw, descramble) else raw
    RemoteImageCache.put(key, fixed)
    fixed
}.getOrNull()

@Composable
fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentScale: ContentScale = ContentScale.Crop,
    headers: Map<String, String>? = null,
    targetWidth: Int = 0,
) {
    val key = cacheKey(url, 0, targetWidth)
    var bmp by remember(url) { mutableStateOf(RemoteImageCache.get(key)) }
    LaunchedEffect(url) {
        if (bmp == null && url.isNotBlank()) bmp = loadRemoteBitmap(url, headers, 0, targetWidth)
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val cur = bmp
        if (cur != null) {
            Image(
                bitmap = cur.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * 章节阅读用的整页图（Fit，不裁）。按屏幕像素宽解码，翻页不再等半天。
 */
@Composable
fun RemotePageImage(
    url: String,
    headers: Map<String, String>? = null,
    descramble: Int = 0,
    /** 阅读器的显示设置（灰度/反色/蓝光）。传 null 就是原图。 */
    prefs: ReaderPrefs? = null,
    /**
     * true = 适应屏幕（相册/双页：整页缩到屏内，可能左右留边）；
     * false = 铺满屏宽（Webtoon：长条图按宽度铺满、上下连续）。
     */
    fit: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val targetPx = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val crop = if (prefs?.autoCropOnline == true) prefs.cropLevel + 1 else 0
    val key = cacheKey(url, descramble, targetPx, crop)

    var bmp by remember(url, descramble, crop) { mutableStateOf(RemoteImageCache.get(key)) }
    var failed by remember(url, descramble, crop) { mutableStateOf(false) }
    var attempt by remember(url) { mutableStateOf(0) }

    LaunchedEffect(url, descramble, crop, attempt) {
        if (bmp != null) return@LaunchedEffect
        failed = false
        val got = loadRemoteBitmap(url, headers, descramble, targetPx, crop)
        if (got != null) bmp = got else failed = true
    }

    // 整页按**图片自己的宽高比**铺满屏幕宽度，页与页之间无缝。
    // 以前外面套一个写死的 0.7 比例框、图在里面 Fit —— 比例不完全一致就留黑边，
    // 看起来就是「没全屏、四周有黑框」。
    val filter = remember(prefs?.grayscale, prefs?.invert) {
        readerColorFilter(prefs?.grayscale == true, prefs?.invert == true)
    }
    val blue = (prefs?.blueLight ?: 0).coerceIn(0, 100)
    Box(modifier = if (fit) modifier.fillMaxSize() else modifier.fillMaxWidth()) {
        val cur = bmp
        when {
            cur != null -> Image(
                bitmap = cur.asImageBitmap(),
                contentDescription = null,
                modifier = if (fit) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(cur.width.toFloat() / maxOf(cur.height, 1))
                },
                contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
                colorFilter = filter,
            )
            failed -> Box(
                if (fit) Modifier.fillMaxSize().background(Color(0xFF141414))
                else Modifier.fillMaxWidth().aspectRatio(0.7f).background(Color(0xFF141414)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "这一页没加载出来\n点一下重试",
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { attempt++ }.padding(12.dp),
                )
            }
            else -> Box(
                if (fit) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().aspectRatio(0.7f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color(0xCCFF6E9C), strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
        // 蓝光过滤：整页盖一层暖色，强度跟着设置走（0 就是不加）
        if (blue > 0 && cur != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color(0xFFFFA64D).copy(alpha = blue / 100f * 0.35f)),
            )
        }
    }
}

/**
 * 灰度 / 反色 转成 ColorFilter。
 *
 * 阅读器的「滤镜」以前只对本地书生效（图片走原生绘制），在线看的时候点了没反应。
 * 现在两张路都经过这里：矩阵用 Android 的 ColorMatrix 组合，再交给 Compose 绘制。
 */
private fun readerColorFilter(gray: Boolean, invert: Boolean): androidx.compose.ui.graphics.ColorFilter? {
    if (!gray && !invert) return null
    return runCatching {
        val m = android.graphics.ColorMatrix()
        if (gray) {
            val g = android.graphics.ColorMatrix()
            g.setSaturation(0f)
            m.postConcat(g)
        }
        if (invert) {
            val inv = android.graphics.ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            m.postConcat(inv)
        }
        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(m.array),
        )
    }.getOrNull()
}

/** 预取：先把下一两页下好，划下去直接命中缓存 */
suspend fun prefetchPage(
    url: String,
    headers: Map<String, String>?,
    descramble: Int,
    targetWidth: Int,
    crop: Int = 0,
) {
    if (url.isBlank()) return
    if (RemoteImageCache.get(cacheKey(url, descramble, targetWidth, crop)) != null) return
    loadRemoteBitmap(url, headers, descramble, targetWidth, crop)
}
