package com.enhance.comic.render

import android.graphics.Bitmap
import android.graphics.RectF

/** Supplies page bitmaps to the renderer. Implementations are called on a worker thread. */
interface PageProvider {
    val pageCount: Int

    /** width / height of a source page. May be called from the GL thread. */
    fun aspectOf(pageIndex: Int): Float

    /**
     * width / height **as displayed**, i.e. after the white border has been trimmed.
     *
     * Layout must use this one: a cropped page is narrower than its source, and sizing the
     * frame with the original ratio is exactly what stretches the artwork.
     */
    fun displayAspect(pageIndex: Int): Float = aspectOf(pageIndex)

    /** Normalized content box of a page, null when the page should be shown in full. */
    fun cropOf(pageIndex: Int): RectF? = null

    /** Decodes a page, subsampled to roughly [targetWidth] pixels wide. */
    fun decode(pageIndex: Int, targetWidth: Int): Bitmap?
}
