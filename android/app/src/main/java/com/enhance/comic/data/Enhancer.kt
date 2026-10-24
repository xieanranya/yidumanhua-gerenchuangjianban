package com.enhance.comic.data

import android.graphics.Bitmap
import com.enhance.comic.model.EnhanceSettings

/**
 * CPU quality enhancement, applied once per page on the loader thread.
 *
 * This replaces the GLES compute chain: an EGL context that fails to come up means a blank
 * page, and a reader that cannot show a page has no other feature left. The passes are the
 * same three ideas (unsharp mask, denoise, tone curve) at display resolution, which is all
 * that is visible on screen anyway.
 */
object Enhancer {

    fun process(src: Bitmap, s: EnhanceSettings): Bitmap {
        if (src.width < 8 || src.height < 8) return src
        val tone = s.contrastPass || s.brightness != 100 || s.contrastLevel != 100 || s.gamma != 100
        if (!s.sharpen && !s.denoise && !tone) return src

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        try {
            src.getPixels(px, 0, w, 0, 0, w, h)
        } catch (_: Throwable) {
            return src
        }

        var out = px
        if (s.denoise) {
            out = blur3(out, w, h, (0.35f + 0.5f * s.denoiseBlendF).coerceIn(0f, 1f))
        }
        if (s.sharpen) {
            out = sharpen(out, w, h, s.sharpenAmount / 100f * 0.9f)
        }
        if (tone) {
            applyTone(out, s)
        }

        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, w, 0, 0, w, h)
            bmp
        } catch (_: Throwable) {
            src
        }
    }

    /** Separable 1-2-1 blur, blended with the original for a soft denoise. */
    private fun blur3(src: IntArray, w: Int, h: Int, blend: Float): IntArray {
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = src[row + if (x > 0) x - 1 else x]
                val c = src[row + x]
                val r = src[row + if (x < w - 1) x + 1 else x]
                tmp[row + x] = avg3(l, c, r)
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val up = (if (y > 0) y - 1 else y) * w
            val cur = y * w
            val dn = (if (y < h - 1) y + 1 else y) * w
            for (x in 0 until w) {
                val blurred = avg3(tmp[up + x], tmp[cur + x], tmp[dn + x])
                out[cur + x] = mix(src[cur + x], blurred, blend)
            }
        }
        return out
    }

    /**
     * Unsharp mask against a two-pass 1-2-1 blur, i.e. roughly a 2px radius.
     *
     * A single 3x3 pass (radius ~0.7px) only hardens JPEG mosquito noise; comic line art
     * needs a wider kernel before the edge contrast is visible when the page is viewed at
     * fit-to-screen. Amount stays under 1.1 so published pages do not gain white halos.
     */
    private fun sharpen(src: IntArray, w: Int, h: Int, amount: Float): IntArray {
        if (amount <= 0.01f) return src
        var blurred = blur3(src, w, h, 1f)
        blurred = blur3(blurred, w, h, 1f)
        val out = IntArray(src.size)
        val k = amount.coerceIn(0f, 1.1f)
        for (i in src.indices) {
            val c = src[i]
            val b = blurred[i]
            val a = c ushr 24
            val r = channel((c ushr 16) and 0xFF, (b ushr 16) and 0xFF, k)
            val g = channel((c ushr 8) and 0xFF, (b ushr 8) and 0xFF, k)
            val bl = channel(c and 0xFF, b and 0xFF, k)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return out
    }

    private fun channel(c: Int, blur: Int, amount: Float): Int =
        (c + (c - blur) * amount).toInt().coerceIn(0, 255)

    /** Brightness / contrast / gamma through a 256 entry table: one pass, no extra buffers. */
    private fun applyTone(px: IntArray, s: EnhanceSettings) {
        val brightness = s.brightnessF
        val contrast = s.contrastLevelF
        val gamma = s.gammaF
        val sCurve = if (s.contrastPass) 0.25f * s.contrastBlendF else 0f
        val lut = IntArray(256)
        for (i in 0 until 256) {
            var v = i / 255f
            v *= brightness
            v = (v - 0.5f) * contrast + 0.5f
            if (sCurve > 0f) {
                val soft = v * v * (3f - 2f * v)
                v += (soft - v) * sCurve
            }
            v = v.coerceIn(0f, 1f)
            v = Math.pow(v.toDouble(), 1.0 / gamma.coerceAtLeast(0.05f).toDouble()).toFloat()
            lut[i] = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            val r = lut[(c ushr 16) and 0xFF]
            val g = lut[(c ushr 8) and 0xFF]
            val b = lut[c and 0xFF]
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun avg3(a: Int, b: Int, c: Int): Int {
        val r = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF) * 2 + ((c ushr 16) and 0xFF)) shr 2
        val g = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF) * 2 + ((c ushr 8) and 0xFF)) shr 2
        val bl = ((a and 0xFF) + (b and 0xFF) * 2 + (c and 0xFF)) shr 2
        val alpha = ((a ushr 24) + (b ushr 24) * 2 + (c ushr 24)) shr 2
        return (alpha shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * k).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * k).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
