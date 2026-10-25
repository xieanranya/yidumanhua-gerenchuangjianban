package com.enhance.comic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线图片加载：自己写而不是引 Coil，一是少一个依赖（曾经因为它构建失败过），
 * 二是这些站点的图需要带 Referer，Coil 默认不带。
 */
object RemoteImageCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(url: String): Bitmap? = cache.get(url)
    fun put(url: String, bmp: Bitmap) { cache.put(url, bmp) }
    fun clear() = cache.evictAll()
}

suspend fun loadRemoteBitmap(url: String, referer: String? = null): Bitmap? {
    RemoteImageCache.get(url)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            )
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { RemoteImageCache.put(url, it) }
        }.getOrNull()
    }
}

@Composable
fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentScale: ContentScale = ContentScale.Crop,
    referer: String? = null,
) {
    var bmp by remember(url) { mutableStateOf(RemoteImageCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp == null && url.isNotBlank()) bmp = loadRemoteBitmap(url, referer)
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
                strokeWidth = androidx.compose.ui.unit.Dp(2f),
            )
        }
    }
}

/** 章节阅读用的整页图（Fit，不裁） */
@Composable
fun RemotePageImage(url: String, referer: String? = null, modifier: Modifier = Modifier) {
    var bmp: ImageBitmap? by remember(url) { mutableStateOf(null) }
    LaunchedEffect(url) {
        val b = loadRemoteBitmap(url, referer)
        if (b != null) bmp = b.asImageBitmap()
    }
    Box(modifier = modifier) {
        bmp?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
