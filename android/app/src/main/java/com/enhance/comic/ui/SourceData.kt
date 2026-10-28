package com.enhance.comic.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个源在界面上的状态。
 *
 * 只有登录与开关——**没有线路这个概念**。域名不通会自动换别名重试一次，
 * 用户不需要知道「直连还是 VPN」，就像普通聚合软件那样。
 */
data class SourceRow(
    val key: String,
    val name: String,
    val note: String = "",
    val canLogin: Boolean = false,
    val logged: Boolean = false,
    val needLogin: Boolean = false,
    /** 用户是否把它算进「全部源」的搜索与首页推荐里 */
    val enabled: Boolean = true,
)

data class ComicRow(
    val id: String,
    val title: String,
    val cover: String,
    val sub: String,
    /** 跨源聚合时要知道这条来自哪个源；单源页面上留空也无所谓 */
    val sourceKey: String = "",
    val sourceName: String = "",
)

data class ExploreSection(val title: String, val comics: List<ComicRow>)

/** 探索页可能返回 [{title,comics}] / {comics,maxPage} / {sections:[...]}，这里统一成 sections。 */
fun parseSections(
    data: JSONObject?,
    sourceKey: String = "",
    sourceName: String = "",
): List<ExploreSection> {
    data ?: return emptyList()
    val arr = data.optJSONArray("sections") ?: return emptyList()
    val out = mutableListOf<ExploreSection>()
    for (i in 0 until arr.length()) {
        val s = arr.optJSONObject(i) ?: continue
        val cs = s.optJSONArray("comics") ?: continue
        val list = (0 until cs.length()).mapNotNull { idx ->
            cs.optJSONObject(idx)?.let { o ->
                val id = o.optString("id")
                if (id.isBlank()) {
                    null
                } else {
                    ComicRow(
                        id,
                        o.optString("title"),
                        o.optString("cover"),
                        o.optString("subTitle"),
                        sourceKey,
                        sourceName,
                    )
                }
            }
        }
        if (list.isNotEmpty()) out += ExploreSection(s.optString("title"), list)
    }
    return out
}

fun parseComics(
    data: JSONObject?,
    sourceKey: String = "",
    sourceName: String = "",
): List<ComicRow> {
    data ?: return emptyList()
    val arr = data.optJSONArray("comics") ?: return emptyList()
    val out = mutableListOf<ComicRow>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id")
        if (id.isBlank()) continue
        out += ComicRow(
            id = id,
            title = o.optString("title"),
            cover = o.optString("cover"),
            sub = o.optString("subTitle"),
            sourceKey = sourceKey,
            sourceName = sourceName,
        )
    }
    return out
}

/**
 * 把引擎给的源列表套上用户的开关与顺序。
 *
 * 顺序：`order` 里列出的按它的先后排；没列到的（引擎后来新增的）保持引擎原顺序接在后面。
 * 用稳定的 sortedBy，所以「没列到」的彼此之间不会被搅乱。
 */
fun buildRows(
    srcs: JSONArray,
    off: Set<String> = emptySet(),
    order: List<String> = emptyList(),
): List<SourceRow> {
    val rows = mutableListOf<SourceRow>()
    for (i in 0 until srcs.length()) {
        val s = srcs.optJSONObject(i) ?: continue
        val key = s.optString("key")
        if (key.isBlank()) continue
        rows += SourceRow(
            key = key,
            name = s.optString("name", key),
            note = s.optString("note"),
            canLogin = s.optBoolean("canLogin"),
            logged = s.optBoolean("logged"),
            needLogin = s.optBoolean("needLogin"),
        )
    }
    val rank = order.withIndex().associate { (i, k) -> k to i }
    return rows
        .sortedBy { rank[it.key] ?: Int.MAX_VALUE }
        .map { it.copy(enabled = it.key !in off) }
}

/** 源小结：只说数量与开关，不提线路（已经不存在这个概念）。 */
fun sourceSummary(rows: List<SourceRow>): String {
    if (rows.isEmpty()) return "正在读取源列表…"
    val logged = rows.count { it.logged }
    val enabled = rows.count { it.enabled }
    return buildString {
        append("$enabled/${rows.size} 个源在用")
        if (logged > 0) append(" · $logged 个已登录")
    }
}

