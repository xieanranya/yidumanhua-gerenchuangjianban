package com.enhance.comic.ui

import org.json.JSONArray
import org.json.JSONObject

/** 一个源在界面上的状态：线路、登录、是否探通。 */
data class SourceRow(
    val key: String,
    val name: String,
    val host: String,
    val line: String,
    val label: String,
    val note: String,
    val canLogin: Boolean = false,
    val logged: Boolean = false,
    val needLogin: Boolean = false,
    /** 探测确认所有通道都不通 */
    val offline: Boolean = false,
    /** 探测选中过（false = 还在检测中） */
    val picked: Boolean = false,
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

fun buildRows(srcs: JSONArray, st: JSONArray): List<SourceRow> {
    val byKey = HashMap<String, JSONObject>()
    for (i in 0 until st.length()) st.optJSONObject(i)?.let { byKey[it.optString("key")] = it }
    val out = mutableListOf<SourceRow>()
    for (i in 0 until srcs.length()) {
        val s = srcs.optJSONObject(i) ?: continue
        val key = s.optString("key")
        val r = byKey[key]
        val line = r?.optString("line") ?: ""
        // pick=false 说明 ci/host 还只是线路表里的默认值，不是探测结果。
        // 旧代码直接把默认值当「直连」显示，于是出现「标着直连、其实打的是被墙域名」。
        val picked = r?.optBoolean("pick") == true
        val offline = r?.optBoolean("offline") == true
        out += SourceRow(
            key = key,
            name = s.optString("name", key),
            host = r?.optString("host") ?: "",
            line = line,
            label = when {
                offline -> "不通"
                !picked -> "检测中"
                line == "direct" -> "直连"
                line == "proxy" -> "需 VPN"
                else -> ""
            },
            note = r?.optString("note") ?: "",
            canLogin = s.optBoolean("canLogin"),
            logged = s.optBoolean("logged"),
            needLogin = s.optBoolean("needLogin"),
            offline = offline,
            picked = picked,
        )
    }
    return out
}

/** 按状态给一行中文小结，首页/我的页直接用。 */
fun routeSummary(rows: List<SourceRow>): String {
    if (rows.isEmpty()) return "还没有源数据"
    val direct = rows.count { it.picked && !it.offline && it.line == "direct" }
    val proxy = rows.count { it.picked && !it.offline && it.line == "proxy" }
    val off = rows.count { it.offline }
    val probing = rows.count { !it.picked && !it.offline }
    return buildString {
        append("$direct 个直连")
        if (proxy > 0) append(" · $proxy 个需 VPN")
        if (off > 0) append(" · $off 个不通")
        if (probing > 0) append(" · $probing 个检测中")
    }
}

fun routeTone(rows: List<SourceRow>): Tone = when {
    rows.isEmpty() -> Tone.PLAIN
    rows.count { it.offline } >= rows.size -> Tone.BAD
    rows.count { it.picked && !it.offline } >= rows.size - rows.count { it.offline } -> Tone.OK
    else -> Tone.WARN
}

/**
 * 选来请求内容的源。
 *
 * 还没探测过的源也要放进来（排最后）：新装第一次打开时线路表里本来就是直连默认值，
 * 这时候如果不给请求，发现页会空白，用户会以为整个 App 坏了。
 * 探测只影响「排序」和「标不通就跳过」，不影响可用性。
 */
fun pickableSources(rows: List<SourceRow>): List<SourceRow> =
    rows.filter { !it.offline }
        .sortedBy {
            // sortedBy 是稳定排序，同档内保持 routes.json 里的原顺序
            when {
                it.picked && it.line == "direct" -> 0
                it.picked && it.line == "proxy" -> 1
                it.picked -> 2
                else -> 3
            }
        }

/** 源抛出来的网络错误太生硬（"HTTP 0"），换成用户能照做的说法。 */
fun friendlyError(e: String): String {
    val low = e.lowercase()
    return when {
        low.contains("status code: 0") || e.contains("HTTP 0") ||
            low.contains("请求失败: 0") || low.contains("timed out") || low.contains("timeout") ->
            "连不上这个源。回源管理点「重新检测」，看它标「直连」还是「需 VPN」——" +
                "标「需 VPN」就要先开代理。"
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

/** 跨源聚合：多源优先排在前面，其余保持原顺序。 */
fun groupWorks(list: List<ComicRow>): List<Work> {
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
    val multi = out.filter { it.entries.size > 1 }.sortedByDescending { it.entries.size }
    val single = out.filter { it.entries.size == 1 }
    return multi + single
}
