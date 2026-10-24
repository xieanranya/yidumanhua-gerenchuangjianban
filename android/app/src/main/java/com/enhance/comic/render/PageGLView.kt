package com.enhance.comic.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.PageRef
import com.enhance.comic.model.ReaderPrefs
import com.enhance.comic.model.ReadingMode
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * GPU page view.
 *
 * Gestures:
 *  - paged, 1x zoom: the page follows the finger horizontally or vertically and snaps to the
 *    next / previous page once the drag passes a distance or velocity threshold, otherwise it
 *    springs back.
 *  - paged, zoomed: one finger pans with a rubber band at the edges, two fingers pinch around
 *    the focal point, double tap toggles 1x / 2.6x anchored at the tap.
 *  - vertical / horizontal scroll: finger drag, velocity fling with a small overscroll bounce,
 *    pinch changes how wide pages are laid out.
 */
class PageGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val COMMIT_FRACTION = 0.28f
        private const val COMMIT_VELOCITY = 360f
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_TAP_ZOOM = 2.6f
        private const val SCROLL_ZOOM_MAX = 4f
    }

    var onPageChanged: ((Int) -> Unit)? = null
    var onPageStep: ((forward: Boolean) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    var onGlUnsupported: (() -> Unit)? = null

    private val renderer = EnhanceRenderer(this)
    private val loader = Executors.newSingleThreadExecutor()
    private val inFlight = Collections.synchronizedSet(HashSet<Int>())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var provider: PageProvider? = null
    private var refs: List<PageRef> = emptyList()
    private var mode = ReadingMode.PAGED
    private var prefs = ReaderPrefs()
    private var enhance = EnhanceSettings()

    @Volatile private var scroll = 0f
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

    private var glReady = false
    private var lastReported = -1

    private val scroller = OverScroller(context)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ------------------------------------------------------------------- setup

    fun setProvider(provider: PageProvider?) {
        this.provider = provider
        renderer.provider = provider
    }

    fun setPageRefs(newRefs: List<PageRef>) {
        if (refs.size != newRefs.size) {
            turnX = 0f
            turnY = 0f
            turnIndex = -1
        }
        refs = newRefs
        renderer.pageRefs = newRefs
        pushTurn()
    }

    fun applySettings(newPrefs: ReaderPrefs, newEnhance: EnhanceSettings) {
        val cropChanged = newPrefs.autoCropWhiteBorders != prefs.autoCropWhiteBorders
        val enhanceChanged = newEnhance != enhance
        val modeChanged = newPrefs.readingMode != mode
        val rtlChanged = newPrefs.rightToLeft != prefs.rightToLeft
        val spreadChanged = newPrefs.doublePage != prefs.doublePage

        prefs = newPrefs
        enhance = newEnhance
        renderer.prefs = newPrefs
        renderer.enhance = newEnhance

        if (enhanceChanged && !cropChanged) renderer.bumpSettings()
        if (cropChanged) queueEvent { renderer.clearCache() }
        if (modeChanged) {
            mode = newPrefs.readingMode
            renderer.mode = newPrefs.readingMode
            cancelAnimator()
            zoom = 1f
            panX = 0f
            panY = 0f
            turnX = 0f
            turnY = 0f
            turnIndex = -1
            renderer.zoom = 1f
            renderer.panX = 0f
            renderer.panY = 0f
            renderer.layoutZoom = 1f
            queueEvent { renderer.clearCache() }
        }
        if (rtlChanged || modeChanged || spreadChanged) queueEvent { renderer.bumpSettings() }
        requestRender()
    }

    /** Jumps to a display-page index (index into the ref list). */
    fun seekTo(refIndex: Int) {
        if (refIndex < 0) return
        cancelAnimator()
        zoom = 1f
        panX = 0f
        panY = 0f
        turnX = 0f
        turnY = 0f
        turnIndex = -1
        renderer.zoom = 1f
        renderer.panX = 0f
        renderer.panY = 0f
        renderer.layoutZoom = 1f
        queueEvent {
            val target = if (renderer.mode == ReadingMode.PAGED) {
                0f
            } else {
                renderer.seekScrollTo(refIndex, width, height)
            }
            renderer.current = refIndex
            renderer.scroll = target
            requestRender()
            post {
                scroll = target
                scrollRaw = target
            }
        }
        pushTurn()
        postDelayed({ reportProgress() }, 120)
    }

    /**
     * Turns one screen: a page in paged mode (two when a spread is shown), a viewport in
     * scroll modes. Used by volume keys, the bottom bar and edge taps.
     */
    fun stepPage(forward: Boolean) {
        if (mode == ReadingMode.PAGED) {
            if (turnIndex >= 0) return
            val span = renderer.stepSpan.coerceAtLeast(1)
            val target = renderer.current + if (forward) span else -span
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
            pushTurn()
            requestRender()
            animateTurn(sx, 0f, commit = true, velocity = 0f)
        } else {
            val extent = if (mode == ReadingMode.VERTICAL) height.toFloat() else width.toFloat()
            val target = (scroll + (if (forward) 1 else -1) * extent * 0.86f)
                .coerceIn(0f, maxScroll())
            animateScrollTo(target)
        }
    }

    fun currentRefIndex(): Int = if (mode == ReadingMode.PAGED) renderer.current else lastReported

    // ---------------------------------------------------------------- loading

    /** Called on the GL thread. */
    fun requestLoad(index: Int, targetWidth: Int) {
        val p = provider ?: return
        if (!inFlight.add(index)) return
        loader.execute {
            val bitmap = try {
                p.decode(index, targetWidth)
            } catch (_: Throwable) {
                null
            }
            if (bitmap == null) {
                inFlight.remove(index)
                return@execute
            }
            // 内容框来自 BookSession 的扫描结果，和布局用的比例是同一个。
            val box = try {
                p.cropOf(index)
            } catch (_: Throwable) {
                null
            }
            val cropArray = if (box != null) {
                floatArrayOf(box.left, box.top, box.right, box.bottom)
            } else {
                floatArrayOf(0f, 0f, 1f, 1f)
            }
            queueEvent {
                inFlight.remove(index)
                renderer.uploadPage(index, bitmap, cropArray)
            }
        }
    }

    fun resetLoaded() {
        inFlight.clear()
    }

    fun postFrame() {
        requestRender()
    }

    /** Called on the GL thread once the surface exists. */
    fun onGlReady(ok: Boolean) {
        glReady = ok
        post {
            if (!ok) onGlUnsupported?.invoke()
            requestRender()
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimator()
        loader.shutdownNow()
        super.onDetachedFromWindow()
    }

    // ----------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelAnimator()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = if (mode == ReadingMode.PAGED) {
                    (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                } else {
                    (zoom * detector.scaleFactor).coerceIn(1f, SCROLL_ZOOM_MAX)
                }
                val k = next / zoom
                if (abs(k - 1f) < 0.0005f) return true
                if (mode == ReadingMode.PAGED) {
                    // Keep the content under the fingertips pinned while scaling.
                    panX += (detector.focusX - width / 2f - panX) * (1f - k)
                    panY += (detector.focusY - height / 2f - panY) * (1f - k)
                    zoom = next
                    renderer.zoom = zoom
                    renderer.panX = panX
                    renderer.panY = panY
                } else {
                    val f = if (mode == ReadingMode.VERTICAL) detector.focusY else detector.focusX
                    val anchor = (scroll + f) / zoom
                    zoom = next
                    renderer.layoutZoom = zoom
                    applyScroll(anchor * zoom - f)
                    scrollRaw = scroll
                }
                requestRender()
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
                when {
                    e.x < w * 0.25f -> stepPage(false)
                    e.x > w * 0.75f -> stepPage(true)
                    else -> onCenterTap?.invoke()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode == ReadingMode.PAGED) {
                    if (zoom > 1.05f) animateZoomTo(1f, width / 2f, height / 2f)
                    else animateZoomTo(DOUBLE_TAP_ZOOM, e.x, e.y)
                } else {
                    if (zoom > 1.05f) animateScrollZoomTo(1f, width / 2f, height / 2f)
                    else animateScrollZoomTo(2.2f, e.x, e.y)
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
                when (mode) {
                    ReadingMode.PAGED -> {
                        if (zoom > 1.02f) applyPan(-distanceX, -distanceY) else dragTurn()
                    }
                    else -> {
                        val delta = if (mode == ReadingMode.VERTICAL) distanceY else distanceX
                        scrollRaw += delta
                        applyScroll(rubberScroll(scrollRaw))
                    }
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
                    zoom > 1.02f -> startPanFling(velocityX, velocityY)
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
                if (turnIndex >= 0 || turnX != 0f || turnY != 0f) {
                    turnX = 0f
                    turnY = 0f
                    turnIndex = -1
                    pushTurn()
                }
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
        if (mode == ReadingMode.PAGED && zoom <= 1.02f) {
            releaseTurn()
        } else if (mode == ReadingMode.PAGED) {
            springBackPan()
        } else {
            val maxS = maxScroll()
            if (scroll < 0f || scroll > maxS) animateScrollTo(scroll.coerceIn(0f, maxS))
        }
        reportProgress()
    }

    // ------------------------------------------------------------- page turn

    private fun dragTurn() {
        if (!axisLocked) {
            if (abs(dragX) < touchSlop && abs(dragY) < touchSlop) return
            axisX = abs(dragX) >= abs(dragY)
            axisLocked = true
        }
        val extent = if (axisX) width.coerceAtLeast(1).toFloat() else height.coerceAtLeast(1).toFloat()
        val d = if (axisX) dragX else dragY
        if (abs(d) < 1f) return

        val target = resolveTurnTarget(d)
        turnIndex = target
        val raw = d / extent
        val frac = if (target >= 0) raw.coerceIn(-1f, 1f) else (raw * 0.35f).coerceIn(-0.16f, 0.16f)
        if (axisX) {
            turnX = frac
            turnY = 0f
        } else {
            turnX = 0f
            turnY = frac
        }
        pushTurn()
    }

    private fun resolveTurnTarget(d: Float): Int {
        val forward = if (axisX) {
            if (prefs.rightToLeft) d > 0f else d < 0f
        } else {
            d < 0f
        }
        val span = renderer.stepSpan.coerceAtLeast(1)
        val target = renderer.current + if (forward) span else -span
        return if (target in refs.indices) target else -1
    }

    private fun releaseTurn() {
        if (abs(turnX) < 0.002f && abs(turnY) < 0.002f) {
            if (turnIndex >= 0) {
                turnIndex = -1
                pushTurn()
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
            pushTurn()
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
            renderer.current = target
            lastReported = -1
            pushTurn()
            requestRender()
            post {
                onPageChanged?.invoke(target)
                prefetchAround(target)
            }
        } else {
            pushTurn()
        }
    }

    private fun pushTurn() {
        renderer.turnX = turnX
        renderer.turnY = turnY
        renderer.turnIndex = turnIndex
        requestRender()
    }

    // ---------------------------------------------------------------- zooming

    private fun rubber(value: Float, bound: Float): Float {
        if (bound <= 0.5f) return value * 0.35f
        return when {
            value > bound -> bound + (value - bound) * 0.35f
            value < -bound -> -bound + (value + bound) * 0.35f
            else -> value
        }
    }

    private fun applyPan(dx: Float, dy: Float) {
        panX = rubber(panX + dx, renderer.maxPanX)
        panY = rubber(panY + dy, renderer.maxPanY)
        renderer.panX = panX
        renderer.panY = panY
        requestRender()
    }

    private fun springBackPan() {
        val mx = renderer.maxPanX
        val my = renderer.maxPanY
        val tx = panX.coerceIn(-mx, mx)
        val ty = panY.coerceIn(-my, my)
        if (abs(tx - panX) < 0.5f && abs(ty - panY) < 0.5f) {
            panX = tx
            panY = ty
            renderer.panX = tx
            renderer.panY = ty
            return
        }
        animateValues(panX, panY, tx, ty, 200L) { x, y ->
            panX = x
            panY = y
            renderer.panX = x
            renderer.panY = y
            requestRender()
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
        val mx = max(0f, renderer.maxPanX * ratio)
        val my = max(0f, renderer.maxPanY * ratio)
        val endX = if (z1 <= 1.001f) 0f else (fx - cx - wx * z1).coerceIn(-mx, mx)
        val endY = if (z1 <= 1.001f) 0f else (fy - cy - wy * z1).coerceIn(-my, my)
        animateValues(z0, 0f, z1, 0f, 240L, DecelerateInterpolator(1.8f), onFrame = { z, _ ->
            val progress = if (abs(z1 - z0) < 0.0001f) 1f else (z - z0) / (z1 - z0)
            zoom = z
            panX = startX + (endX - startX) * progress
            panY = startY + (endY - startY) * progress
            renderer.zoom = zoom
            renderer.panX = panX
            renderer.panY = panY
            requestRender()
        })
    }

    private fun animateScrollZoomTo(z1: Float, fx: Float, fy: Float) {
        val z0 = zoom
        if (abs(z1 - z0) < 0.01f) return
        val f = if (mode == ReadingMode.VERTICAL) fy else fx
        val anchor = (scroll + f) / z0
        animateValues(z0, 0f, z1, 0f, 240L, DecelerateInterpolator(1.8f), onFrame = { z, _ ->
            zoom = z
            renderer.layoutZoom = z
            applyScroll(max(0f, anchor * z - f))
            scrollRaw = scroll
        })
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

    // --------------------------------------------------------------- scrolling

    private fun viewExtent(): Float =
        if (mode == ReadingMode.VERTICAL) height.toFloat() else width.toFloat()

    private fun maxScroll(): Float = max(0f, renderer.contentExtent - viewExtent())

    /** Rubber band past either end: the content keeps following but with resistance. */
    private fun rubberScroll(value: Float): Float {
        val maxS = maxScroll()
        val over = viewExtent() * 0.18f
        return when {
            value < 0f -> max(0f - over, value * 0.35f)
            value > maxS -> min(maxS + over, maxS + (value - maxS) * 0.35f)
            else -> value
        }
    }

    private fun applyScroll(value: Float) {
        scroll = value
        renderer.scroll = value
        requestRender()
    }

    private fun animateScrollTo(target: Float) {
        cancelAnimator()
        val from = scroll
        if (abs(target - from) < 1f) return
        val distance = abs(target - from)
        val duration = (180f + distance / 4f).toLong().coerceIn(180L, 420L)
        animateValues(from, 0f, target, 0f, duration, DecelerateInterpolator(1.7f), onFrame = { v, _ ->
            applyScroll(v)
        })
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
                reportProgress()
                return
            }
            val vertical = mode == ReadingMode.VERTICAL
            applyScroll(if (vertical) scroller.currY.toFloat() else scroller.currX.toFloat())
            postOnAnimation(this)
        }
    }

    private fun startPanFling(velocityX: Float, velocityY: Float) {
        val mx = renderer.maxPanX.toInt()
        val my = renderer.maxPanY.toInt()
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
            renderer.panX = panX
            renderer.panY = panY
            requestRender()
            postOnAnimation(this)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun reportProgress() {
        if (refs.isEmpty() || !glReady) return
        queueEvent {
            val idx = if (renderer.mode == ReadingMode.PAGED) {
                renderer.current
            } else {
                renderer.pageAtScroll(renderer.scroll, width, height)
            }
            if (idx != lastReported) {
                lastReported = idx
                post { onPageChanged?.invoke(idx) }
            }
        }
    }

    /** Prefetches the neighbours of [refIndex] so page turns feel instant. */
    fun prefetchAround(refIndex: Int) {
        if (refs.isEmpty()) return
        val targets = listOf(refIndex - 1, refIndex, refIndex + 1, refIndex + 2)
        for (i in targets) {
            val ref = refs.getOrNull(i) ?: continue
            requestLoad(ref.index, width.coerceIn(720, 2400))
        }
    }
}
