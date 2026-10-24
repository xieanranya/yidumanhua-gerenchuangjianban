package com.enhance.comic.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import java.io.InputStream

object ImageUtil {

    private val SUPPORTED = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "heic")

    /** True when [name] looks like a page image. */
    fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED && !name.startsWith("__MACOSX") && !name.substringAfterLast('/').startsWith(".")
    }

    /**
     * Decodes an image, subsampling so the result is at least [targetWidth] wide.
     * [open] must return a fresh stream on every call (called twice).
     */
    fun decodeStream(open: () -> InputStream?, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val target = targetWidth.coerceAtLeast(64)
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            open()?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Reads only the aspect ratio (w / h) of an encoded image, or null when unreadable. */
    fun readAspect(open: () -> InputStream?): Float? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** [decodeStream] for archives that can only hand out a byte array (RAR). */
    fun decodeBytes(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (_: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val target = targetWidth.coerceAtLeast(64)
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** [readAspect] for archives that can only hand out a byte array (RAR). */
    fun aspectOfBytes(bytes: ByteArray): Float? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Downscales [src] to at most [maxWidth] and re-encodes it as a JPEG cover. */
    fun encodeCover(src: Bitmap, maxWidth: Int = 420, quality: Int = 86): ByteArray? {
        if (src.width <= 0 || src.height <= 0) return null
        val scaled = if (src.width > maxWidth) {
            val h = (src.height.toFloat() / src.width * maxWidth).toInt().coerceAtLeast(1)
            try {
                Bitmap.createScaledBitmap(src, maxWidth, h, true)
            } catch (_: Throwable) {
                src
            }
        } else {
            src
        }
        val out = java.io.ByteArrayOutputStream()
        val ok = try {
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } catch (_: Throwable) {
            false
        }
        if (scaled !== src) scaled.recycle()
        return if (ok && out.size() > 0) out.toByteArray() else null
    }

    /**
     * Detects the ink area of a page, returned in normalized 0..1 coordinates, or null when
     * there is nothing worth trimming.
     *
     * 阈值不是一个固定的 238，而是相对「纸色」判断 —— 扫描件常常是米白或浅灰，固定阈值
     * 要么削进画面，要么一格都不裁。深色页（夜景、反色扫描）直接跳过，免得把画面裁掉。
     */
    fun detectContentBox(src: Bitmap, relativeDrop: Int = 22): RectF? {
        if (src.width < 8 || src.height < 8) return null
        val w = 96
        val h = (src.height.toFloat() / src.width * w).toInt().coerceIn(8, 1600)
        val small = try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (_: Throwable) {
            return null
        }
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        fun luma(x: Int, y: Int): Int {
            val p = px[y * w + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return (r * 77 + g * 150 + b * 29) shr 8
        }

        // 纸色取自四边 2px 宽的框，用中位数，个别噪点不影响。
        val edge = IntArray((w * 4) + (h * 4))
        var e = 0
        for (x in 0 until w) {
            edge[e++] = luma(x, 0)
            edge[e++] = luma(x, 1)
            edge[e++] = luma(x, h - 1)
            edge[e++] = luma(x, h - 2)
        }
        for (y in 0 until h) {
            edge[e++] = luma(0, y)
            edge[e++] = luma(1, y)
            edge[e++] = luma(w - 1, y)
            edge[e++] = luma(w - 2, y)
        }
        edge.sort()
        val paper = edge[edge.size / 2]
        // 深色页不裁。
        if (paper < 96) return null
        val cut = paper - relativeDrop

        val rowInk = IntArray(h)
        val colInk = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (luma(x, y) < cut) {
                    rowInk[y]++
                    colInk[x]++
                }
            }
        }

        // 一行里墨点超过 1.2% 才算内容：一粒灰尘或定位标记不该撑大边框。
        val rowMin = maxOf(1, (w * 0.012f).toInt())
        val colMin = maxOf(1, (h * 0.012f).toInt())

        var top = 0
        while (top < h - 1 && rowInk[top] < rowMin) top++
        var bottom = h - 1
        while (bottom > top && rowInk[bottom] < rowMin) bottom--
        var left = 0
        while (left < w - 1 && colInk[left] < colMin) left++
        var right = w - 1
        while (right > left && colInk[right] < colMin) right--

        if (top >= bottom || left >= right) return null

        val pad = 0.004f
        val l = (left.toFloat() / w - pad).coerceAtLeast(0f)
        val t = (top.toFloat() / h - pad).coerceAtLeast(0f)
        val r = ((right + 1).toFloat() / w + pad).coerceAtMost(1f)
        val b = ((bottom + 1).toFloat() / h + pad).coerceAtMost(1f)

        // 安全边界：任何一维都不能裁到只剩四分之一，面积不能裁掉超过三分之二。
        // 检测失手时宁可整页不裁，也不能把画面切成一条。
        if (r - l < 0.25f || b - t < 0.25f) return null
        if ((r - l) * (b - t) < 0.34f) return null
        if ((r - l) > 0.99f && (b - t) > 0.99f) return null
        return RectF(l, t, r, b)
    }

}
