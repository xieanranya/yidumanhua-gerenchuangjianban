package com.enhance.comic.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.enhance.comic.data.Enhancer
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.PageRef
import com.enhance.comic.model.ReaderPrefs
import com.enhance.comic.model.ReadingMode
import com.enhance.comic.model.ScaleFilter
import com.enhance.comic.model.ScaleMode
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * The page surface.
 *
 * Everything is drawn with an ordinary [Canvas]: no EGL context, no FBOs, no shaders. A
 * surface that fails to initialise is indistinguishable from a book with no pages, so the
 * plainest rendering path is the one that gets used.
 *
 * Quality work happens once per page on a loader thread ([Enhancer]); drawing then costs a
 * single `drawBitmap` per visible page, which keeps scrolling and finger-following cheap.
 *
 * Gestures:
 *  - paged at 1x: the page follows the finger and snaps forward past 28% or on a fast flick.
 *  - paged zoomed: rubber-banded pan, pinch around the focal point, double tap 1x / 2.6x.
 *  - scroll modes: drag, fling with a small overscroll, pinch changes the layout width.
 */
class PageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val COMMIT_FRACTION = 0.28f
        private const val COMMIT_VELOCITY = 360f
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_TAP_ZOOM = 2.6f
        private const val SCROLL_ZOOM_MAX = 4f
        private const val MAX_CACHE = 6
    }

    var onPageChanged: ((Int) -> Unit)? = null
    /** 0 = 左侧, 1 = 中间, 2 = 右侧。动作由阅读界面按「页面按钮设置」决定。 */
    var onTapZone: ((Int) -> Unit)? = null

    private var provider: PageProvider? = null
    private var refs: List<PageRef> = emptyList()
    private var mode = ReadingMode.PAGED
    private var prefs = ReaderPrefs()
    private var enhance = EnhanceSettings()

    // ------------------------------------------------------------------- cache

    private class Entry(val bitmap: Bitmap, val crop: RectF)

    private val cache = object : LinkedHashMap<Int, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean =
            size > MAX_CACHE
    }
    private val loading = HashSet<Int>()
    private val failed = HashSet<Int>()
    /** Recreated on attach: a detached view leaves a shut down pool behind. */
    private var worker: java.util.concurrent.ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------- state

    private var current = 0
    private var scroll = 0f
    private var scrollRaw = 0f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private var dragging = false
    private var axisLocked = false
    private var axisX = true
    private var dragX = 0f
    private var dragY = 0f
    private var lastVelX = 0f
    private var lastVelY = 0f

    private var turnX = 0f
    private var turnY = 0f
    private var turnIndex = -1

    private var animator: ValueAnimator? = null
    private var lastReported = -1
    /** 上一次触发预取的页，避免 onDraw 每帧都去排队。 */
    private var prefetchedFor = -999
    private var lastPanX = 0f
    private var lastPanY = 0f

    private var layoutRefs: List<PageRef>? = null
    private var layoutW = 0
    private var layoutH = 0
    private var layoutZoom = -1f
    private var pageStarts = FloatArray(0)
    private var pageLengths = FloatArray(0)
    private var contentExtent = 0f

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 158)
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val bgColor = intArrayOf(0xFF0B0B0D.toInt(), 0xFF16161A.toInt(), 0xFFEDEDE6.toInt())

    /** 黑白 / 反色 / 蓝光：只影响绘制，不需要重新解码。 */
    private var colorFilter: ColorFilter? = null

    // ------------------------------------------------------------------- setup

    fun setProvider(value: PageProvider?) {
        // Compose calls this on every recomposition; only a different session is a reset.
        if (provider === value) return
        provider = value
        cache.clear()
        failed.clear()
        loading.clear()
        invalidate()
    }

    fun setPageRefs(value: List<PageRef>) {
        if (refs === value) return
        if (refs.size != value.size) resetTurn()
        refs = value
        layoutRefs = null
        invalidate()
    }

    fun applySettings(newPrefs: ReaderPrefs, newEnhance: EnhanceSettings) {
        val modeChanged = newPrefs.readingMode != mode
        val enhanceChanged = newEnhance != enhance
        val cropChanged = newPrefs.autoCropWhiteBorders != prefs.autoCropWhiteBorders
        val gapChanged = newPrefs.pageGapPercent != prefs.pageGapPercent
        val spreadChanged = newPrefs.doublePage != prefs.doublePage ||
            newPrefs.forceSpread != prefs.forceSpread
        prefs = newPrefs
        enhance = newEnhance
        mode = newPrefs.readingMode
        colorFilter = buildColorFilter(newPrefs)
        paint.colorFilter = colorFilter
        keepScreenOn = newPrefs.keepScreenOn
        if (gapChanged || spreadChanged) layoutRefs = null
        if (enhanceChanged || cropChanged) {
            // Pages are cached already enhanced, so they have to be decoded again.
            cache.clear()
            failed.clear()
            loading.clear()
        }
        if (modeChanged) {
            cancelAnimator()
            zoom = 1f
            panX = 0f
            panY = 0f
            resetTurn()
            layoutRefs = null
            scroll = 0f
            scrollRaw = 0f
        }
        invalidate()
    }

    /** Jumps to a display-page index. */
    fun seekTo(refIndex: Int) {
        if (refIndex < 0) return
        cancelAnimator()
        resetTurn()
        zoom = 1f
        panX = 0f
        panY = 0f
        current = refIndex
        if (mode == ReadingMode.PAGED) {
            scroll = 0f
        } else {
            ensureLayout()
            scroll = pageStarts.getOrElse(refIndex) { 0f }
        }
        scrollRaw = scroll
        lastReported = -1
        invalidate()
        prefetchAround(refIndex)
    }

    /** One screen forward / back: a page (two on a spread) or roughly a viewport of scroll. */
    fun stepPage(forward: Boolean) {
        if (refs.isEmpty()) return
        if (mode == ReadingMode.PAGED) {
            if (turnIndex >= 0) return
            val target = current + if (forward) spanAt(current) else -spanAt(current)
            if (target !in refs.indices) return
            turnIndex = target
            val sx = when {
                forward && prefs.rightToLeft -> 1f
                forward -> -1f
                prefs.rightToLeft -> -1f
                else -> 1f
            }
            turnX = 0f
            turnY = 0f
            animateTurn(sx, 0f, commit = true, velocity = 0f)
        } else {
            val extent = if (mode == ReadingMode.VERTICAL) height.toFloat() else width.toFloat()
            animateScrollTo((scroll + (if (forward) 1 else -1) * extent * 0.86f).coerceIn(0f, maxScroll()))
        }
    }

    fun currentRefIndex(): Int = current

    fun prefetchAround(refIndex: Int) {
        if (refs.isEmpty()) return
        prefetchedFor = refIndex
        for (i in listOf(refIndex - 1, refIndex, refIndex + 1, refIndex + 2, refIndex + 3)) {
            if (i in refs.indices) requestIfNeeded(i)
        }
    }

    /** [prefetchAround] 的绘制路径版本：同一页只排一次队。 */
    private fun prefetchForDraw(refIndex: Int) {
        if (prefetchedFor == refIndex) return
        prefetchAround(refIndex)
    }

    // ------------------------------------------------------------------ loading

    private fun targetWidth(): Int {
        val base = if (mode == ReadingMode.PAGED) width * max(1f, zoom) else width * zoom
        return base.toInt().coerceIn(720, 1600)
    }

    private fun requestIfNeeded(refIndex: Int) {
        val ref = refs.getOrNull(refIndex) ?: return
        val p = provider ?: return
        val key = ref.index
        synchronized(cache) {
            if (cache.containsKey(key) || key in loading || key in failed) return
            loading.add(key)
        }
        val widthPx = targetWidth()
        val settings = enhance
        val pool = worker ?: return
        try {
            pool.execute {
                load(ref, key, p, widthPx, settings)
            }
        } catch (_: Throwable) {
            synchronized(cache) { loading.remove(key) }
        }
    }

    private fun load(
        ref: PageRef,
        key: Int,
        p: PageProvider,
        widthPx: Int,
        settings: EnhanceSettings,
    ) {
        val decoded = try {
            p.decode(key, widthPx)
        } catch (_: Throwable) {
            null
        }
        if (decoded == null) {
            main.post {
                synchronized(cache) {
                    loading.remove(key)
                    failed.add(key)
                }
                invalidate()
            }
            return
        }
        // 内容框由 BookSession 在扫描阶段算好：布局用的也是同一个框，两边必须一致，
        // 否则页面会被拉伸。
        val cropRect = try {
            p.cropOf(key) ?: RectF(0f, 0f, 1f, 1f)
        } catch (_: Throwable) {
            RectF(0f, 0f, 1f, 1f)
        }
        val processed = try {
            Enhancer.process(decoded, settings)
        } catch (_: Throwable) {
            decoded
        }
        main.post {
            synchronized(cache) {
                loading.remove(key)
                cache[key] = Entry(processed, cropRect)
            }
            invalidate()
        }
    }

    private fun retryFailed() {
        if (failed.isEmpty()) return
        synchronized(cache) { failed.clear() }
        prefetchAround(current)
    }

    // ------------------------------------------------------------------- layout

    private fun aspectOf(refIndex: Int): Float {
        val ref = refs.getOrNull(refIndex) ?: return 1f / 1.42f
        // displayAspect：开启自动裁白边后是裁过的比例，和绘制用的 src 框一致。
        val src = provider?.displayAspect(ref.index) ?: (1f / 1.42f)
        val a = if (ref.isSplit) src * 0.5f else src
        return if (a.isNaN() || a < 0.05f) 1f / 1.42f else a.coerceIn(0.08f, 12f)
    }

    /** True when [idx] starts a natural two-page spread. */
    private fun spreadPartner(idx: Int): Int {
        if (!prefs.doublePage) return -1
        // 页面布局「双页」强制并排；「自动」只在横屏时并排。
        if (!prefs.forceSpread && width <= height) return -1
        val a = refs.getOrNull(idx) ?: return -1
        if (a.isSplit) return -1
        val b = refs.getOrNull(idx + 1) ?: return -1
        if (b.isSplit || b.index == a.index) return -1
        return idx + 1
    }

    private fun spanAt(idx: Int): Int = if (spreadPartner(idx) >= 0) 2 else 1

    private fun ensureLayout() {
        if (width == 0 || height == 0) return
        if (layoutRefs === refs && layoutW == width && layoutH == height && layoutZoom == zoom) return
        val n = refs.size
        pageStarts = FloatArray(n)
        pageLengths = FloatArray(n)
        val vertical = mode == ReadingMode.VERTICAL
        // 页面间距由用户设置（默认 0）：竖向是长条漫，页与页必须严丝合缝，
        // 任何默认间隙都会在深色画面上露出一条黑边。
        val pct = prefs.pageGapPercent.coerceIn(0, 30) / 100f
        val gap = if (pct <= 0f) {
            0f
        } else {
            (if (vertical) height else width).toFloat() * pct
        }
        var acc = 0f
        for (i in 0 until n) {
            val a = aspectOf(i)
            val len = if (vertical) (width * zoom) / a else (height * zoom) * a
            pageStarts[i] = acc
            pageLengths[i] = len.coerceAtLeast(1f)
            acc += pageLengths[i] + gap
        }
        contentExtent = acc
        layoutRefs = refs
        layoutW = width
        layoutH = height
        layoutZoom = zoom
    }

    private fun viewExtent(): Float =
        if (mode == ReadingMode.VERTICAL) height.toFloat() else width.toFloat()

    private fun maxScroll(): Float = max(0f, contentExtent - viewExtent())

    // ------------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor[prefs.background.coerceIn(0, 2)])
        if (refs.isEmpty() || width == 0 || height == 0) return
        if (mode == ReadingMode.PAGED) drawPaged(canvas) else drawScroll(canvas)
    }

    private fun buildColorFilter(p: ReaderPrefs): ColorFilter? {
        if (!p.grayscale && !p.invert && p.blueLight <= 0) return null
        val m = ColorMatrix()
        if (p.grayscale) m.setSaturation(0f)
        if (p.blueLight > 0) {
            // 蓝光过滤：只衰减蓝通道、绿通道略降，红保持满值。这是纯对角矩阵，黑色
            // (0,0,0) 仍然是黑 —— 黑底漫画不会发灰，和 iOS 那边用 multiply 叠加等价。
            val t = p.blueLight.coerceIn(0, 100) / 100f
            m.preConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f - 0.16f * t, 0f, 0f, 0f,
                        0f, 0f, 1f - 0.52f * t, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        if (p.invert) {
            m.preConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        return ColorMatrixColorFilter(m)
    }

    private fun drawPaged(canvas: Canvas) {
        val idx = current.coerceIn(0, refs.lastIndex)
        val partner = spreadPartner(idx)
        val a1 = aspectOf(idx)
        val a2 = if (partner >= 0) aspectOf(partner) else 0f
        val totalAspect = a1 + a2
        var fit = when (prefs.scaleMode) {
            ScaleMode.FIT -> min(width / totalAspect, height.toFloat())
            ScaleMode.WIDTH -> width / totalAspect
            ScaleMode.HEIGHT -> height.toFloat()
            // 原始尺寸：位图的一个像素对应屏幕的一个像素，靠拖动看完整页。
            ScaleMode.ORIGINAL -> synchronized(cache) {
                cache[refs[idx].index]?.bitmap?.height?.toFloat()
            } ?: height.toFloat()
        } * zoom
        if (!fit.isFinite() || fit <= 0f) fit = height.toFloat()

        val unitWidth = totalAspect * fit
        val maxPanX = ((unitWidth - width) / 2f).coerceAtLeast(0f)
        val maxPanY = ((fit - height) / 2f).coerceAtLeast(0f)
        lastPanX = maxPanX
        lastPanY = maxPanY

        val cx = width / 2f + panX.coerceIn(-maxPanX, maxPanX)
        val cy = height / 2f + panY.coerceIn(-maxPanY, maxPanY)
        val tx = turnX * width
        val ty = turnY * height
        val moving = abs(tx) > 0.5f || abs(ty) > 0.5f

        if (moving && turnIndex in refs.indices) {
            val horizontal = abs(tx) >= abs(ty)
            val icx = if (horizontal) cx + tx - sign(tx) * width else cx + tx
            val icy = if (horizontal) cy + ty else cy + ty - sign(ty) * height
            drawUnit(canvas, turnIndex, icx, icy, fit)
        }
        drawUnit(canvas, idx, cx + tx, cy + ty, fit)
        prefetchForDraw(idx)
    }

    private fun drawUnit(canvas: Canvas, start: Int, cx: Float, cy: Float, fit: Float) {
        val partner = spreadPartner(start)
        if (partner >= 0) {
            // Right-to-left manga puts the earlier page on the right; the two pages meet
            // exactly at the centre line.
            val leftRef = if (prefs.rightToLeft) partner else start
            val rightRef = if (prefs.rightToLeft) start else partner
            val leftW = aspectOf(leftRef) * fit
            val rightW = aspectOf(rightRef) * fit
            drawPage(canvas, leftRef, cx - leftW / 2f, cy, fit)
            drawPage(canvas, rightRef, cx + rightW / 2f, cy, fit)
        } else {
            drawPage(canvas, start, cx, cy, fit)
        }
    }

    private fun drawPage(canvas: Canvas, refIndex: Int, cx: Float, cy: Float, fit: Float) {
        val ref = refs.getOrNull(refIndex) ?: return
        val entry = synchronized(cache) { cache[ref.index] }
        if (entry == null) {
            requestIfNeeded(refIndex)
            if (refIndex == current) drawPlaceholder(canvas, ref)
            return
        }
        val bitmap = entry.bitmap
        if (bitmap.isRecycled) return
        srcOf(ref, bitmap, entry.crop, srcRect)
        val sw = (srcRect.right - srcRect.left).coerceAtLeast(1)
        val sh = (srcRect.bottom - srcRect.top).coerceAtLeast(1)
        val hPx = fit
        val wPx = fit * sw / sh
        dstRect.set(cx - wPx / 2f, cy - hPx / 2f, cx + wPx / 2f, cy + hPx / 2f)
        paint.isFilterBitmap = enhance.scaleFilter != ScaleFilter.NEAREST
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    private fun drawScroll(canvas: Canvas) {
        ensureLayout()
        if (pageStarts.isEmpty()) return
        val vertical = mode == ReadingMode.VERTICAL
        val extent = viewExtent()
        val start = scroll

        for (i in refs.indices) {
            val pageStart = pageStarts[i]
            val length = pageLengths[i]
            if (pageStart + length < start) continue
            if (pageStart > start + extent) break

            val ref = refs[i]
            val entry = synchronized(cache) { cache[ref.index] }
            if (entry == null) {
                requestIfNeeded(i)
                drawCellPlaceholder(canvas, ref, pageStart - start, length, vertical)
                continue
            }
            val bitmap = entry.bitmap
            if (bitmap.isRecycled) continue
            srcOf(ref, bitmap, entry.crop, srcRect)
            if (vertical) {
                val contentW = width * zoom
                val left = (width - contentW) / 2f
                val top = pageStart - start
                dstRect.set(left, top, left + contentW, top + length)
            } else {
                val contentH = height * zoom
                val top = (height - contentH) / 2f
                val left = pageStart - start
                dstRect.set(left, top, left + length, top + contentH)
            }
            paint.isFilterBitmap = enhance.scaleFilter != ScaleFilter.NEAREST
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
        prefetchForDraw(current)
    }

    /** Fills [out] with the source pixels this display page shows (crop + optional half). */
    private fun srcOf(ref: PageRef, bitmap: Bitmap, crop: RectF, out: Rect) {
        var l = (crop.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 2)
        var t = (crop.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 2)
        var r = (crop.right * bitmap.width).toInt().coerceIn(l + 1, bitmap.width)
        var b = (crop.bottom * bitmap.height).toInt().coerceIn(t + 1, bitmap.height)
        if (ref.isSplit) {
            val mid = (l + r) / 2
            val takeRight = if (prefs.rightToLeft) ref.half == 1 else ref.half == 2
            if (takeRight) l = mid else r = mid
        }
        out.set(l, t, r, b)
    }

    private fun drawPlaceholder(canvas: Canvas, ref: PageRef) {
        val label = if (ref.index in failed) "第 ${ref.index + 1} 页无法解码" else "正在载入第 ${ref.index + 1} 页…"
        canvas.drawText(label, width / 2f, height / 2f, textPaint)
    }

    /** Same hint as [drawPlaceholder], positioned inside a scroll cell. */
    private fun drawCellPlaceholder(
        canvas: Canvas,
        ref: PageRef,
        offset: Float,
        length: Float,
        vertical: Boolean,
    ) {
        val label = if (ref.index in failed) "第 ${ref.index + 1} 页无法解码" else "正在载入第 ${ref.index + 1} 页…"
        if (vertical) {
            canvas.drawText(label, width / 2f, offset + min(length, height.toFloat()) / 2f, textPaint)
        } else {
            canvas.drawText(label, offset + min(length, width.toFloat()) / 2f, height / 2f, textPaint)
        }
    }

    // -------------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelAnimator()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val maxZoom = if (mode == ReadingMode.PAGED) MAX_ZOOM else SCROLL_ZOOM_MAX
                val next = (zoom * detector.scaleFactor).coerceIn(1f, maxZoom)
                val k = next / zoom
                if (abs(k - 1f) < 0.0005f) return true
                if (mode == ReadingMode.PAGED) {
                    panX += (detector.focusX - width / 2f - panX) * (1f - k)
                    panY += (detector.focusY - height / 2f - panY) * (1f - k)
                } else {
                    val f = if (mode == ReadingMode.VERTICAL) detector.focusY else detector.focusX
                    val anchor = (scroll + f) / zoom
                    zoom = next
                    layoutRefs = null
                    ensureLayout()
                    scroll = (anchor * zoom - f).coerceIn(0f, maxScroll())
                    scrollRaw = scroll
                }
                zoom = next
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (mode == ReadingMode.PAGED) springBackPan()
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = width.coerceAtLeast(1)
                val zone = when {
                    e.x < w * 0.26f -> 0
                    e.x > w * 0.74f -> 2
                    else -> 1
                }
                onTapZone?.invoke(zone)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!prefs.doubleTapZoom) return true
                if (mode == ReadingMode.PAGED) {
                    if (zoom > 1.05f) animateZoomTo(1f, width / 2f, height / 2f)
                    else animateZoomTo(DOUBLE_TAP_ZOOM, e.x, e.y)
                } else {
                    val target = if (zoom > 1.05f) 1f else 2.2f
                    animateScrollZoomTo(target, e.x, e.y)
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!dragging || scaleDetector.isInProgress) return true
                dragX -= distanceX
                dragY -= distanceY
                if (mode == ReadingMode.PAGED) {
                    if (canPan()) applyPan(-distanceX, -distanceY) else dragTurn()
                } else {
                    val delta = if (mode == ReadingMode.VERTICAL) distanceY else distanceX
                    scrollRaw += delta
                    scroll = rubberScroll(scrollRaw)
                    invalidate()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                lastVelX = velocityX
                lastVelY = velocityY
                when {
                    mode != ReadingMode.PAGED -> startScrollFling(velocityX, velocityY)
                    canPan() -> startPanFling(velocityX, velocityY)
                    // Paged at 1x: the release handler commits or rewinds the drag.
                }
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimator()
                scroller.forceFinished(true)
                dragging = true
                axisLocked = false
                dragX = 0f
                dragY = 0f
                lastVelX = 0f
                lastVelY = 0f
                scrollRaw = scroll
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (turnIndex >= 0 || turnX != 0f || turnY != 0f) resetTurn()
                axisLocked = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onTouchReleased()
            }
        }
        return true
    }

    private fun onTouchReleased() {
        if (mode == ReadingMode.PAGED && !canPan()) {
            releaseTurn()
        } else if (mode == ReadingMode.PAGED) {
            springBackPan()
        } else {
            val maxS = maxScroll()
            if (scroll < 0f || scroll > maxS) animateScrollTo(scroll.coerceIn(0f, maxS))
        }
        reportPage()
    }

    // ---------------------------------------------------------------- page turn

    private fun dragTurn() {
        if (!axisLocked) {
            if (abs(dragX) < touchSlop && abs(dragY) < touchSlop) return
            axisX = abs(dragX) >= abs(dragY)
            axisLocked = true
        }
        val extent = if (axisX) width.coerceAtLeast(1).toFloat() else height.coerceAtLeast(1).toFloat()
        val d = if (axisX) dragX else dragY
        if (abs(d) < 1f) return

        turnIndex = resolveTurnTarget(d)
        val raw = d / extent
        val frac = if (turnIndex >= 0) raw.coerceIn(-1f, 1f) else (raw * 0.35f).coerceIn(-0.16f, 0.16f)
        if (axisX) {
            turnX = frac
            turnY = 0f
        } else {
            turnX = 0f
            turnY = frac
        }
        invalidate()
    }

    private fun resolveTurnTarget(d: Float): Int {
        val forward = if (axisX) {
            if (prefs.rightToLeft) d > 0f else d < 0f
        } else {
            d < 0f
        }
        val span = spanAt(current)
        val target = current + if (forward) span else -span
        return if (target in refs.indices) target else -1
    }

    private fun releaseTurn() {
        if (abs(turnX) < 0.002f && abs(turnY) < 0.002f) {
            if (turnIndex >= 0) {
                turnIndex = -1
                invalidate()
            }
            return
        }
        val frac = if (axisX) turnX else turnY
        val vel = if (axisX) lastVelX else lastVelY
        val sameDir = abs(vel) < 120f || (vel > 0f) == (frac > 0f)
        val commit = turnIndex >= 0 &&
            (abs(frac) > COMMIT_FRACTION || (sameDir && abs(vel) > COMMIT_VELOCITY))
        val sx = if (axisX) sign(frac) else 0f
        val sy = if (axisX) 0f else sign(frac)
        animateTurn(sx, sy, commit, vel)
    }

    private fun animateTurn(toX: Float, toY: Float, commit: Boolean, velocity: Float) {
        val target = turnIndex
        cancelAnimator()
        val fromX = turnX
        val fromY = turnY
        if (abs(toX - fromX) < 0.0005f && abs(toY - fromY) < 0.0005f) {
            finishTurn(commit, target)
            return
        }
        val remaining = max(abs(toX - fromX), abs(toY - fromY))
        var duration = (if (commit) 210L else 180L) - (remaining * 55f).toLong()
        if (abs(velocity) > 1600f) duration = (duration * 0.72f).toLong()
        duration = duration.coerceIn(90L, 260L)

        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = duration
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            turnX = fromX + (toX - fromX) * t
            turnY = fromY + (toY - fromY) * t
            invalidate()
        }
        a.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    finishTurn(commit, target)
                }
            },
        )
        animator = a
        a.start()
    }

    private fun finishTurn(commit: Boolean, target: Int) {
        turnX = 0f
        turnY = 0f
        turnIndex = -1
        if (commit && target in refs.indices) {
            current = target
            lastReported = -1
            prefetchAround(target)
            onPageChanged?.invoke(target)
        }
        invalidate()
    }

    private fun resetTurn() {
        turnX = 0f
        turnY = 0f
        turnIndex = -1
    }

    // ------------------------------------------------------------------ drag vs pan

    /**
     * True when the page is bigger than the viewport, so a drag has to pan instead of
     * turning the page. Zoom alone is not enough: 适应宽度 / 适应高度 / 原始尺寸 overflow
     * at zoom 1 as well, and those pages were undraggable when this only checked [zoom].
     */
    private fun canPan(): Boolean = zoom > 1.02f || lastPanX > 1f || lastPanY > 1f

    // ------------------------------------------------------------------ zooming

    private fun rubber(value: Float, bound: Float): Float {
        if (bound <= 0.5f) return value * 0.35f
        return when {
            value > bound -> bound + (value - bound) * 0.35f
            value < -bound -> -bound + (value + bound) * 0.35f
            else -> value
        }
    }

    private fun applyPan(dx: Float, dy: Float) {
        panX = rubber(panX + dx, lastPanX)
        panY = rubber(panY + dy, lastPanY)
        invalidate()
    }

    private fun springBackPan() {
        val tx = panX.coerceIn(-lastPanX, lastPanX)
        val ty = panY.coerceIn(-lastPanY, lastPanY)
        if (abs(tx - panX) < 0.5f && abs(ty - panY) < 0.5f) {
            panX = tx
            panY = ty
            return
        }
        animateValues(panX, panY, tx, ty, 200L) { x, y ->
            panX = x
            panY = y
            invalidate()
        }
    }

    private fun animateZoomTo(z1: Float, fx: Float, fy: Float) {
        val z0 = zoom
        if (abs(z1 - z0) < 0.01f) return
        val cx = width / 2f
        val cy = height / 2f
        val startX = panX
        val startY = panY
        val wx = (fx - cx - startX) / z0
        val wy = (fy - cy - startY) / z0
        val ratio = z1 / z0
        val mx = max(0f, lastPanX * ratio)
        val my = max(0f, lastPanY * ratio)
        // 缩回 1 倍时，如果这一页本来就比屏幕大（适应宽度等），要保留当前位置，
        // 否则双击缩小会把画面弹回左上角。
        val stillOverflow = lastPanX > 1f || lastPanY > 1f
        val endX = if (z1 <= 1.001f) {
            if (stillOverflow) panX.coerceIn(-max(0f, lastPanX), max(0f, lastPanX)) else 0f
        } else {
            (fx - cx - wx * z1).coerceIn(-mx, mx)
        }
        val endY = if (z1 <= 1.001f) {
            if (stillOverflow) panY.coerceIn(-max(0f, lastPanY), max(0f, lastPanY)) else 0f
        } else {
            (fy - cy - wy * z1).coerceIn(-my, my)
        }
        animateValues(z0, 0f, z1, 0f, 240L, DecelerateInterpolator(1.8f)) { z, _ ->
            val progress = if (abs(z1 - z0) < 0.0001f) 1f else (z - z0) / (z1 - z0)
            zoom = z
            panX = startX + (endX - startX) * progress
            panY = startY + (endY - startY) * progress
            invalidate()
        }
    }

    private fun animateScrollZoomTo(z1: Float, fx: Float, fy: Float) {
        val z0 = zoom
        if (abs(z1 - z0) < 0.01f) return
        val f = if (mode == ReadingMode.VERTICAL) fy else fx
        val anchor = (scroll + f) / z0
        animateValues(z0, 0f, z1, 0f, 240L, DecelerateInterpolator(1.8f)) { z, _ ->
            zoom = z
            layoutRefs = null
            ensureLayout()
            scroll = (anchor * z - f).coerceIn(0f, maxScroll())
            scrollRaw = scroll
            invalidate()
        }
    }

    private fun animateValues(
        from1: Float,
        from2: Float,
        to1: Float,
        to2: Float,
        duration: Long,
        interpolator: TimeInterpolator = DecelerateInterpolator(1.8f),
        onFrame: (Float, Float) -> Unit,
    ) {
        cancelAnimator()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = duration
        a.interpolator = interpolator
        a.addUpdateListener {
            val t = it.animatedValue as Float
            onFrame(from1 + (to1 - from1) * t, from2 + (to2 - from2) * t)
        }
        a.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            },
        )
        animator = a
        a.start()
    }

    private fun cancelAnimator() {
        animator?.cancel()
        animator = null
    }

    // ---------------------------------------------------------------- scrolling

    private fun rubberScroll(value: Float): Float {
        val maxS = maxScroll()
        val over = viewExtent() * 0.18f
        return when {
            value < 0f -> max(0f - over, value * 0.35f)
            value > maxS -> min(maxS + over, maxS + (value - maxS) * 0.35f)
            else -> value
        }
    }

    private fun animateScrollTo(target: Float) {
        cancelAnimator()
        val from = scroll
        if (abs(target - from) < 1f) return
        val distance = abs(target - from)
        val duration = (180f + distance / 4f).toLong().coerceIn(180L, 420L)
        animateValues(from, 0f, target, 0f, duration, DecelerateInterpolator(1.7f)) { v, _ ->
            scroll = v
            invalidate()
        }
        scrollRaw = target
    }

    private fun startScrollFling(velocityX: Float, velocityY: Float) {
        val vertical = mode == ReadingMode.VERTICAL
        val maxS = maxScroll().toInt()
        val from = scroll.toInt()
        val over = (viewExtent() * 0.18f).toInt()
        if (vertical) {
            scroller.fling(0, from, 0, -velocityY.toInt(), 0, 0, -over, maxS + over, 0, over)
        } else {
            scroller.fling(from, 0, -velocityX.toInt(), 0, -over, maxS + over, 0, 0, over, 0)
        }
        postOnAnimation(flingRunner)
    }

    private val flingRunner = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) {
                val maxS = maxScroll()
                if (scroll < 0f || scroll > maxS) animateScrollTo(scroll.coerceIn(0f, maxS))
                reportPage()
                return
            }
            scroll = (if (mode == ReadingMode.VERTICAL) scroller.currY else scroller.currX).toFloat()
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun startPanFling(velocityX: Float, velocityY: Float) {
        val mx = lastPanX.toInt()
        val my = lastPanY.toInt()
        if (mx <= 0 && my <= 0) return
        scroller.fling(panX.toInt(), panY.toInt(), velocityX.toInt(), velocityY.toInt(), -mx, mx, -my, my)
        postOnAnimation(panFlingRunner)
    }

    private val panFlingRunner = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) {
                springBackPan()
                return
            }
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            invalidate()
            postOnAnimation(this)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun reportPage() {
        if (refs.isEmpty()) return
        val idx = if (mode == ReadingMode.PAGED) {
            current
        } else {
            ensureLayout()
            var found = 0
            for (i in pageStarts.indices) {
                if (pageStarts[i] <= scroll + viewExtent() * 0.35f) found = i else break
            }
            found
        }
        if (idx != lastReported && idx in refs.indices) {
            lastReported = idx
            if (idx != current) current = idx
            onPageChanged?.invoke(idx)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (worker == null || worker?.isShutdown == true) {
            // 两个线程：一页还在解码时，下一屏的预取可以同时进行。ZipFile 与
            // ContentResolver 都是线程安全的，RAR / PDF 在 PageSource 里各自加了锁。
            worker = Executors.newFixedThreadPool(2) { r ->
                Thread(r, "page-decode").apply { isDaemon = true }
            }
        }
        prefetchAround(current)
    }

    override fun onDetachedFromWindow() {
        cancelAnimator()
        worker?.shutdownNow()
        worker = null
        cache.clear()
        synchronized(cache) { loading.clear() }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutRefs = null
        if (mode != ReadingMode.PAGED) {
            ensureLayout()
            scroll = pageStarts.getOrElse(current) { 0f }
            scrollRaw = scroll
        }
        invalidate()
    }
}
