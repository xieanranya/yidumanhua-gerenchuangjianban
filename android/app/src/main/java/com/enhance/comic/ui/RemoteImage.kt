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
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.Dispatchers
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

private fun cacheKey(url: String, descramble: Int, targetWidth: Int): String {
    val bucket = if (targetWidth <= 0) 0 else ((targetWidth + 159) / 160) * 160
    return "$bucket|$descramble|$url"
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
): Bitmap? {
    if (url.isBlank()) return null
    val key = cacheKey(url, descramble, targetWidth)
    RemoteImageCache.get(key)?.let { return it }

    return imageGate.withPermit {
        RemoteImageCache.get(key)?.let { return@withPermit it }
        withContext(Dispatchers.IO) {
            runCatching {
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
        }
    }
}

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
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val targetPx = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val key = cacheKey(url, descramble, targetPx)

    var bmp by remember(url, descramble) { mutableStateOf(RemoteImageCache.get(key)) }
    var failed by remember(url, descramble) { mutableStateOf(false) }
    var attempt by remember(url) { mutableStateOf(0) }

    LaunchedEffect(url, descramble, attempt) {
        if (bmp != null) return@LaunchedEffect
        failed = false
        val got = loadRemoteBitmap(url, headers, descramble, targetPx)
        if (got != null) bmp = got else failed = true
    }

    Box(modifier = modifier) {
        val cur = bmp
        when {
            cur != null -> Image(
                bitmap = cur.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            failed -> Box(
                Modifier.fillMaxSize().background(Color(0xFF141414)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "这一页没加载出来\n点一下重试",
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { attempt++ }.padding(12.dp),
                )
            }
            else -> CircularProgressIndicator(
                color = Color(0xCCFF6E9C),
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp).align(Alignment.Center),
            )
        }
    }
}

/** 预取：先把下一两页下好，划下去直接命中缓存 */
suspend fun prefetchPage(url: String, headers: Map<String, String>?, descramble: Int, targetWidth: Int) {
    if (url.isBlank()) return
    if (RemoteImageCache.get(cacheKey(url, descramble, targetWidth)) != null) return
    loadRemoteBitmap(url, headers, descramble, targetWidth)
}
