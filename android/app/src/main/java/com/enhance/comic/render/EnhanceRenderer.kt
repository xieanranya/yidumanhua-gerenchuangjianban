package com.enhance.comic.render

import android.graphics.Bitmap
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.PageRef
import com.enhance.comic.model.ReaderPrefs
import com.enhance.comic.model.ReadingMode
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sign

/**
 * Draws enhanced pages.
 *
 * Each cached page owns three textures: the uploaded source (RGBA8) and a pair of RGBA8
 * ping-pong targets. The compute chain runs once per page load / settings change, so
 * scrolling only costs a textured quad per visible page.
 */
class EnhanceRenderer(private val host: PageGLView) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "EnhanceRenderer"
        private const val MAX_SLOTS = 7
        private const val WG = 16
    }

    @Volatile var mode: ReadingMode = ReadingMode.PAGED
    @Volatile var prefs: ReaderPrefs = ReaderPrefs()
    @Volatile var enhance: EnhanceSettings = EnhanceSettings()
    @Volatile var pageRefs: List<PageRef> = emptyList()
    @Volatile var current: Int = 0
    @Volatile var scroll: Float = 0f
    @Volatile var zoom: Float = 1f
    @Volatile var panX: Float = 0f
    @Volatile var panY: Float = 0f
    @Volatile var provider: PageProvider? = null

    /** Finger-following page turn: fraction of the viewport the current page has slid. */
    @Volatile var turnX: Float = 0f
    @Volatile var turnY: Float = 0f
    /** Ref index of the page sliding in, or -1 when the drag has no destination. */
    @Volatile var turnIndex: Int = -1

    /** Scroll modes scale their layout width by this factor. */
    @Volatile var layoutZoom: Float = 1f

    /** True while a two-page spread is on screen; the view steps by two then. */
    @Volatile var spreadActive: Boolean = false

    /**
     * How many refs one page turn moves. Latched only while the view is settled so a drag
     * that starts on a spread still advances by two.
     */
    @Volatile var stepSpan: Int = 1

    /** Half extent the current page can pan at its zoom, published for the touch handler. */
    @Volatile var maxPanX: Float = 0f
    @Volatile var maxPanY: Float = 0f

    @Volatile var contentExtent: Float = 0f
        private set

    private var surfaceW = 0
    private var surfaceH = 0

    private var copyProgram: GlProgram? = null
    private var displayProgram: GlProgram? = null
    private var sharpenProgram: GlProgram? = null
    private var contrastProgram: GlProgram? = null
    private val denoisePrograms = HashMap<Int, GlProgram?>()

    private var quadVbo = 0
    private var initialized = false

    private val slots = LinkedHashMap<Int, Slot>()
    private var frame = 0L
    private var settingsVersion = 0

    private var starts = FloatArray(0)
    private var extents = FloatArray(0)
    private var layoutRefs: List<PageRef>? = null
    private var layoutW = 0f
    private var layoutH = 0f
    private var layoutZoomCache = 1f

    /** Continuous vertical reading looks better without visible seams between pages. */
    private val gapPx: Float
        get() = if (mode == ReadingMode.VERTICAL) 0f else 8f * host.resources.displayMetrics.density

    // ---------------------------------------------------------------- lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        slots.values.forEach { destroy(it) }
        slots.clear()
        layoutRefs = null

        val buffer = java.nio.ByteBuffer.allocateDirect(Shaders.QUAD.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(Shaders.QUAD)
                position(0)
            }
        val ids = IntArray(1)
        GLES31.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, quadVbo)
        GLES31.glBufferData(
            GLES31.GL_ARRAY_BUFFER,
            Shaders.QUAD.size * 4,
            buffer,
            GLES31.GL_STATIC_DRAW,
        )

        copyProgram = GlProgram.link(Shaders.VERT, Shaders.FRAG_COPY)
        displayProgram = GlProgram.link(Shaders.VERT, Shaders.FRAG_DISPLAY)
        sharpenProgram = GlProgram.compute(Shaders.COMPUTE_SHARPEN)
        contrastProgram = GlProgram.compute(Shaders.COMPUTE_CONTRAST)
        denoisePrograms.clear()

        initialized = copyProgram != null && displayProgram != null
        Log.i(
            TAG,
            "surface created: display=$initialized compute=" +
                "${sharpenProgram != null && contrastProgram != null}",
        )
        host.onGlReady(initialized)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        layoutRefs = null
    }

    override fun onDrawFrame(gl: GL10?) {
        val vw = surfaceW
        val vh = surfaceH
        if (vw <= 0 || vh <= 0) return

        GLES31.glViewport(0, 0, vw, vh)
        GLES31.glClearColor(0.05f, 0.05f, 0.06f, 1f)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

        val dp = displayProgram ?: return
        frame++
        if (mode == ReadingMode.PAGED) drawPaged(dp, vw, vh) else drawScroll(dp, vw, vh)
    }

    // ------------------------------------------------------------------ drawing

    private fun drawPaged(dp: GlProgram, vw: Int, vh: Int) {
        val refs = pageRefs
        if (refs.isEmpty()) {
            spreadActive = false
            stepSpan = 1
            return
        }
        val idx = current.coerceIn(0, refs.lastIndex)
        val span = spanFor(idx, vw, vh)
        spreadActive = span == 2
        stepSpan = span

        val tx = turnX * vw
        val ty = turnY * vh
        val settled = abs(tx) < 0.5f && abs(ty) < 0.5f

        // One shared scale keeps the outgoing and incoming pages the same size mid-turn.
        val fit = if (span == 2) {
            val widest = maxOf(pageAspect(refs[idx]), pageAspect(refs[idx + 1]))
            minOf((vw / 2f) / widest, vh.toFloat()) * zoom
        } else {
            minOf(vw / pageAspect(refs[idx]), vh.toFloat()) * zoom
        }

        val unitW = if (span == 2) {
            (pageAspect(refs[idx]) + pageAspect(refs[idx + 1])) * fit
        } else {
            pageAspect(refs[idx]) * fit
        }
        maxPanX = ((unitW - vw) / 2f).coerceAtLeast(0f)
        maxPanY = ((fit - vh) / 2f).coerceAtLeast(0f)
        val cx = vw / 2f + panX.coerceIn(-maxPanX, maxPanX) + tx
        val cy = vh / 2f + panY.coerceIn(-maxPanY, maxPanY) + ty

        val incoming = turnIndex
        if (!settled && incoming in refs.indices) {
            var icx = cx
            var icy = cy
            if (abs(tx) >= abs(ty)) {
                icx = cx - sign(tx) * vw
            } else {
                icy = cy - sign(ty) * vh
            }
            drawUnit(dp, vw, vh, incoming, spanFor(incoming, vw, vh), icx, icy, fit)
        }
        drawUnit(dp, vw, vh, idx, span, cx, cy, fit)
    }

    /** How many refs make up one screen: two side by side, or just one. */
    private fun spanFor(idx: Int, vw: Int, vh: Int): Int =
        if (prefs.doublePage && vw > vh && spreadPartner(idx) >= 0) 2 else 1

    private fun drawUnit(
        dp: GlProgram,
        vw: Int,
        vh: Int,
        start: Int,
        span: Int,
        cx: Float,
        cy: Float,
        fit: Float,
    ) {
        if (span == 2 && pageRefs.indices.contains(start + 1)) {
            // Right-to-left manga puts the earlier page on the right, and the two pages
            // meet at the centre line instead of leaving a gap between unequal widths.
            val leftRef = if (prefs.rightToLeft) start + 1 else start
            val rightRef = if (prefs.rightToLeft) start else start + 1
            val leftW = pageAspect(pageRefs[leftRef]) * fit
            val rightW = pageAspect(pageRefs[rightRef]) * fit
            drawPage(dp, vw, vh, leftRef, cx - leftW / 2f, cy, fit)
            drawPage(dp, vw, vh, rightRef, cx + rightW / 2f, cy, fit)
        } else {
            drawPage(dp, vw, vh, start, cx, cy, fit)
        }
    }

    private fun drawPage(
        dp: GlProgram,
        vw: Int,
        vh: Int,
        refIndex: Int,
        cx: Float,
        cy: Float,
        fit: Float,
    ) {
        val ref = pageRefs.getOrNull(refIndex) ?: return
        val slot = ensureSlot(ref) ?: return
        refreshIfStale(slot)

        val cropW = (slot.cropR - slot.cropL).coerceAtLeast(0.01f)
        val cropH = (slot.cropB - slot.cropT).coerceAtLeast(0.01f)
        val pageW = slot.w * cropW * (if (ref.isSplit) 0.5f else 1f)
        val pageH = slot.h * cropH
        if (pageW < 1f || pageH < 1f) return

        val drawW = pageW * fit
        val drawH = pageH * fit
        val x0 = (cx - drawW / 2f) / vw * 2f - 1f
        val y0 = 1f - (cy + drawH / 2f) / vh * 2f
        drawQuad(dp, slot, x0, y0, drawW / vw * 2f, drawH / vh * 2f, uvFor(slot, ref), drawW)
    }

    private fun pageAspect(ref: PageRef): Float {
        val src = provider?.displayAspect(ref.index) ?: 1f
        val a = if (ref.isSplit) src * 0.5f else src
        return a.coerceIn(0.08f, 12f)
    }

    private fun spreadPartner(idx: Int): Int {
        val refs = pageRefs
        val a = refs.getOrNull(idx) ?: return -1
        if (a.isSplit) return -1
        val b = refs.getOrNull(idx + 1) ?: return -1
        if (b.isSplit || b.index == a.index) return -1
        return idx + 1
    }

    private fun drawScroll(dp: GlProgram, vw: Int, vh: Int) {
        ensureLayout(vw, vh)
        spreadActive = false
        stepSpan = 1
        val n = starts.size
        if (n == 0) return
        val vertical = mode == ReadingMode.VERTICAL
        val viewExtent = if (vertical) vh.toFloat() else vw.toFloat()
        val start = scroll
        val z = layoutZoom

        for (i in 0 until n) {
            val s = starts[i]
            val total = extents[i]
            if (s + total < start) continue
            if (s > start + viewExtent) break

            val ref = pageRefs[i]
            val slot = ensureSlot(ref) ?: continue
            refreshIfStale(slot)
            val uv = uvFor(slot, ref)
            val len = (total - gapPx).coerceAtLeast(1f)
            val offset = s - start

            if (vertical) {
                val y0 = 1f - (offset + len) / vh * 2f
                drawQuad(dp, slot, -1f, y0, 2f, len / vh * 2f, uv, vw * z)
            } else {
                val drawH = vh * z
                val x0 = offset / vw * 2f - 1f
                drawQuad(dp, slot, x0, drawH / vh, len / vw * 2f, drawH / vh * 2f, uv, len)
            }
        }
    }

    private fun drawQuad(
        dp: GlProgram,
        slot: Slot,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        uv: FloatArray,
        drawWidthPx: Float,
    ) {
        dp.use()
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, slot.resultTex)
        GLES31.glUniform1i(dp.u("uTex"), 0)
        GLES31.glUniform4f(dp.u("uRect"), x, y, w, h)
        GLES31.glUniform4f(dp.u("uUv"), uv[0], uv[1], uv[2], uv[3])
        GLES31.glUniform2f(dp.u("uTexSize"), slot.w.toFloat(), slot.h.toFloat())
        GLES31.glUniform1f(
            dp.u("uTexelsPerPixel"),
            slot.w * (uv[2] - uv[0]) / drawWidthPx.coerceAtLeast(1f),
        )
        GLES31.glUniform1i(dp.u("uFilter"), enhance.scaleFilter.ordinal)
        GLES31.glUniform3f(
            dp.u("uColor"),
            enhance.brightnessF,
            enhance.contrastLevelF,
            enhance.gammaF,
        )
        bindQuad()
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindQuad() {
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, quadVbo)
        GLES31.glEnableVertexAttribArray(0)
        GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 8, 0)
    }

    private fun uvFor(slot: Slot, ref: PageRef): FloatArray {
        val l = slot.cropL
        val t = slot.cropT
        val r = slot.cropR
        val b = slot.cropB
        if (!ref.isSplit) return floatArrayOf(l, t, r, b)
        val mid = (l + r) * 0.5f
        val left = floatArrayOf(l, t, mid, b)
        val right = floatArrayOf(mid, t, r, b)
        return if (prefs.rightToLeft) {
            if (ref.half == 1) right else left
        } else {
            if (ref.half == 1) left else right
        }
    }

    // ------------------------------------------------------------------ layout

    private fun ensureLayout(vw: Int, vh: Int) {
        val refs = pageRefs
        val z = layoutZoom
        if (layoutRefs === refs && layoutW == vw.toFloat() && layoutH == vh.toFloat() &&
            layoutZoomCache == z
        ) {
            return
        }

        val n = refs.size
        starts = FloatArray(n)
        extents = FloatArray(n)
        val vertical = mode == ReadingMode.VERTICAL
        val gap = gapPx
        var acc = 0f
        for (i in 0 until n) {
            val ref = refs[i]
            val src = provider?.displayAspect(ref.index) ?: 1f
            val aspect = (if (ref.isSplit) src * 0.5f else src).coerceIn(0.1f, 10f)
            val len = if (vertical) (vw * z) / aspect else (vh * z) * aspect
            starts[i] = acc
            extents[i] = len + gap
            acc += len + gap
        }
        contentExtent = acc
        layoutRefs = refs
        layoutW = vw.toFloat()
        layoutH = vh.toFloat()
        layoutZoomCache = z
    }

    /** Page index shown at [scroll], used for the progress readout. */
    fun pageAtScroll(scrollPx: Float, vw: Int, vh: Int): Int {
        ensureLayout(vw, vh)
        if (starts.isEmpty()) return 0
        var lo = 0
        var hi = starts.lastIndex
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] <= scrollPx) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    fun extentOfPage(index: Int): Float = extents.getOrElse(index) { 0f }

    /** Scroll offset that puts [index] at the top of the viewport. GL thread only. */
    fun seekScrollTo(index: Int, vw: Int, vh: Int): Float {
        ensureLayout(vw, vh)
        return starts.getOrElse(index) { 0f }
    }

    // ------------------------------------------------------------------ loading

    private fun ensureSlot(ref: PageRef): Slot? {
        val existing = slots[ref.index]
        if (existing != null) {
            existing.lastUsed = frame
            return existing
        }
        host.requestLoad(ref.index, decodeWidth(ref.index))
        return null
    }

    private fun decodeWidth(pageIndex: Int): Int {
        val aspect = provider?.displayAspect(pageIndex) ?: 1f
        val base = when (mode) {
            ReadingMode.PAGED -> surfaceW * 1.15f * zoom
            ReadingMode.VERTICAL -> surfaceW * layoutZoom
            ReadingMode.HORIZONTAL -> surfaceH * aspect * layoutZoom
        }
        return base.toInt().coerceIn(720, 2400)
    }

    /** Called on the GL thread from [PageGLView]. */
    fun uploadPage(index: Int, bitmap: Bitmap, crop: FloatArray) {
        if (!initialized) {
            bitmap.recycle()
            return
        }
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            bitmap.recycle()
            return
        }

        slots.remove(index)?.let { destroy(it) }

        val tex = IntArray(1)
        GLES31.glGenTextures(1, tex, 0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tex[0])
        applyTextureParams()
        try {
            GLUtils.texImage2D(GLES31.GL_TEXTURE_2D, 0, bitmap, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "upload failed", t)
            bitmap.recycle()
            GLES31.glDeleteTextures(1, tex, 0)
            return
        }
        bitmap.recycle()

        val slot = Slot(index, w, h)
        slot.srcTex = tex[0]
        slot.cropL = crop[0]
        slot.cropT = crop[1]
        slot.cropR = crop[2]
        slot.cropB = crop[3]
        slot.lastUsed = frame
        createTargets(slot)
        buildEnhanced(slot)
        slots[index] = slot
        evictIfNeeded()
        host.postFrame()
    }

    private fun createTargets(slot: Slot) {
        val tex = IntArray(2)
        GLES31.glGenTextures(2, tex, 0)
        slot.texA = tex[0]
        slot.texB = tex[1]
        for (t in tex) {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, t)
            applyTextureParams()
            GLES31.glTexImage2D(
                GLES31.GL_TEXTURE_2D,
                0,
                GLES31.GL_RGBA8,
                slot.w,
                slot.h,
                0,
                GLES31.GL_RGBA,
                GLES31.GL_UNSIGNED_BYTE,
                null,
            )
        }
        val fbo = IntArray(2)
        GLES31.glGenFramebuffers(2, fbo, 0)
        slot.fboA = fbo[0]
        slot.fboB = fbo[1]
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, slot.fboA)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER,
            GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D,
            slot.texA,
            0,
        )
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, slot.fboB)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER,
            GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D,
            slot.texB,
            0,
        )
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
    }

    private fun applyTextureParams() {
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
    }

    // --------------------------------------------------------------- computing

    private fun refreshIfStale(slot: Slot) {
        if (slot.builtVersion == settingsVersion) return
        buildEnhanced(slot)
    }

    private fun buildEnhanced(slot: Slot) {
        val cp = copyProgram
        if (cp == null) {
            slot.resultIsA = true
            slot.builtVersion = settingsVersion
            return
        }
        // Copy source into A. uUv is inverted so the copy does not flip the image.
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, slot.fboA)
        GLES31.glViewport(0, 0, slot.w, slot.h)
        GLES31.glDisable(GLES31.GL_BLEND)
        cp.use()
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, slot.srcTex)
        GLES31.glUniform1i(cp.u("uTex"), 0)
        GLES31.glUniform4f(cp.u("uRect"), -1f, -1f, 2f, 2f)
        GLES31.glUniform4f(cp.u("uUv"), 0f, 1f, 1f, 0f)
        bindQuad()
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)

        val e = enhance
        var isA = true

        if (e.denoise) {
            val prog = denoiseProgram(e.denoiseRadius)
            if (prog != null) {
                runCompute(prog, slot, isA) { p ->
                    GLES31.glUniform1f(p.u("uBlend"), e.denoiseBlendF)
                }
                isA = !isA
            }
        }
        if (e.sharpen) {
            sharpenProgram?.let { prog ->
                runCompute(prog, slot, isA) { p ->
                    GLES31.glUniform1f(p.u("uAmount"), e.sharpenF)
                    GLES31.glUniform1f(p.u("uGate"), 0.02f)
                }
                isA = !isA
            }
        }
        if (e.contrastPass) {
            contrastProgram?.let { prog ->
                runCompute(prog, slot, isA) { p ->
                    GLES31.glUniform1f(p.u("uBlend"), e.contrastBlendF)
                }
                isA = !isA
            }
        }

        slot.resultIsA = isA
        slot.builtVersion = settingsVersion
    }

    private fun runCompute(
        prog: GlProgram,
        slot: Slot,
        readsA: Boolean,
        setup: (GlProgram) -> Unit,
    ) {
        val src = if (readsA) slot.texA else slot.texB
        val dst = if (readsA) slot.texB else slot.texA
        prog.use()
        GLES31.glBindImageTexture(0, src, 0, false, 0, GLES31.GL_READ_ONLY, GLES31.GL_RGBA8)
        GLES31.glBindImageTexture(1, dst, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA8)
        setup(prog)
        GLES31.glDispatchCompute(
            (slot.w + WG - 1) / WG,
            (slot.h + WG - 1) / WG,
            1,
        )
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT,
        )
        GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_READ_ONLY, GLES31.GL_RGBA8)
        GLES31.glBindImageTexture(1, 0, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA8)
    }

    private fun denoiseProgram(radius: Int): GlProgram? = denoisePrograms.getOrPut(radius) {
        GlProgram.compute(Shaders.computeDenoise(radius))
    }

    // ------------------------------------------------------------------ helpers

    fun bumpSettings() {
        settingsVersion++
    }

    fun clearCache() {
        slots.values.forEach { destroy(it) }
        slots.clear()
        layoutRefs = null
        host.resetLoaded()
        host.postFrame()
    }

    private fun evictIfNeeded() {
        while (slots.size > MAX_SLOTS) {
            val keep = pageRefs.getOrNull(current)?.index
            val victim = slots.values
                .filter { it.index != keep }
                .minByOrNull { it.lastUsed }
                ?: return
            destroy(victim)
            slots.remove(victim.index)
        }
    }

    private fun destroy(slot: Slot) {
        val texs = intArrayOf(slot.srcTex, slot.texA, slot.texB)
        GLES31.glDeleteTextures(3, texs, 0)
        val fbos = intArrayOf(slot.fboA, slot.fboB)
        GLES31.glDeleteFramebuffers(2, fbos, 0)
    }

    fun hasSlot(index: Int): Boolean = slots.containsKey(index)
}

private class Slot(val index: Int, val w: Int, val h: Int) {
    var srcTex = 0
    var texA = 0
    var texB = 0
    var fboA = 0
    var fboB = 0
    var resultIsA = true
    var cropL = 0f
    var cropT = 0f
    var cropR = 1f
    var cropB = 1f
    var lastUsed = 0L
    var builtVersion = -1

    val resultTex: Int get() = if (resultIsA) texA else texB
}
