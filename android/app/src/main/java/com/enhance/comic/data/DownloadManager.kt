package com.enhance.comic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.enhance.comic.model.Book
import com.enhance.comic.model.BookType
import com.enhance.comic.ui.descramble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max

/**
 * 漫画离线下载（与 iOS 1.9.85 同一套语义）。
 *
 * 一本书 = 一个任务 = `filesDir/downloads/<key>/` 目录（章节子目录/页图），
 * 落盘后注册成 FOLDER 型本地书，书架 / 本地阅读器（子目录即章节）直接复用。
 *
 * 调度：
 *  - 同时最多 [MAX_BOOKS] 本（超出排队），每本内章节串行，章内页图并行 [PAGES_PARALLEL] 路，
 *    不人为限速 —— 快慢由网络质量决定；
 *  - 断点：章节内按「页文件是否存在」续传，任务队列落盘 tasks.json，杀进程重启自动续跑；
 *  - 暂停 = 取消协程 + 状态落盘；继续 = 重新排队（已完成的章节/页直接跳过，失败章节重置重试）。
 */
object DownloadManager {
    const val MAX_BOOKS = 5
    private const val PAGES_PARALLEL = 3

    data class DlChapter(
        val id: String,
        val title: String,
        val index: Int,
        val dir: String,
        val total: Int = 0,
        val done: Int = 0,
        /** 0 待下 / 1 下载中 / 2 完成 / 3 失败 */
        val status: Int = 0,
    )

    data class DlTask(
        val id: String,
        val sourceKey: String,
        val sourceName: String,
        val comicId: String,
        val title: String,
        val cover: String,
        /** 0 排队 / 1 下载中 / 2 暂停 / 3 完成 / 4 部分章节失败 */
        val status: Int,
        val chapters: List<DlChapter>,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val doneChapters: Int get() = chapters.count { it.status == 2 }
        val totalPages: Int get() = chapters.sumOf { max(it.total, 0) }
        val donePages: Int get() = chapters.sumOf { max(it.done, 0) }
        val progress: Double
            get() = if (totalPages > 0) donePages.toDouble() / totalPages else 0.0
    }

    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> get() = _tasks

    /** 有新书注册进书库时自增；MainActivity 收到后重载 books，书架立即出现新条目 */
    private val _bookTick = MutableStateFlow(0)
    val bookTick: StateFlow<Int> get() = _bookTick

    private var context: Context? = null
    private var engine: SourceEngine? = null
    private var store: BookStore? = null
    private var root: File? = null
    private var tasksFile: File? = null
    private val jobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saveLock = Any()

    fun attach(context: Context, store: BookStore, engine: SourceEngine) {
        if (root != null) return
        val app = context.applicationContext
        this.context = app
        this.store = store
        this.engine = engine
        val r = File(app.filesDir, "downloads").apply { mkdirs() }
        root = r
        tasksFile = File(r, "tasks.json")
        load()
        // 启动续传：上次退出时在跑/排队的任务重新排队（文件级断点由下载时兜底）
        _tasks.value = _tasks.value.map { t ->
            if (t.status == 0 || t.status == 1) {
                t.copy(status = 0, chapters = t.chapters.map { if (it.status == 1) it.copy(status = 0) else it })
            } else t
        }
        save()
        pump()
    }

    // ---------------------------------------------------------------- 对外操作

    /** 入队一本（已存在则：暂停/失败 → 继续，其余不动）。 */
    fun start(
        sourceKey: String,
        sourceName: String,
        comicId: String,
        title: String,
        cover: String,
        chapters: List<Pair<String, String>>,
    ) {
        val r = root ?: return
        if (chapters.isEmpty()) return
        val key = folderKey(sourceKey, comicId)
        val cur = _tasks.value
        if (cur.any { it.id == key }) {
            val t = cur.first { it.id == key }
            if (t.status == 2 || t.status == 4) toggle(key)
            return
        }
        val chs = chapters.mapIndexed { i, (cid, ct) ->
            DlChapter(id = cid, title = ct, index = i, dir = chapterDirName(i, ct))
        }
        File(r, key).mkdirs()
        _tasks.value = cur + DlTask(
            id = key, sourceKey = sourceKey, sourceName = sourceName,
            comicId = comicId, title = title, cover = cover, status = 0, chapters = chs,
        )
        save()
        pump()
    }

