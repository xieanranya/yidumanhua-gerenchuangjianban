package com.enhance.comic.data

import android.content.Context
import com.enhance.comic.ui.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

/** 一条在线阅读记录：同一个源的同一本书只留最新一条。 */
data class OnlineRecord(
    val source: String,
    val sourceName: String,
    val comicId: String,
    val title: String,
    val cover: String,
    val epId: String,
    val epTitle: String,
    val epIndex: Int,
    val epTotal: Int,
    val at: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("sourceName", sourceName)
        put("comicId", comicId)
        put("title", title)
        put("cover", cover)
        put("epId", epId)
        put("epTitle", epTitle)
        put("epIndex", epIndex)
        put("epTotal", epTotal)
        put("at", at)
    }

    companion object {
        fun fromJson(o: JSONObject) = OnlineRecord(
            source = o.optString("source"),
            sourceName = o.optString("sourceName"),
            comicId = o.optString("comicId"),
            title = o.optString("title"),
            cover = o.optString("cover"),
            epId = o.optString("epId"),
            epTitle = o.optString("epTitle"),
            epIndex = o.optInt("epIndex"),
            epTotal = o.optInt("epTotal"),
            at = o.optLong("at"),
        )
    }
}

/**
 * 界面偏好与在线阅读记录。跟书库一样存在 SharedPreferences 里，
 * 但单独一个键，免得改主题时把整本书库重写一遍。
 */
class UiStore(context: Context) {

    private val prefs = context.getSharedPreferences("enhance_comic", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, "")!!) }
            .getOrDefault(ThemeMode.AUTO)
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    /** 上次在哪个 Tab，启动时回到那里。 */
    var lastTab: Int
        get() = prefs.getInt(KEY_TAB, 0)
        set(value) {
            prefs.edit().putInt(KEY_TAB, value).apply()
        }

    fun records(): List<OnlineRecord> {
        val raw = prefs.getString(KEY_ONLINE, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { OnlineRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** 记录一次打开章节，同时充当「继续阅读」的指针。 */
    fun remember(record: OnlineRecord) {
        val kept = records().filterNot {
            it.source == record.source && it.comicId == record.comicId
        }
        val arr = JSONArray()
        (listOf(record) + kept).take(MAX_RECORDS).forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ONLINE, arr.toString()).apply()
    }

    fun lastChapter(source: String, comicId: String): OnlineRecord? =
        records().firstOrNull { it.source == source && it.comicId == comicId }

    fun forget(source: String, comicId: String) {
        val kept = records().filterNot { it.source == source && it.comicId == comicId }
        val arr = JSONArray()
        kept.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ONLINE, arr.toString()).apply()
    }

    fun clearRecords() {
        prefs.edit().remove(KEY_ONLINE).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_TAB = "last_tab"
        const val KEY_ONLINE = "online_records"
        const val MAX_RECORDS = 60
    }
}