/** 源抛出来的网络错误太生硬（"HTTP 0"），换成用户能照做的说法。 */
fun friendlyError(e: String): String {
    val low = e.lowercase()
    return when {
        low.contains("status code: 0") || e.contains("HTTP 0") ||
            low.contains("请求失败: 0") || low.contains("timed out") || low.contains("timeout") ->
            "连不上这个源。先确认网站本身能打开；E-Hentai / 绅士漫画 / PicACG 这类站点国内要开代理。"
        else -> e
    }
}

// ================================================================================ 作品聚合

/** 同一本书在某个源里的入口。 */
data class WorkEntry(
    val sourceKey: String,
    val sourceName: String,
    val comicId: String,
    val title: String,
    val cover: String,
)

/**
 * 聚合后的「一部作品」。entries 按源顺序排列，第一个用来展示。
 *
 * 各源没有互通的 ID，所以只认**标题归一化后完全一致**的条目；
 * 宁可少合也不把两本不同的书串成一本。
 */
data class Work(
    val norm: String,
    val title: String,
    val cover: String,
    val entries: List<WorkEntry>,
) {
    val badge: String get() = if (entries.size > 1) "${entries.size} 源" else ""
    val sourceNames: String get() = entries.map { it.sourceName }.distinct().joinToString(" / ")
    val primary: WorkEntry get() = entries.first()
}

/** 标题指纹：去掉空白、标点、括号，统一小写，只留文字和数字。 */
fun normalizeTitle(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw.lowercase()) {
        if (ch.isLetterOrDigit()) sb.append(ch)
    }
    return sb.toString()
}

fun ComicRow.asWork(): Work = Work(
    norm = normalizeTitle(title),
    title = title,
    cover = cover,
    entries = listOf(WorkEntry(sourceKey, sourceName, id, title, cover)),
)

/**
 * 跨源聚合：多源优先排在前面，其余保持原顺序。
 *
 * [sort] = false 时不做「多源优先」重排，只按首次出现顺序摆。
 * 发现页是流式上屏的，每来一个源都重排一次会让整片格子跳，所以流式过程中传 false，
 * 全部源到齐后再排一次。
 */
fun groupWorks(list: List<ComicRow>, sort: Boolean = true): List<Work> {
    val index = HashMap<String, Int>()
    val out = ArrayList<Work>()
    for (row in list) {
        val norm = normalizeTitle(row.title)
        if (norm.isBlank()) continue
        val entry = WorkEntry(row.sourceKey, row.sourceName, row.id, row.title, row.cover)
        val at = index[norm]
        if (at == null) {
            index[norm] = out.size
            out += Work(norm, row.title, row.cover, listOf(entry))
        } else {
            val cur = out[at]
            if (cur.entries.any { it.sourceKey == entry.sourceKey && it.comicId == entry.comicId }) continue
            out[at] = cur.copy(
                cover = cur.cover.ifBlank { row.cover },
                entries = cur.entries + entry,
            )
        }
    }
    if (!sort) return out
    val multi = out.filter { it.entries.size > 1 }.sortedByDescending { it.entries.size }
    val single = out.filter { it.entries.size == 1 }
    return multi + single
}

/**
 * 发现页「全部源」的结果缓存。
 *
 * 引擎的 JS 是同步桥，多个源只能排队跑，一次全量要十几秒。切回发现页时如果 3 分钟内
 * 刚拉过同一批源、同一个关键词，就直接把上次的结果画出来，不再重新打一遍网络。
 * 只放内存（进程退了就没了），空结果不缓存——下次进来还应该再试一次。
 */
object ExploreCache {
    /** 这么短时间内再次进来：直接用，不刷新 */
    private const val FRESH_MS = 10 * 60 * 1000L
    /** 超过这个时间就别拿出来了（宁可见一次菊花，也不要显示半个月前的内容） */
    private const val KEEP_MS = 7L * 24 * 3600 * 1000
    private const val MAX_ENTRIES = 4
    private const val FILE = "explore_cache.json"

    data class Hit(val works: List<Work>, val ok: Int, val fail: Int, val fresh: Boolean)

    private class Entry(val works: List<Work>, val ok: Int, val fail: Int, val at: Long)

