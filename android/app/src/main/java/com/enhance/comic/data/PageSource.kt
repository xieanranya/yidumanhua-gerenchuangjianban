package com.enhance.comic.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.enhance.comic.model.Book
import com.enhance.comic.model.BookType
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A run of consecutive pages that live in the same folder inside an archive. */
data class Chapter(val title: String, val start: Int, val count: Int) {
    val endExclusive: Int get() = start + count
    fun contains(page: Int): Boolean = page in start until endExclusive
}

/**
 * An opened book. All heavy work (decode / pdf render / rar extraction) must happen on one
 * thread: [PdfRenderer] is single threaded and [Archive] is not safe to read concurrently.
 */
class BookSession private constructor(
    val book: Book,
    private val resolver: ContentResolver,
    private val kind: BookType,
    private val zip: ZipFile?,
    private val zipEntries: List<ZipEntry>,
    private val rar: Archive?,
    private val rarHeaders: List<FileHeader>,
    private val pdf: PdfRenderer?,
    private val pdfFd: ParcelFileDescriptor?,
    private val folderFiles: List<Uri>,
    /** Consecutive same-folder page runs; a single entry means "no chapter UI". */
    val chapters: List<Chapter>,
) : Closeable {

    val pageCount: Int = when (kind) {
        BookType.ZIP -> zipEntries.size
        BookType.RAR -> rarHeaders.size
        BookType.PDF -> pdf?.pageCount ?: 0
        BookType.FOLDER -> folderFiles.size
    }

    val hasChapters: Boolean get() = chapters.size > 1

    private val aspects = FloatArray(pageCount) { 0f }

    /**
     * 自动裁白边：是否启用 + 每页的内容框。
     *
     * 内容框必须在**布局之前**算好 —— 图被裁窄之后，如果还按原比例排版就会拉伸变形。
     * 所以开启裁边时先扫一遍（每页解一张 240px 缩略图），把裁后比例交给布局用。
     */
    @Volatile var autoCrop: Boolean = false
        private set

    private val cropBoxes = arrayOfNulls<RectF>(pageCount)
    private val cropScanned = BooleanArray(pageCount)

    fun setAutoCrop(enabled: Boolean) {
        autoCrop = enabled
    }

    fun cropsReady(): Boolean = cropScanned.all { it }

    /** Known width / height ratio, falling back to a portrait guess. */
    fun aspectOf(index: Int): Float {
        if (index !in aspects.indices) return DEFAULT_ASPECT
        val a = aspects[index]
        return if (a > 0.01f) a else DEFAULT_ASPECT
    }

    /** 显示用宽高比：开启裁白边时是裁过之后的比例。 */
    fun displayAspectOf(index: Int): Float {
        val a = aspectOf(index)
        if (!autoCrop) return a
        val box = cropOf(index) ?: return a
        val ratio = box.width() / box.height()
        if (!ratio.isFinite() || ratio <= 0.01f) return a
        return (a * ratio).coerceIn(0.08f, 12f)
    }

    /** 该页内容框（归一化），null 表示整页保留。 */
    fun cropOf(index: Int): RectF? {
        if (!autoCrop || index !in cropBoxes.indices) return null
        if (!cropScanned[index]) return null
        return cropBoxes[index]
    }

    /** 逐页检测内容框。只做一次，结果缓存；重复调用只补没扫过的页。 */
    fun scanCrops(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        // PDF 每页是重新渲染的矢量页，没有扫描白边，跳过省时间。
        if (kind == BookType.PDF) {
            java.util.Arrays.fill(cropScanned, true)
            onProgress(pageCount, pageCount)
            return
        }
        for (i in 0 until pageCount) {
            if (cropScanned[i]) continue
            val small = decode(i, 240)
            cropBoxes[i] = if (small != null) {
                try {
                    ImageUtil.detectContentBox(small)
                } finally {
                    small.recycle()
                }
            } else {
                null
            }
            cropScanned[i] = true
            if ((i + 1) % 8 == 0) onProgress(i + 1, pageCount)
        }
        onProgress(pageCount, pageCount)
    }

    fun hasAspect(index: Int): Boolean = index in aspects.indices && aspects[index] > 0.01f

    fun chapterIndexAt(page: Int): Int {
        if (chapters.isEmpty()) return -1
        for (i in chapters.indices) if (chapters[i].contains(page)) return i
        return chapters.lastIndex
    }

    fun chapterAt(page: Int): Chapter? = chapters.getOrNull(chapterIndexAt(page))

    /**
     * Reads the size of every page up front. Cheap for zip/pdf, one bounds decode per
     * image for folders, and one full entry extraction per page for RAR.
     */
    fun scanAspects(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        for (i in 0 until pageCount) {
            if (aspects[i] > 0.01f) continue
            aspects[i] = when (kind) {
                BookType.PDF -> pdf?.let { r ->
                    runCatching {
                        r.openPage(i).use { it.width.toFloat() / it.height.toFloat() }
                    }.getOrNull()
                } ?: DEFAULT_ASPECT

                BookType.ZIP -> ImageUtil.readAspect { zip?.getInputStream(zipEntries[i]) }
                    ?: DEFAULT_ASPECT

                BookType.RAR -> rarBytes(i)?.let { ImageUtil.aspectOfBytes(it) } ?: DEFAULT_ASPECT

                BookType.FOLDER -> ImageUtil.readAspect { resolver.openInputStream(folderFiles[i]) }
                    ?: DEFAULT_ASPECT
            }
            if ((i + 1) % 8 == 0) onProgress(i + 1, pageCount)
        }
        onProgress(pageCount, pageCount)
    }

    fun decode(index: Int, targetWidth: Int): Bitmap? {
        if (index !in 0 until pageCount) return null
        return try {
            when (kind) {
                BookType.ZIP -> ImageUtil.decodeStream(
                    open = { zip?.getInputStream(zipEntries[index]) },
                    targetWidth = targetWidth,
                )

                BookType.RAR -> rarBytes(index)?.let { ImageUtil.decodeBytes(it, targetWidth) }

                BookType.FOLDER -> ImageUtil.decodeStream(
                    open = { resolver.openInputStream(folderFiles[index]) },
                    targetWidth = targetWidth,
                )

                BookType.PDF -> renderPdf(index, targetWidth)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------- rar io

    private val rarLock = Any()
    private val rarCache = object : LinkedHashMap<Int, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > 3
    }

    /**
     * junrar can only stream an entry forward, so an entry is fully materialised before it
     * is decoded. Two small pages are kept around because a page is read twice (bounds then
     * pixels) and often re-read when the renderer cache misses.
     */
    private fun rarBytes(index: Int): ByteArray? {
        synchronized(rarLock) {
            rarCache[index]?.let { return it }
            val archive = rar ?: return null
            val header = rarHeaders.getOrNull(index) ?: return null
            val bytes = try {
                archive.getInputStream(header).use { it.readBytes() }
            } catch (_: Throwable) {
                null
            } ?: return null
            if (bytes.isEmpty()) return null
            rarCache[index] = bytes
            return bytes
        }
    }

    /**
     * PdfRenderer 只允许一页一页地开，解码池有两个线程，所以这里自己串起来。
     * 单页渲染通常在 20ms 以内，串行不会成为瓶颈。
     */
    private val pdfLock = Any()

    private fun renderPdf(index: Int, targetWidth: Int): Bitmap? =
        synchronized(pdfLock) { renderPdfLocked(index, targetWidth) }

    private fun renderPdfLocked(index: Int, targetWidth: Int): Bitmap? {
        val renderer = pdf ?: return null
        val page = renderer.openPage(index)
        return try {
            val width = targetWidth.coerceIn(64, page.width * 4)
            val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    }

    override fun close() {
        runCatching { zip?.close() }
        runCatching { rar?.close() }
        runCatching { pdf?.close() }
        runCatching { pdfFd?.close() }
    }

    companion object {
        const val DEFAULT_ASPECT = 1f / 1.42f

        fun open(context: Context, book: Book): BookSession? = try {
            when (book.type) {
                BookType.ZIP -> {
                    val zip = ZipFile(File(book.path))
                    val entries = zip.entries().toList()
                        .filter { !it.isDirectory && ImageUtil.isImageName(it.name) }
                        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    BookSession(
                        book, context.contentResolver, BookType.ZIP, zip, entries,
                        null, emptyList(), null, null, emptyList(),
                        buildChapters(entries.map { it.name }),
                    )
                }

                BookType.RAR -> {
                    val archive = Archive(File(book.path))
                    val headers = archive.fileHeaders
                        .filter { !it.isDirectory && ImageUtil.isImageName(it.fileName ?: "") }
                        .sortedWith { a, b ->
                            naturalCompare(a.fileName ?: "", b.fileName ?: "")
                        }
                    BookSession(
                        book, context.contentResolver, BookType.RAR, null, emptyList(),
                        archive, headers, null, null, emptyList(),
                        buildChapters(headers.map { it.fileName ?: "" }),
                    )
                }

                BookType.PDF -> {
                    val file = File(book.path)
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(fd)
                    BookSession(
                        book, context.contentResolver, BookType.PDF, null, emptyList(),
                        null, emptyList(), renderer, fd, emptyList(),
                        listOf(Chapter("", 0, renderer.pageCount)),
                    )
                }

                BookType.FOLDER -> {
                    // 两种来源共用 FOLDER：SAF 选的文件夹（content://），以及 7z / tar
                    // 导入时解到 App 存储里的真实目录（绝对路径，可能带子目录 = 章节）。
                    val entries: List<Pair<String, Uri>> = if (book.path.startsWith("content://")) {
                        val tree = DocumentFile.fromTreeUri(context, Uri.parse(book.path))
                        tree?.listFiles()
                            ?.filter { it.isFile && ImageUtil.isImageName(it.name.orEmpty()) }
                            ?.map { it.name.orEmpty() to it.uri }
                            .orEmpty()
                    } else {
                        val dir = File(book.path)
                        if (!dir.isDirectory) {
                            emptyList()
                        } else {
                            dir.walkTopDown()
                                .filter { it.isFile && ImageUtil.isImageName(it.name) }
                                .map {
                                    it.relativeTo(dir).path.replace(File.separatorChar, '/') to
                                        Uri.fromFile(it)
                                }
                                .sortedWith { a, b -> naturalCompare(a.first, b.first) }
                                .toList()
                        }
                    }
                    val files = entries.map { it.second }
                    BookSession(
                        book, context.contentResolver, BookType.FOLDER, null, emptyList(),
                        null, emptyList(), null, null, files,
                        buildChapters(entries.map { it.first }),
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * Splits page names into chapters by their parent folder. Archives that keep a flat
         * list of images (the common case) produce a single chapter, which the UI hides.
         */
        fun buildChapters(names: List<String>): List<Chapter> {
            if (names.isEmpty()) return emptyList()
            val dirs = names.map { it.substringBeforeLast('/', "") }
            val distinct = dirs.distinct()
            if (distinct.size < 2) {
                return listOf(Chapter(singleTitle(dirs.first()), 0, names.size))
            }

            val out = ArrayList<Chapter>()
            var runStart = 0
            for (i in 1..names.size) {
                if (i == names.size || dirs[i] != dirs[runStart]) {
                    out.add(Chapter(dirTitle(dirs[runStart], out.size + 1), runStart, i - runStart))
                    runStart = i
                }
            }
            // Every page in its own folder is noise, not a table of contents.
            if (out.size < 2) return listOf(Chapter(singleTitle(dirs.first()), 0, names.size))
            return out
        }

        private fun dirTitle(dir: String, fallbackIndex: Int): String {
            val last = dir.trimEnd('/').substringAfterLast('/')
            return last.ifBlank { "第 $fallbackIndex 段" }
        }

        private fun singleTitle(dir: String?): String =
            dir?.trimEnd('/')?.substringAfterLast('/').orEmpty()

        private fun java.util.Enumeration<ZipEntry>.toList(): List<ZipEntry> = buildList {
            while (hasMoreElements()) add(nextElement())
        }

        /** "page2" < "page10" ordering, the way comic archives are usually named. */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var si = i
                    var sj = j
                    while (si < a.length && a[si].isDigit()) si++
                    while (sj < b.length && b[sj].isDigit()) sj++
                    val na = a.substring(i, si).trimStart('0')
                    val nb = b.substring(j, sj).trimStart('0')
                    val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                    if (cmp != 0) return cmp
                    i = si
                    j = sj
                } else {
                    val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cmp != 0) return cmp
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
