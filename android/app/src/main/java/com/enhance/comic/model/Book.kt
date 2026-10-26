package com.enhance.comic.model

import org.json.JSONArray
import org.json.JSONObject

enum class BookType { ZIP, RAR, PDF, FOLDER }

enum class ReadingMode { PAGED, VERTICAL, HORIZONTAL }

/** How a paged image is scaled into the viewport before the finger's own zoom applies. */
enum class ScaleMode(val label: String) {
    FIT("适应屏幕"),
    WIDTH("适应宽度"),
    HEIGHT("适应高度"),
    ORIGINAL("原始尺寸"),
}

/** What a tap in one of the three screen zones does. */
enum class TapAction(val label: String) {
    PREV_PAGE("上一页"),
    NEXT_PAGE("下一页"),
    PREV_CHAPTER("上一话"),
    NEXT_CHAPTER("下一话"),
    TOGGLE_CHROME("显示 / 隐藏栏"),
    OPEN_MENU("打开菜单"),
    NONE("无"),
}

/** Zoom resampling filter, mirroring the "smooth filters" of classic comic readers. */
enum class ScaleFilter(val label: String) {
    NEAREST("最近邻"),
    BILINEAR("双线性"),
    BICUBIC("双三次"),
    LANCZOS3("Lanczos3"),
    ;

    companion object {
        fun fromOrdinal(i: Int): ScaleFilter = entries.getOrElse(i) { BICUBIC }
    }
}

data class Book(
    val id: String,
    val title: String,
    val type: BookType,
    /** Absolute file path for ZIP/PDF, tree document URI string for FOLDER. */
    val path: String,
    val pageCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPage: Int = 0,
    /** 最后一次退出阅读器的时间，首页「继续阅读」按它排序，没有就跟 addedAt 走。 */
    val lastReadAt: Long = 0,
    val bookmarks: List<Int> = emptyList(),
    /** Optional user-given name per bookmarked source page index. */
    val bookmarkNames: Map<Int, String> = emptyMap(),
    /** Absolute path of the cached cover jpeg, empty when none has been generated yet. */
    val coverPath: String = "",
    /** 原始容器标签（如 "7Z" / "TAR"）：7z、tar 会解包成文件夹入库，界面上仍显示原格式。 */
    val sourceLabel: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type.name)
        put("path", path)
        put("pageCount", pageCount)
        put("addedAt", addedAt)
        put("lastPage", lastPage)
        put("lastReadAt", lastReadAt)
        put("bookmarks", JSONArray(bookmarks))
        put("bookmarkNames", JSONObject().apply {
            bookmarkNames.forEach { (page, name) -> put(page.toString(), name) }
        })
        put("coverPath", coverPath)
        put("sourceLabel", sourceLabel)
    }

    companion object {
        fun fromJson(o: JSONObject): Book = Book(
            id = o.getString("id"),
            title = o.getString("title"),
            type = runCatching { BookType.valueOf(o.getString("type")) }.getOrDefault(BookType.ZIP),
            path = o.getString("path"),
            pageCount = o.optInt("pageCount"),
            addedAt = o.optLong("addedAt"),
            lastPage = o.optInt("lastPage"),
            lastReadAt = o.optLong("lastReadAt"),
            bookmarks = buildList {
                val arr = o.optJSONArray("bookmarks") ?: JSONArray()
                for (i in 0 until arr.length()) add(arr.getInt(i))
            },
            bookmarkNames = buildMap {
                val obj = o.optJSONObject("bookmarkNames") ?: JSONObject()
                obj.keys().forEach { key ->
                    key.toIntOrNull()?.let { put(it, obj.optString(key)) }
                }
            },
            coverPath = o.optString("coverPath"),
            sourceLabel = o.optString("sourceLabel"),
        )
    }
}

/**
 * Per-pass strength. Amounts are 1..100 and are mapped to the shader ranges:
 * sharpen 0.025..2.5, denoise blend 0.05..1.0, contrast blend 0.1..1.0.
 */
