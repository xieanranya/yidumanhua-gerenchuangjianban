package com.enhance.comic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.enhance.comic.render.PageProvider
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * CPU fallback used when the device cannot create a GLES 3.1 context.
 * Simple fit-to-screen paged reading: no enhancement, no zoom.
 */
class SimplePageView(context: Context) : View(context) {

    var provider: PageProvider? = null
        set(value) {
            field = value
            reload()
        }

    var pageIndex: Int = 0
        set(value) {
            if (field != value) {
                field = value
                reload()
            }
        }

    var onPageStep: ((Boolean) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private var bitmap: Bitmap? = null
    private var loadToken = 0

    init {
        setBackgroundColor(Color.rgb(13, 13, 16))
    }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val w = width.coerceAtLeast(1)
                when {
                    e.x < w * 0.28f -> onPageStep?.invoke(false)
                    e.x > w * 0.72f -> onPageStep?.invoke(true)
                    else -> onCenterTap?.invoke()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val threshold = 56f * resources.displayMetrics.density
                if (abs(dx) > abs(dy) && abs(dx) > threshold) {
                    onPageStep?.invoke(dx < 0)
                }
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    private fun reload() {
        val p = provider ?: return
        val target = pageIndex
        val token = ++loadToken
        val current = bitmap
        bitmap = null
        current?.recycle()
        invalidate()
        executor.execute {
            val bmp = runCatching { p.decode(target, width.coerceAtLeast(720)) }.getOrNull()
            post {
                if (token != loadToken) {
                    bmp?.recycle()
                    return@post
                }
                bitmap = bmp
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (bmp.isRecycled || width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    override fun onDetachedFromWindow() {
        executor.shutdownNow()
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }
}