    private val slots = LinkedHashMap<String, Entry>()
    private var file: java.io.File? = null

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "explore-cache").apply { isDaemon = true }
    }

    /** 启动时调一次：把上次的结果读回内存，发现页一进去就有内容 */
    @Synchronized
    fun attach(ctx: android.content.Context) {
        if (file != null) return
        val f = java.io.File(ctx.filesDir, FILE)
        file = f
        runCatching {
            if (!f.exists()) return
            val root = org.json.JSONObject(f.readText())
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                val o = root.optJSONObject(k) ?: continue
                val works = worksFromJson(o.optJSONArray("works"))
                if (works.isEmpty()) continue
                slots[k] = Entry(works, o.optInt("ok"), o.optInt("fail"), o.optLong("at"))
            }
        }
    }

    /**
     * 内存 → 磁盘。过期（超过 KEEP_MS）的条目直接丢掉。
     * [Hit.fresh] = 10 分钟内拉的，界面可以完全不刷新；false 就先拿旧的顶上，后台静默更新。
     */
    @Synchronized
    fun get(key: String): Hit? {
        if (key.isBlank()) return null
        val e = slots[key] ?: return null
        val age = System.currentTimeMillis() - e.at
        if (age > KEEP_MS) {
            slots.remove(key)
            return null
        }
        if (e.works.isEmpty()) return null
        return Hit(e.works, e.ok, e.fail, age <= FRESH_MS)
    }

    @Synchronized
    fun put(key: String, list: List<Work>, okCount: Int, failCount: Int) {
        if (key.isBlank() || list.isEmpty()) return
        if (!slots.containsKey(key) && slots.size >= MAX_ENTRIES) {
            slots.keys.firstOrNull()?.let { slots.remove(it) }
        }
        slots[key] = Entry(list.take(240), okCount, failCount, System.currentTimeMillis())
        persistLocked()
    }

    @Synchronized
    fun clear() {
        slots.clear()
        persistLocked()
    }

    private fun persistLocked() {
        val f = file ?: return
        val snapshot = slots.mapValues { (_, e) ->
            org.json.JSONObject().apply {
                put("at", e.at)
                put("ok", e.ok)
                put("fail", e.fail)
                put("works", worksToJson(e.works))
            }
        }
        val root = org.json.JSONObject()
        snapshot.forEach { (k, v) -> root.put(k, v) }
        val text = root.toString()
        io.execute {
            runCatching { f.writeText(text) }
        }
    }
}

// ------------------------------------------------------------------ 缓存落盘用的编解码
// 发现页/首页的推荐结果要能跨启动直接显示，所以 Work 得能存成 JSON。

fun workToJson(w: Work): JSONObject = JSONObject().apply {
    put("n", w.norm)
    put("t", w.title)
    put("c", w.cover)
    val arr = JSONArray()
    w.entries.forEach { e ->
        arr.put(JSONObject().apply {
            put("sk", e.sourceKey)
            put("sn", e.sourceName)
            put("id", e.comicId)
            put("t", e.title)
            put("c", e.cover)
        })
    }
    put("e", arr)
}

fun workFromJson(o: JSONObject?): Work? {
    o ?: return null
    val arr = o.optJSONArray("e") ?: return null
    val entries = ArrayList<WorkEntry>(arr.length())
    for (i in 0 until arr.length()) {
        val e = arr.optJSONObject(i) ?: continue
        val id = e.optString("id")
        if (id.isBlank()) continue
        entries += WorkEntry(
            sourceKey = e.optString("sk"),
            sourceName = e.optString("sn"),
            comicId = id,
            title = e.optString("t"),
            cover = e.optString("c"),
        )
    }
    if (entries.isEmpty()) return null
    return Work(
        norm = o.optString("n").ifBlank { normalizeTitle(o.optString("t")) },
        title = o.optString("t"),
        cover = o.optString("c"),
        entries = entries,
    )
}

fun worksToJson(list: List<Work>): JSONArray = JSONArray().apply { list.forEach { put(workToJson(it)) } }

fun worksFromJson(arr: JSONArray?): List<Work> {
    arr ?: return emptyList()
    val out = ArrayList<Work>(arr.length())
    for (i in 0 until arr.length()) workFromJson(arr.optJSONObject(i))?.let { out += it }
    return out
}