data class EnhanceSettings(
    val sharpen: Boolean = false,
    val sharpenAmount: Int = 100,
    val denoise: Boolean = false,
    val denoiseRadius: Int = 2,
    val denoiseBlend: Int = 100,
    val contrastPass: Boolean = false,
    val contrastBlend: Int = 100,
    val scaleFilter: ScaleFilter = ScaleFilter.BICUBIC,
    val brightness: Int = 100,
    val contrastLevel: Int = 100,
    val gamma: Int = 100,
) {
    val sharpenF: Float get() = sharpenAmount / 100f * 2.5f
    val denoiseBlendF: Float get() = 0.05f + denoiseBlend / 100f * 0.95f
    val contrastBlendF: Float get() = 0.1f + contrastBlend / 100f * 0.9f
    val brightnessF: Float get() = brightness / 100f
    val contrastLevelF: Float get() = contrastLevel / 100f
    val gammaF: Float get() = (gamma / 100f).coerceAtLeast(0.05f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("sharpen", sharpen)
        put("sharpenAmount", sharpenAmount)
        put("denoise", denoise)
        put("denoiseRadius", denoiseRadius)
        put("denoiseBlend", denoiseBlend)
        put("contrastPass", contrastPass)
        put("contrastBlend", contrastBlend)
        put("scaleFilter", scaleFilter.ordinal)
        put("brightness", brightness)
        put("contrastLevel", contrastLevel)
        put("gamma", gamma)
    }

    companion object {
        fun fromJson(o: JSONObject): EnhanceSettings = EnhanceSettings(
            sharpen = o.optBoolean("sharpen"),
            sharpenAmount = o.optInt("sharpenAmount", 100),
            denoise = o.optBoolean("denoise"),
            denoiseRadius = o.optInt("denoiseRadius", 2),
            denoiseBlend = o.optInt("denoiseBlend", 100),
            contrastPass = o.optBoolean("contrastPass"),
            contrastBlend = o.optInt("contrastBlend", 100),
            scaleFilter = ScaleFilter.fromOrdinal(o.optInt("scaleFilter", ScaleFilter.BICUBIC.ordinal)),
            brightness = o.optInt("brightness", 100),
            contrastLevel = o.optInt("contrastLevel", 100),
            gamma = o.optInt("gamma", 100),
        )
    }
}