    /** 暂停 ⇄ 继续（完成态无效）。 */
    fun toggle(id: String) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        when (t.status) {
            0, 1 -> {
                synchronized(jobs) { jobs.remove(id)?.cancel() }
                updateTask(id) { task ->
                    task.copy(status = 2, chapters = task.chapters.map {
                        if (it.status == 1) it.copy(status = 0) else it
                    })
                }
                save()
            }
            2, 4 -> {
                // 继续时把失败章节也重置为待下（页文件级断点保证已下的不重下）
                updateTask(id) { task ->
                    task.copy(status = 0, chapters = task.chapters.map {
                        if (it.status == 3) it.copy(status = 0) else it
                    })
                }
                save()
                pump()
            }
        }
    }

    /** 删除任务 + 删书目录 + 从书库摘除条目。 */
    fun remove(id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        _tasks.value = _tasks.value.filter { it.id != id }
        save()
        val r = root ?: return
        val st = store
        if (st != null) {
            val path = File(r, id).absolutePath
            val books = st.loadBooks()
            val hit = books.firstOrNull { it.type == BookType.FOLDER && it.path == path }
            if (hit != null) st.saveBooks(books.filter { it.id != hit.id })
        }
        File(r, id).deleteRecursively()
        File(r, "$id.jpg").delete()
    }

    // ---------------------------------------------------------------- 调度

    private fun pump() {
        val r = root ?: return
        synchronized(jobs) {
            var slots = MAX_BOOKS - jobs.size
            if (slots <= 0) return
            val cur = _tasks.value
            var next = cur
            var started = false
            for (t in cur) {
                if (slots <= 0) break
                if (t.status != 0 || jobs.containsKey(t.id)) continue
                next = next.map { if (it.id == t.id) it.copy(status = 1) else it }
                jobs[t.id] = scope.launch { runBook(t.id) }
                slots--
                started = true
            }
            if (started) {
                _tasks.value = next
                save()
            }
        }
    }

    private suspend fun runBook(id: String) {
        try {
            val t0 = _tasks.value.firstOrNull { it.id == id } ?: return
            for (ci in t0.chapters.indices) {
                if (!currentCoroutineContext().isActive) return
                if (_tasks.value.firstOrNull { it.id == id } == null) return
                if (_tasks.value.first { it.id == id }.chapters[ci].status == 2) continue
                runChapter(id, ci)
                if (!currentCoroutineContext().isActive) return
                registerBook(id)
                save()
            }
            val t2 = _tasks.value.firstOrNull { it.id == id } ?: return
            val allDone = t2.chapters.all { it.status == 2 }
            updateTask(id) { it.copy(status = if (allDone) 3 else 4) }
            registerBook(id)
            save()
        } catch (e: CancellationException) {
            // 暂停：状态已在 toggle 里置 2 并落盘，这里只做收尾
        } finally {
            synchronized(jobs) { jobs.remove(id) }
            pump()
        }
    }

    private suspend fun runChapter(id: String, ci: Int) {
        val r0 = root ?: return
        val eng = engine ?: return
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        val ch = t.chapters[ci]
        updateChapter(id, ci) { it.copy(status = 1) }

        // 1) 取页列表（惰性源只回页数，真地址逐页解析；非惰性直接给 URL 串数组）
        val data: JSONObject = try {
            val resp = eng.pages(t.sourceKey, t.comicId, ch.id)
            if (!resp.optBoolean("ok")) {
                markChapter(id, ci, 3)
                return
            }
            resp.optJSONObject("data") ?: JSONObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markChapter(id, ci, 3)
            return
        }
        val lazy = data.optBoolean("lazy")
        val urls: List<String?> = if (lazy) {
            val n = data.optInt("count")
            if (n <= 0) {
                markChapter(id, ci, 3)
                return
            }
            List(n) { null }
        } else {
            val arr = data.optJSONArray("images") ?: JSONArray()
            val list = (0 until arr.length()).map { arr.optString(it) }
            if (list.isEmpty()) {
                markChapter(id, ci, 3)
                return
            }
            list
        }
        val total = urls.size
        updateChapter(id, ci) { it.copy(total = total, status = 1) }

        val chDir = File(File(r0, id), ch.dir).apply { mkdirs() }

        // 2) 逐页下载：缺页才下（文件级断点），窗口 [PAGES_PARALLEL] 并行
        val missing = (0 until total).filter { !hasPage(chDir, it) }
        var done = total - missing.size
        updateChapter(id, ci) { it.copy(done = done) }
        var failed = 0
        val sk = t.sourceKey
        val cid = t.comicId
        val epId = ch.id
        missing.chunked(PAGES_PARALLEL).forEach { window ->
            if (!currentCoroutineContext().isActive) throw CancellationException("paused")
            val results = coroutineScope {
                window.map { pageIndex ->
                    async {
                        fetchOnePage(
                            eng, sk, cid, epId, pageIndex, lazy, urls[pageIndex],
                            File(chDir, pageFileName(pageIndex)),
                        )
                    }
                }.map { it.await() }
            }
            done += results.count { it }
            failed += results.count { !it }
            updateChapter(id, ci) { it.copy(done = done) }
            save()
        }
        updateChapter(id, ci) { it.copy(done = total - failed, status = if (failed == 0) 2 else 3) }
        save()
    }

    /** 解析（惰性）+ 下载 + 还原 + 落盘一页。 */
    private suspend fun fetchOnePage(
        eng: SourceEngine,
        sourceKey: String,
        comicId: String,
        epId: String,
        pageIndex: Int,
        lazy: Boolean,
        rawUrl: String?,
        dest: File,
    ): Boolean {
        if (dest.exists()) return true
        var url = rawUrl ?: ""
        val headers = HashMap<String, String>()
        var num = 0
        if (lazy) {
            val r = try {
                eng.pageImage(sourceKey, comicId, epId, pageIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return false
            }
            if (!r.optBoolean("ok")) return false
            val d = r.optJSONObject("data") ?: return false
            url = d.optString("url")
            val hs = d.optJSONObject("headers")
            hs?.keys()?.forEach { k -> headers[k] = hs.optString(k) }
            num = d.optJSONObject("descramble")?.optInt("num") ?: 0
        }
        if (!url.startsWith("http")) return false
        return savePage(url, headers, num, dest)
    }

    /** 下载字节（镜像 RemoteImage 的请求头语义）；num≤1 存原始字节，num>1 还原后 JPEG 重编码。 */
    private suspend fun savePage(
        url: String,
        headers: Map<String, String>,
        num: Int,
        dest: File,
    ): Boolean = withContext(Dispatchers.IO) {
        if (dest.exists()) return@withContext true
        var bytes: ByteArray? = null
        for (attempt in 0 until 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
                )
                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                var hasReferer = false
                headers.forEach { (k, v) ->
                    val lk = k.lowercase()
                    if (v.isNotBlank() && lk != "accept-encoding" && lk != "connection" && lk != "host") {
                        if (lk == "referer") hasReferer = true
                        conn.setRequestProperty(k, v)
                    }
                }
                if (!hasReferer) {
                    val u = URL(url)
                    conn.setRequestProperty("Referer", "${u.protocol}://${u.host}/")
                }
                if (conn.responseCode in 200..299) {
                    val data = conn.inputStream.use { it.readBytes() }
                    if (data.isNotEmpty()) bytes = data
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                conn?.disconnect()
            }
            if (bytes != null) break
            if (attempt < 2) delay(800)
        }
        val raw = bytes ?: return@withContext false
        val out: ByteArray = if (num > 1) {
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@withContext false
            val straight = descramble(bmp, num)
            val bos = ByteArrayOutputStream()
            straight.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            bos.toByteArray()
        } else raw
        try {
            FileOutputStream(dest).use { it.write(out) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- 本地书注册

    private fun registerBook(id: String) {
        val st = store
        val r = root
        if (st == null || r == null) return
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        val donePages = t.chapters.sumOf { max(it.done, 0) }
        if (donePages <= 0) return
        val path = File(r, id).absolutePath
        val books = st.loadBooks()
        val existing = books.firstOrNull { it.type == BookType.FOLDER && it.path == path }
        if (existing != null) {
            if (existing.pageCount != donePages) {
                st.saveBooks(books.map { if (it.id == existing.id) it.copy(pageCount = donePages) else it })
            }
            return
        }
        var coverPath = ""
        runCatching {
            val firstCh = t.chapters.firstOrNull { it.done > 0 } ?: return@runCatching
            val first = File(File(r, id), firstCh.dir)
                .listFiles()?.filter { ImageUtil.isImageName(it.name) }
                ?.sortedBy { it.name }?.firstOrNull() ?: return@runCatching
            val bmp = BitmapFactory.decodeFile(first.absolutePath) ?: return@runCatching
            val bytes = ImageUtil.encodeCover(bmp, 420, 86)
            bmp.recycle()
            if (bytes != null) {
                // 封面放在书目录外，避免被 FOLDER 阅读器当成一页
                val f = File(r, "$id.jpg")
                FileOutputStream(f).use { it.write(bytes) }
                coverPath = f.absolutePath
            }
        }
        st.saveBooks(
            books + Book(
                id = UUID.randomUUID().toString(),
                title = t.title,
                type = BookType.FOLDER,
                path = path,
                pageCount = donePages,
                coverPath = coverPath,
            ),
        )
        _bookTick.value += 1
    }

    // ---------------------------------------------------------------- 持久化

    private fun load() {
        val f = tasksFile ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            _tasks.value = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val chs = o.optJSONArray("chapters") ?: JSONArray()
                    DlTask(
                        id = o.getString("id"),
                        sourceKey = o.getString("sourceKey"),
                        sourceName = o.getString("sourceName"),
                        comicId = o.getString("comicId"),
                        title = o.getString("title"),
                        cover = o.optString("cover"),
                        status = o.optInt("status"),
                        createdAt = o.optLong("createdAt"),
                        chapters = (0 until chs.length()).mapNotNull { j ->
                            runCatching {
                                val c = chs.getJSONObject(j)
                                DlChapter(
                                    id = c.getString("id"),
                                    title = c.getString("title"),
                                    index = c.optInt("index"),
                                    dir = c.getString("dir"),
                                    total = c.optInt("total"),
                                    done = c.optInt("done"),
                                    status = c.optInt("status"),
                                )
                            }.getOrNull()
                        },
                    )
                }.getOrNull()
            }
        }
    }

    private fun save() {
        val f = tasksFile ?: return
        synchronized(saveLock) {
            runCatching {
                val arr = JSONArray()
                _tasks.value.forEach { t ->
                    val chs = JSONArray()
                    t.chapters.forEach { c ->
                        chs.put(
                            JSONObject().apply {
                                put("id", c.id)
                                put("title", c.title)
                                put("index", c.index)
                                put("dir", c.dir)
                                put("total", c.total)
                                put("done", c.done)
                                put("status", c.status)
                            },
                        )
                    }
                    arr.put(
                        JSONObject().apply {
                            put("id", t.id)
                            put("sourceKey", t.sourceKey)
                            put("sourceName", t.sourceName)
                            put("comicId", t.comicId)
                            put("title", t.title)
                            put("cover", t.cover)
                            put("status", t.status)
                            put("createdAt", t.createdAt)
                            put("chapters", chs)
                        },
                    )
                }
                f.writeText(arr.toString())
            }
        }
    }

    // ---------------------------------------------------------------- 命名 / 状态

    fun bookPath(id: String): String? = root?.let { File(it, id).absolutePath }

    /** 书目录（下载中心导出用）；不存在返回 null。 */
    fun bookDir(id: String): File? {
        val r = root ?: return null
        val d = File(r, id)
        return if (d.exists()) d else null
    }

    fun folderKey(sourceKey: String, comicId: String): String {
        val clean = comicId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        var s = sourceKey + "_" + clean
        if (s.length > 96) s = s.substring(s.length - 96)
        return s.ifEmpty { "book" }
    }

    fun chapterDirName(index: Int, title: String): String {
        val clean = title.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "章节" }
        val name = if (clean.length > 40) clean.substring(0, 40) else clean
        return String.format("%04d", index + 1) + " - " + name
    }

    fun pageFileName(i: Int): String = String.format("%05d.jpg", i + 1)

    fun hasPage(dir: File, i: Int): Boolean = File(dir, pageFileName(i)).exists()

    private fun updateTask(id: String, f: (DlTask) -> DlTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it }
    }

    private fun updateChapter(id: String, ci: Int, f: (DlChapter) -> DlChapter) {
        updateTask(id) { t -> t.copy(chapters = t.chapters.mapIndexed { i, c -> if (i == ci) f(c) else c }) }
    }

    private fun markChapter(id: String, ci: Int, status: Int) {
        updateChapter(id, ci) { it.copy(status = status) }
    }
}
