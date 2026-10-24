package com.enhance.comic.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.enhance.comic.model.Book
import com.enhance.comic.model.BookType
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.ReaderPrefs
import com.github.junrar.Archive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

/** JSON-on-SharedPreferences store: the library is small, no database needed. */
class BookStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("enhance_comic", Context.MODE_PRIVATE)

    private val booksDir: File = File(context.filesDir, "books").apply { mkdirs() }

    private val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    fun loadBooks(): List<Book> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Book.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun saveBooks(books: List<Book>) {
        val arr = JSONArray()
        books.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_BOOKS, arr.toString()).apply()
    }

    var enhance: EnhanceSettings
        get() = prefs.getString(KEY_ENHANCE, null)
            ?.let { runCatching { EnhanceSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: EnhanceSettings()
        set(value) {
            prefs.edit().putString(KEY_ENHANCE, value.toJson().toString()).apply()
        }

    var readerPrefs: ReaderPrefs
        get() = prefs.getString(KEY_READER, null)
            ?.let { runCatching { ReaderPrefs.fromJson(JSONObject(it)) }.getOrNull() }
            ?: ReaderPrefs()
        set(value) {
            prefs.edit().putString(KEY_READER, value.toJson().toString()).apply()
        }

    /** Copies a picked document into app storage so later reads are plain file access. */
    fun importDocument(uri: Uri, name: String?): Book? {
        val resolver = context.contentResolver
        val display = name
            ?: DocumentFile.fromSingleUri(context, uri)?.name
            ?: "book_${System.currentTimeMillis()}"
        val ext = display.substringAfterLast('.', "").lowercase()
        val byExtension = when (ext) {
            "zip", "cbz" -> BookType.ZIP
            "rar", "cbr" -> BookType.RAR
            "pdf" -> BookType.PDF
            else -> null
        }
        val safeName = display.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        val dst = File(booksDir, "${UUID.randomUUID().toString().take(8)}_$safeName")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out, 256 * 1024) }
            } ?: return null
        } catch (_: Throwable) {
            dst.delete()
            return null
        }

        // 7z / tar 系列：solid block 和流式格式都没法按页随机读，导入时整包解成目录。
        val packed = ArchiveExtractor.kindFor(display, readHead(dst))
        if (packed != null) return importPacked(dst, display, packed)

        // Some file providers hand over files with no extension, or with an odd one. The
        // first bytes are a reliable answer where the name is not.
        val type = byExtension ?: sniffType(dst)
        if (type == null) {
            dst.delete()
            return null
        }

        val pageCount = when (type) {
            BookType.ZIP -> runCatching {
                ZipFile(dst).use { zip ->
                    zip.entries().toList().count { !it.isDirectory && ImageUtil.isImageName(it.name) }
                }
            }.getOrDefault(0)

            BookType.RAR -> runCatching {
                Archive(dst).use { archive ->
                    archive.fileHeaders.count {
                        !it.isDirectory && ImageUtil.isImageName(it.fileName ?: "")
                    }
                }
            }.getOrDefault(0)

            BookType.PDF -> runCatching {
                ParcelFileDescriptor.open(dst, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(0)

            BookType.FOLDER -> 0
        }

        if (pageCount <= 0) {
            dst.delete()
            return null
        }

        val book = Book(
            id = UUID.randomUUID().toString(),
            title = display.substringBeforeLast('.'),
            type = type,
            path = dst.absolutePath,
            pageCount = pageCount,
        )
        return book.copy(coverPath = writeCover(book).orEmpty())
    }

    /** 7z / tar：整包解成目录入库，原压缩包在解出图片后删掉，库里只留一份。 */
    private fun importPacked(file: File, display: String, kind: ArchiveExtractor.Kind): Book? {
        val folder = File(booksDir, UUID.randomUUID().toString().take(8))
        val count = runCatching { ArchiveExtractor.extract(file, kind, folder) }.getOrDefault(0)
        if (count <= 0) {
            folder.deleteRecursively()
            file.delete()
            return null
        }
        file.delete()
        val label = when (kind) {
            ArchiveExtractor.Kind.SEVENZIP -> "7Z"
            ArchiveExtractor.Kind.TAR -> "TAR"
            ArchiveExtractor.Kind.TAR_GZ -> "TAR.GZ"
            ArchiveExtractor.Kind.TAR_BZ2 -> "TAR.BZ2"
            ArchiveExtractor.Kind.TAR_XZ -> "TAR.XZ"
        }
        val book = Book(
            id = UUID.randomUUID().toString(),
            title = archiveTitle(display),
            type = BookType.FOLDER,
            path = folder.absolutePath,
            pageCount = count,
            sourceLabel = label,
        )
        return book.copy(coverPath = writeCover(book).orEmpty())
    }

    /** "xx.tar.gz" 这种双后缀只去掉真正的扩展名，别把名字削成 "xx.tar"。 */
    private fun archiveTitle(display: String): String {
        val lower = display.lowercase()
        val suffixes = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz")
        suffixes.firstOrNull { lower.endsWith(it) }?.let {
            return display.dropLast(it.length)
        }
        return display.substringBeforeLast('.')
    }

    private fun readHead(file: File): ByteArray = runCatching {
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(512)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    /** Detects the container format from the magic bytes, independent of the file name. */    private fun sniffType(file: File): BookType? = try {
        java.io.FileInputStream(file).use { input ->
            val head = ByteArray(8)
            val read = input.read(head)
            if (read < 4) {
                null
            } else {
                when {
                    head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> BookType.ZIP
                    head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
                        head[2] == 0x72.toByte() && head[3] == 0x21.toByte() -> BookType.RAR
                    head[0] == 0x25.toByte() && head[1] == 0x50.toByte() &&
                        head[2] == 0x44.toByte() && head[3] == 0x46.toByte() -> BookType.PDF
                    else -> null
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    fun importFolder(uri: Uri): Book? {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return null
        val files = tree.listFiles().filter { it.isFile && ImageUtil.isImageName(it.name.orEmpty()) }
        if (files.isEmpty()) return null
        val book = Book(
            id = UUID.randomUUID().toString(),
            title = tree.name ?: "文件夹",
            type = BookType.FOLDER,
            path = uri.toString(),
            pageCount = files.size,
        )
        return book.copy(coverPath = writeCover(book).orEmpty())
    }

    /** Generates missing covers for books imported by an earlier version. */
    fun backfillCovers(books: List<Book>): List<Book> = books.map { book ->
        if (book.coverPath.isNotEmpty() && File(book.coverPath).isFile) {
            book
        } else {
            book.copy(coverPath = writeCover(book).orEmpty())
        }
    }

    /** Renders page 1 into a small JPEG so the shelf shows real artwork. */
    private fun writeCover(book: Book): String? {
        val session = BookSession.open(context, book) ?: return null
        return try {
            val bitmap = session.decode(0, 480)
            if (bitmap == null) {
                null
            } else {
                val bytes = ImageUtil.encodeCover(bitmap)
                bitmap.recycle()
                if (bytes == null) {
                    null
                } else {
                    val file = File(coversDir, "${book.id}.jpg")
                    file.writeBytes(bytes)
                    file.absolutePath
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            session.close()
        }
    }

    /** 用指定源页重新生成封面（阅读器的「设为封面」）。 */
    fun setCoverFromPage(book: Book, page: Int): Book? {
        val session = BookSession.open(context, book) ?: return null
        return try {
            val bitmap = session.decode(page, 480) ?: return null
            val bytes = ImageUtil.encodeCover(bitmap)
            bitmap.recycle()
            if (bytes == null) {
                null
            } else {
                val file = File(coversDir, "${book.id}.jpg")
                file.writeBytes(bytes)
                val updated = book.copy(coverPath = file.absolutePath)
                saveBooks(loadBooks().map { if (it.id == updated.id) updated else it })
                updated
            }
        } catch (_: Throwable) {
            null
        } finally {
            session.close()
        }
    }

    fun deleteBook(book: Book) {
        val remaining = loadBooks().filterNot { it.id == book.id }
        saveBooks(remaining)
        if (book.coverPath.isNotEmpty()) {
            runCatching { File(book.coverPath).delete() }
        }
        if (book.type == BookType.FOLDER) {
            // 7z / tar 解出来的目录是 App 自己的存储，删书要一起删；SAF 的文件夹不能动。
            val dir = File(book.path)
            if (!book.path.startsWith("content://") &&
                dir.canonicalPath.startsWith(booksDir.canonicalPath + File.separator)
            ) {
                runCatching { dir.deleteRecursively() }
            }
        } else {
            runCatching { File(book.path).delete() }
        }
    }

    private fun java.util.Enumeration<java.util.zip.ZipEntry>.toList(): List<java.util.zip.ZipEntry> =
        buildList {
            while (hasMoreElements()) add(nextElement())
        }

    private companion object {
        const val KEY_BOOKS = "books"
        const val KEY_ENHANCE = "enhance"
        const val KEY_READER = "reader"
    }
}
