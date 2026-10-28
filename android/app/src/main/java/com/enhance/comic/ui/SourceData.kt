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
 * 发现页 / 首页的结果缓存（内存 + 磁盘）。
 *
 * 分两层，签名不同：
 *  - `w|…` 聚合结果（跨源合并后的 Work 列表）：首页热门、「全部」用
 *  - `s|<源>|<关键词>` 单个源自己那一段：点某个源的 chip 时直接用它
 *
 * 为什么必须有「单源」这一层 —— 引擎是同步桥，一次发 8 个源其实是让它们排队，
 * 点单源时新请求排在 8 个后面，表现就是「点了源一直在转」。有了单源缓存，
 * 点进去先把上次的结果画出来，只有过了新鲜期才真的去刷。
 *
 * [Hit.fresh] = 新鲜期内，界面可以连网络都不打；否则先拿旧的顶上、后台静默更新。
 * 空结果不缓存（下次进来还应该再试一次）。
 */
object ExploreCache {
    /** 新鲜期。发现页的内容不需要分钟级同步，半小时内直接用最省事 */
    private const val FRESH_MS = 30 * 60 * 1000L
    /** 超过这个时间就别拿出来了（宁可见一次菊花，也不要显示半个月前的内容） */
    private const val KEEP_MS = 7L * 24 * 3600 * 1000
    private const val MAX_WORKS = 8
    private const val MAX_SECTIONS = 40
    /**
     * 失败几次算「这个源现在连不上」。
     *
     * 实测（国内直连）：jm/copy/Komiic 各 1.2~1.4 秒，而 ehentai / htmanga / mh18
     * 各要 10 秒以上然后失败 —— 8 个源串行就是 37 秒，用户看到的就是「一直在加载」。
     * 所以失败一次就先跳过：这些站要么要代理要么要登录，短期不会自己好。
     * 单个点进去仍然会试（用户明确要看），成功一次就恢复。
     */
    private const val FAIL_LIMIT_NO_VPN = 1
    private const val FAIL_LIMIT_VPN = 2
    /** 没开 VPN 时：多半是被墙，15 分钟后再试 */
    private const val COOL_MS_NO_VPN = 15 * 60 * 1000L
    /** 开着 VPN 时：那多半是这个源自己抽风，3 分钟就再试 */
    private const val COOL_MS_VPN = 3 * 60 * 1000L
    private const val FILE = "explore_cache.json"

    data class Hit(val works: List<Work>, val ok: Int, val fail: Int, val fresh: Boolean)

    data class SectionHit(val sections: List<ExploreSection>, val fresh: Boolean)

    private class WorkEntry(val works: List<Work>, val ok: Int, val fail: Int, val at: Long)

    private class SectionEntry(
        val sections: List<ExploreSection>,
        val at: Long,
        /** 上次这个源花了多久（毫秒），用来决定刷新顺序：快的先跑，首屏更快 */
        val cost: Long,
    )

    private class FailEntry(val count: Int, val at: Long)

    private val worksSlots = LinkedHashMap<String, WorkEntry>()
    private val sectionSlots = LinkedHashMap<String, SectionEntry>()
    private val failSlots = LinkedHashMap<String, FailEntry>()

    private var appCtx: android.content.Context? = null

