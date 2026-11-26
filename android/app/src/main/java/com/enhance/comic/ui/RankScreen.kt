package com.enhance.comic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.SourceEngine
import com.enhance.comic.data.UiStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

/** 排行榜跨 Tab 状态（Tab 是 when 渲染的，放页面里会随切走一起丢）。 */
class RankState {
    var rows by mutableStateOf<List<SourceRow>>(emptyList())
    var rankSrc by mutableStateOf("")
    var rankOptIdx by mutableIntStateOf(0)
    /** 源 key → 排行选项 [{value,text}] */
    var rankOptions by mutableStateOf<Map<String, List<Map<String, String>>>>(emptyMap())
    /** "key|value" → 榜单 */
    var rankComics by mutableStateOf<Map<String, List<ComicRow>>>(emptyMap())
    var rankBusy by mutableStateOf(false)
    var rankError by mutableStateOf<String?>(null)
    /** 已经为哪个 sourceRev 探测过选项；切 Tab 回来不重探 */
    var settledRev by mutableIntStateOf(-1)
}

/**
 * 排行榜（一级 Tab）：各源网站自己的排行 —— 源 chips + 周期 chips（日/周/月…）+ 榜单。
 * 引擎 rankOptions 探测，没有排行接口的源不显示，不硬凑。
 */
@Composable
fun RankScreen(
    engine: SourceEngine,
    ui: UiStore,
    state: RankState,
    sourceRev: Int = 0,
    onOpenWork: (Work) -> Unit,
) {
    val c = LocalColors.current

    // 探测每个源的排行选项（同步调用，无网络）；默认选第一个有排行的源（jm 优先）
    LaunchedEffect(sourceRev) {
        if (state.settledRev == sourceRev && state.rankOptions.isNotEmpty()) return@LaunchedEffect
        val opts = LinkedHashMap<String, List<Map<String, String>>>()
        for (row in buildRows(engine.sources(), ui.disabledSources(), ui.sourceOrder())
            .filter { it.enabled }) {
            val arr = runCatching { engine.rankOptions(row.key) }.getOrElse { JSONArray() }
            if (arr.length() > 0) {
                val list = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        mapOf("value" to o.optString("value"), "text" to o.optString("text"))
                    }
                }
                if (list.isNotEmpty()) opts[row.key] = list
            }
        }
        state.rankOptions = opts
        state.rows = buildRows(engine.sources(), ui.disabledSources(), ui.sourceOrder())
        if (state.rankSrc.isBlank() || opts[state.rankSrc] == null) {
            state.rankSrc = opts.keys.firstOrNull { it == "jm" }
                ?: opts.keys.firstOrNull() ?: ""
        }
        state.rankOptIdx = 0
        state.settledRev = sourceRev
    }

    val rankOpts = state.rankOptions[state.rankSrc] ?: emptyList()
    val rankOptValue = rankOpts.getOrNull(state.rankOptIdx)?.get("value") ?: ""
    val rankSrcName = state.rows.firstOrNull { it.key == state.rankSrc }?.name ?: state.rankSrc

    // 拉当前源当前周期的榜单（缓存 key = 源|周期）
    LaunchedEffect(state.rankSrc, state.rankOptIdx, state.rankOptions.size, sourceRev) {
        if (state.rankSrc.isBlank() || rankOptValue.isBlank()) return@LaunchedEffect
        val cacheKey = state.rankSrc + "|" + rankOptValue
        if (state.rankComics[cacheKey] != null) return@LaunchedEffect
        state.rankBusy = true
        state.rankError = null
        val r = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
                engine.rank(state.rankSrc, rankOptValue, 1)
            }
        }.getOrElse { null }
        val ok = r?.optBoolean("ok") == true
        val comics = if (ok) {
            parseRankComics(r!!.optJSONObject("data"), state.rankSrc, rankSrcName)
        } else emptyList()
        state.rankComics = state.rankComics + (cacheKey to comics)
        if (!ok) {
            state.rankError = r?.optString("error")?.takeIf { it.isNotBlank() } ?: "拉取失败"
        }
        state.rankBusy = false
    }

    val rankMatched = state.rankComics[state.rankSrc + "|" + rankOptValue] ?: emptyList()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(GridGapV),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BrandHeader(
                title = "排行榜",
                sub = if (rankSrcName.isNotBlank()) "各源官方排行 · 当前 $rankSrcName" else "各源官方排行",
            )
        }

        if (state.rankSrc.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox(60.dp) }
        } else {
            // 源 chips：只有真有排行接口的源出现
            item(span = { GridItemSpan(maxLineSpan) }) {
                val rankable = state.rows.filter { (state.rankOptions[it.key] ?: emptyList()).isNotEmpty() }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(rankable.size) { idx ->
                        val r = rankable[idx]
                        Chip(r.name, state.rankSrc == r.key) {
                            if (state.rankSrc != r.key) {
                                state.rankSrc = r.key
                                state.rankOptIdx = 0
                            }
                        }
                    }
                }
            }
            // 周期 chips：来自源自己的排行选项（今天/本周/本月…）
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(rankOpts.size) { i ->
                        Chip(rankOpts[i]["text"] ?: "", state.rankOptIdx == i) {
                            if (state.rankOptIdx != i) state.rankOptIdx = i
                        }
                    }
                }
            }

            when {
                state.rankBusy -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox(180.dp) }
                state.rankError != null && rankMatched.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        state.rankError!!,
                        color = c.fg3, fontSize = 11.5.sp,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
                rankMatched.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "这个榜现在没有数据（源站还没更新），换一个周期或源试试。",
                        color = c.fg3, fontSize = 11.5.sp,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
                else -> itemsIndexed(rankMatched, key = { _, it -> it.id }) { i, comic ->
                    RankCell(
                        index = i,
                        cover = comic.cover,
                        title = comic.title,
                        sub = comic.sub,
                        seed = comic.title.hashCode(),
                        onClick = { onOpenWork(comic.asWork()) },
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(8.dp)) }
    }
}

/** rank 返回的 comics → ComicRow（引擎已解析成 {id,title,cover,subtitle}） */
internal fun parseRankComics(data: org.json.JSONObject?, sourceKey: String, sourceName: String): List<ComicRow> {
    data ?: return emptyList()
    val arr = data.optJSONArray("comics") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o ->
            val id = o.optString("id")
            if (id.isBlank()) null else ComicRow(
                id,
                o.optString("title"),
                o.optString("cover"),
                o.optString("subTitle", "").ifBlank { o.optString("subtitle") },
                sourceKey,
                sourceName,
            )
        }
    }
}