/** Display / layout preferences that are not per-pass. */
data class ReaderPrefs(
    val readingMode: ReadingMode = ReadingMode.PAGED,
    val rightToLeft: Boolean = true,
    val splitWidePages: Boolean = true,
    val autoCropWhiteBorders: Boolean = false,
    val volumeKeyPaging: Boolean = true,
    /** Shows two pages side by side in paged mode when the screen is landscape. */
    val doublePage: Boolean = false,
    /** Two pages even in portrait (页面布局 → 双页). */
    val forceSpread: Boolean = false,
    /** Gap between pages in the scroll modes, percent of the viewport. */
    val pageGapPercent: Int = 0,
    // ---- 滤镜 / 显示
    val grayscale: Boolean = false,
    val autoContrast: Boolean = false,
    val invert: Boolean = false,
    /** 蓝光过滤强度 0–100，0 为关闭。旧版本存的是布尔开关，读到 true 时映射成 55。 */
    val blueLight: Int = 0,
    // ---- 设置
    val keepScreenOn: Boolean = true,
    val autoBrightness: Boolean = true,
    val brightness: Int = 50,
    /** 0 = 纯黑, 1 = 深灰, 2 = 米白 */
    val background: Int = 0,
    val doubleTapZoom: Boolean = true,
    /** 浅色栏体（原版的白色工具栏）；false 为深色。 */
    val chromeLight: Boolean = false,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val tapLeftAction: TapAction = TapAction.PREV_PAGE,
    val tapCenterAction: TapAction = TapAction.TOGGLE_CHROME,
    val tapRightAction: TapAction = TapAction.NEXT_PAGE,
    /** 音量键反向：音量上 = 下一页。 */
    val volumeKeyInvert: Boolean = false,
    /** 长按音量键连续翻页。 */
    val volumeKeyRepeat: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("readingMode", readingMode.name)
        put("rightToLeft", rightToLeft)
        put("splitWidePages", splitWidePages)
        put("autoCropWhiteBorders", autoCropWhiteBorders)
        put("volumeKeyPaging", volumeKeyPaging)
        put("doublePage", doublePage)
        put("forceSpread", forceSpread)
        put("pageGapPercent", pageGapPercent)
        put("grayscale", grayscale)
        put("autoContrast", autoContrast)
        put("invert", invert)
        put("blueLight", blueLight)
        put("keepScreenOn", keepScreenOn)
        put("autoBrightness", autoBrightness)
        put("brightness", brightness)
        put("background", background)
        put("doubleTapZoom", doubleTapZoom)
        put("chromeLight", chromeLight)
        put("scaleMode", scaleMode.name)
        put("tapLeftAction", tapLeftAction.name)
        put("tapCenterAction", tapCenterAction.name)
        put("tapRightAction", tapRightAction.name)
        put("volumeKeyInvert", volumeKeyInvert)
        put("volumeKeyRepeat", volumeKeyRepeat)
    }

    companion object {
        fun fromJson(o: JSONObject): ReaderPrefs = ReaderPrefs(
            readingMode = runCatching { ReadingMode.valueOf(o.optString("readingMode")) }
                .getOrDefault(ReadingMode.PAGED),
            rightToLeft = o.optBoolean("rightToLeft", true),
            splitWidePages = o.optBoolean("splitWidePages", true),
            autoCropWhiteBorders = o.optBoolean("autoCropWhiteBorders"),
            volumeKeyPaging = o.optBoolean("volumeKeyPaging", true),
            doublePage = o.optBoolean("doublePage"),
            forceSpread = o.optBoolean("forceSpread"),
            pageGapPercent = o.optInt("pageGapPercent"),
            grayscale = o.optBoolean("grayscale"),
            autoContrast = o.optBoolean("autoContrast"),
            invert = o.optBoolean("invert"),
            blueLight = run {
                // 旧版本存的是 true / false，不能直接 optInt（会静默变成 0 而丢掉设置）。
                val raw: Any? = o.opt("blueLight")
                when {
                    raw is Boolean -> if (raw) 55 else 0
                    raw is Number -> raw.toInt().coerceIn(0, 100)
                    else -> 0
                }
            },
            keepScreenOn = o.optBoolean("keepScreenOn", true),
            autoBrightness = o.optBoolean("autoBrightness", true),
            brightness = o.optInt("brightness", 50),
            background = o.optInt("background"),
            doubleTapZoom = o.optBoolean("doubleTapZoom", true),
            chromeLight = o.optBoolean("chromeLight"),
            scaleMode = runCatching { ScaleMode.valueOf(o.optString("scaleMode")) }
                .getOrDefault(ScaleMode.FIT),
            tapLeftAction = tapAction(o, "tapLeftAction", TapAction.PREV_PAGE),
            tapCenterAction = tapAction(o, "tapCenterAction", TapAction.TOGGLE_CHROME),
            tapRightAction = tapAction(o, "tapRightAction", TapAction.NEXT_PAGE),
            volumeKeyInvert = o.optBoolean("volumeKeyInvert"),
            volumeKeyRepeat = o.optBoolean("volumeKeyRepeat"),
        )

        private fun tapAction(o: JSONObject, key: String, fallback: TapAction): TapAction =
            runCatching { TapAction.valueOf(o.optString(key)) }.getOrDefault(fallback)
    }
}

/** One page as displayed: an index into the source, optionally cropped to half of it. */
data class PageRef(val index: Int, val half: Int = 0) {
    val isSplit get() = half != 0
}