    /** 当前是否有 VPN 网络。用户基本常开，开了就不该把墙外源当坏源 */
    @Volatile
    private var vpn = false
    private var file: java.io.File? = null

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "explore-cache").apply { isDaemon = true }
    }

    /** 启动时调一次：把上次的结果读回内存，发现页一进去就有内容 */
    @Synchronized
    fun attach(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
        if (file != null) return
        val f = java.io.File(ctx.filesDir, FILE)
        file = f
        runCatching {
            if (!f.exists()) return
            val root = org.json.JSONObject(f.readText())
            // v1 是扁平的 {sig: {works:…}}，v2 分成 w / s 两组
            val wRoot = root.optJSONObject("w")
            val sRoot = root.optJSONObject("s")
            if (wRoot == null && sRoot == null) {
                val it = root.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val o = root.optJSONObject(k) ?: continue
                    val works = worksFromJson(o.optJSONArray("works"))
                    if (works.isEmpty()) continue
                    worksSlots[k] = WorkEntry(works, o.optInt("ok"), o.optInt("fail"), o.optLong("at"))
                }
                return@runCatching
            }
            wRoot?.let { wr ->
                val it = wr.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val o = wr.optJSONObject(k) ?: continue
                    val works = worksFromJson(o.optJSONArray("works"))
                    if (works.isEmpty()) continue
                    worksSlots[k] = WorkEntry(works, o.optInt("ok"), o.optInt("fail"), o.optLong("at"))
                }
            }
            root.optJSONObject("f")?.let { fr ->
                val it = fr.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val o = fr.optJSONObject(k) ?: continue
                    failSlots[k] = FailEntry(o.optInt("n"), o.optLong("at"))
                }
            }
            sRoot?.let { sr ->
                val it = sr.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val o = sr.optJSONObject(k) ?: continue
                    val secs = sectionsFromJson(o.optJSONArray("secs"))
                    if (secs.isEmpty()) continue
                    sectionSlots[k] = SectionEntry(secs, o.optLong("at"), o.optLong("cost"))
                }
            }
        }
    }

    /** 有没有 VPN 网络（TRANSPORT_VPN）。分应用代理排除本 App 时会误判，但那是少数情况。 */
    private fun detectVpn(ctx: android.content.Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    /**
     * VPN 开关状态变了 → 之前的「连不上」全部作废。
     *
     * 关着 VPN 时 ehentai/htmanga 这些必然失败，一旦跳过了，用户后来打开 VPN 也还是被跳过 ——
     * 那就成了「明明能连却一直不给内容」。所以每次判断前先看一眼，变了就清空。
     */
    private fun refreshVpnLocked() {
        val ctx = appCtx ?: return
        val now = detectVpn(ctx)
        if (now == vpn) return
        vpn = now
        if (failSlots.isNotEmpty()) {
            failSlots.clear()
            persistLocked()
        }
    }

    /** 给界面用：现在是不是走着 VPN */
    @Synchronized
    fun vpnOn(): Boolean {
        refreshVpnLocked()
        return vpn
    }

    private fun failLimit(): Int = if (vpn) FAIL_LIMIT_VPN else FAIL_LIMIT_NO_VPN

    private fun coolMs(): Long = if (vpn) COOL_MS_VPN else COOL_MS_NO_VPN

    // ------------------------------------------------------------------ 聚合结果

    @Synchronized
    fun get(key: String): Hit? {
        if (key.isBlank()) return null
        val e = worksSlots[key] ?: return null
        val age = System.currentTimeMillis() - e.at
        if (age > KEEP_MS) {
            worksSlots.remove(key)
            return null
        }
        if (e.works.isEmpty()) return null
        return Hit(e.works, e.ok, e.fail, age <= FRESH_MS)
    }

    @Synchronized
    fun put(key: String, list: List<Work>, okCount: Int, failCount: Int) {
        if (key.isBlank() || list.isEmpty()) return
        if (!worksSlots.containsKey(key) && worksSlots.size >= MAX_WORKS) {
            worksSlots.keys.firstOrNull()?.let { worksSlots.remove(it) }
        }
        worksSlots[key] = WorkEntry(list.take(240), okCount, failCount, System.currentTimeMillis())
        persistLocked()
    }

    // ------------------------------------------------------------------ 单源结果

    @Synchronized
    fun getSections(key: String): SectionHit? {
        if (key.isBlank()) return null
        val e = sectionSlots[key] ?: return null
        val age = System.currentTimeMillis() - e.at
        if (age > KEEP_MS) {
            sectionSlots.remove(key)
            return null
        }
        if (e.sections.isEmpty()) return null
        return SectionHit(e.sections, age <= FRESH_MS)
    }

    /**
     * @param costMs 这次请求花了多久；下次刷新按它排序，快的源先跑，首屏更快
     */
    @Synchronized
    fun putSections(key: String, secs: List<ExploreSection>, costMs: Long = 0) {
        val alive = secs.filter { it.comics.isNotEmpty() }
        if (key.isBlank() || alive.isEmpty()) return
        if (!sectionSlots.containsKey(key) && sectionSlots.size >= MAX_SECTIONS) {
            sectionSlots.keys.firstOrNull()?.let { sectionSlots.remove(it) }
        }
        sectionSlots[key] = SectionEntry(alive, System.currentTimeMillis(), costMs)
        persistLocked()
    }

    /**
     * 这个源是不是「暂时连不上」。
     *
     * 国内直连 ehentai / wnacg / picacg 这类站必然超时，每个要等满 14 秒；
     * 「全部」一次跑 8 个源，光等它们就 40 秒 —— 表现就是永远在加载。
     * 连续失败两次就先跳过它（单个点进去仍然会试），半小时后再放出来。
     */
    @Synchronized
    fun isCooling(key: String): Boolean {
        refreshVpnLocked()
        val e = failSlots[key] ?: return false
        if (e.count < failLimit()) return false
        if (System.currentTimeMillis() - e.at > coolMs()) {
            failSlots.remove(key)
            return false
        }
        return true
    }

    /** 请求失败（超时 / 站点拒绝）记一次 */
    @Synchronized
    fun markFail(key: String) {
        if (key.isBlank()) return
        refreshVpnLocked()
        val e = failSlots[key]
        failSlots[key] = FailEntry((e?.count ?: 0) + 1, System.currentTimeMillis())
        persistLocked()
    }

    /** 成功一次就把失败计数清掉 */
    @Synchronized
    fun markOk(key: String) {
        if (failSlots.remove(key) != null) persistLocked()
    }

    /** 上次这个源用了多久；没记录返回 0（调用方自己定默认值） */
    @Synchronized
    fun cost(key: String): Long = sectionSlots[key]?.cost ?: 0

    /**
     * 只清聚合结果，保留每个源自己那份。
     *
     * 改源开关/排序时用这个：聚合签名确实变了要重算，但单个源的内容没变 ——
     * 以前一律 clear()，结果「关掉一个源」就把所有源的缓存也清了，
     * 再点任何 chip 都得重新拉一遍，这就是「又在重复加载」。
     */
    @Synchronized
    fun clearAggregates() {
        worksSlots.clear()
        persistLocked()
    }

    @Synchronized
    fun clear() {
        worksSlots.clear()
        sectionSlots.clear()
        persistLocked()
    }

    private fun persistLocked() {
        val f = file ?: return
        val root = org.json.JSONObject()
        val w = org.json.JSONObject()
        worksSlots.forEach { (k, e) ->
            w.put(k, org.json.JSONObject().apply {
                put("at", e.at)
                put("ok", e.ok)
                put("fail", e.fail)
                put("works", worksToJson(e.works))
            })
        }
        val sr = org.json.JSONObject()
        sectionSlots.forEach { (k, e) ->
            sr.put(k, org.json.JSONObject().apply {
                put("at", e.at)
                put("cost", e.cost)
                put("secs", sectionsToJson(e.sections))
            })
        }
        val fr = org.json.JSONObject()
        failSlots.forEach { (k, e) ->
            fr.put(k, org.json.JSONObject().apply {
                put("n", e.count)
                put("at", e.at)
            })
        }
        root.put("w", w)
        root.put("s", sr)
        root.put("f", fr)
        val text = root.toString()
        io.execute {
            runCatching { f.writeText(text) }
        }
    }
}

/**
 * 单源缓存的签名。首页热门和发现页共用同一份 —— 首页拉过的源，点进发现页的 chip
 * 就不用再拉一次（以前两边签名不同，等于把所有源拉了两遍）。
 */
internal fun exploreSig(key: String, q: String): String = "s|" + key + "|" + q

/** 单源分段结果的编解码（发现页点某个源时用它做即时显示）。 */
fun comicRowToJson(c: ComicRow): JSONObject = JSONObject().apply {
    put("id", c.id)
    put("t", c.title)
    put("c", c.cover)
    put("sub", c.sub)
    put("sk", c.sourceKey)
    put("sn", c.sourceName)
}

fun comicRowFromJson(o: JSONObject?): ComicRow? {
    o ?: return null
    val id = o.optString("id")
    if (id.isBlank()) return null
    return ComicRow(
        id = id,
        title = o.optString("t"),
        cover = o.optString("c"),
        sub = o.optString("sub"),
        sourceKey = o.optString("sk"),
        sourceName = o.optString("sn"),
    )
}

fun sectionsToJson(secs: List<ExploreSection>): JSONArray = JSONArray().apply {
    secs.forEach { s ->
        put(JSONObject().apply {
            put("t", s.title)
            put("c", JSONArray().apply { s.comics.forEach { put(comicRowToJson(it)) } })
        })
    }
}

fun sectionsFromJson(arr: JSONArray?): List<ExploreSection> {
    arr ?: return emptyList()
    val out = ArrayList<ExploreSection>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val cArr = o.optJSONArray("c") ?: continue
        val comics = ArrayList<ComicRow>(cArr.length())
        for (j in 0 until cArr.length()) comicRowFromJson(cArr.optJSONObject(j))?.let { comics += it }
        if (comics.isEmpty()) continue
        out += ExploreSection(o.optString("t"), comics)
    }
    return out
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
