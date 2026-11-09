package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.SourceEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.enhance.comic.data.UiStore
import com.enhance.comic.model.Book
import org.json.JSONArray

/** 首页跨 Tab 状态（Tab 是 when 渲染的，放页面里会随切走一起丢）。 */
class HomeState {
    var rows by mutableStateOf<List<SourceRow>>(emptyList())
    var hot by mutableStateOf<List<Work>>(emptyList())
    var hotLabel by mutableStateOf("")
    var busy by mutableStateOf(true)
    /** 已经为哪个 sourceRev 拉过；切 Tab 回来不重拉 */
    var settledRev by mutableIntStateOf(-1)
    var settledVpn by mutableStateOf(false)

    // ── 排行榜（各源网站自己的排行，rankOptions 探测到的源才显示）──
    var rankSrc by mutableStateOf("")
    var rankOptIdx by mutableIntStateOf(0)
    /** 源 key → 排行选项 [{value,text}] */
    var rankOptions by mutableStateOf<Map<String, List<Map<String, String>>>>(emptyMap())
    /** "key|value" → 榜单 */
    var rankComics by mutableStateOf<Map<String, List<ComicRow>>>(emptyMap())
    var rankBusy by mutableStateOf(false)
    var rankError by mutableStateOf<String?>(null)
}

/**
 * 首页（v4 简洁版）：品牌头 + 今日热门横排 + 各源排行榜。
 * 排行榜 = 源网站自己的排行（引擎 rankOptions 探测，没有排行接口的源不显示，不硬凑）。
 */
@Composable
fun HomeScreen(
    books: List<Book>,
    engine: SourceEngine,
    ui: UiStore,
    state: HomeState,
    sourceRev: Int = 0,
    onOpenBook: (Book) -> Unit,
    onDiscover: () -> Unit,
    onOpenWork: (Work) -> Unit,
) {
    val c = LocalColors.current

    // 今日热门（保留三源缓存/冷却逻辑）
    LaunchedEffect(sourceRev) {
        val vpnNow = ExploreCache.vpnOn()
        if (state.settledRev == sourceRev && state.settledVpn == vpnNow && state.hot.isNotEmpty()) {
            return@LaunchedEffect
        }
        state.busy = true
        try {
            state.rows = buildRows(engine.sources(), ui.disabledSources(), ui.sourceOrder())
            val targets = state.rows.filter { it.enabled }.take(3)
            if (targets.isEmpty()) {
                state.hot = emptyList()
                state.hotLabel = ""
                return@LaunchedEffect
            }

            val hits = LinkedHashMap<String, ExploreCache.SectionHit?>()
            targets.forEach { hits[it.key] = ExploreCache.getSections(exploreSig(it.key, "")) }
            val seeded = targets.flatMap { s ->
                hits[s.key]?.sections?.flatMap { it.comics } ?: emptyList()
            }
            if (seeded.isNotEmpty()) {
                state.hot = groupWorks(seeded).take(12)
                val have = hits.count { it.value != null }
                state.hotLabel = if (have > 0) "$have 个源" else ""
                state.busy = false
            }

            val runnable = targets.filter { !ExploreCache.isCooling(it.key) }
            val need = (if (runnable.isEmpty()) targets.take(1) else runnable)
                .filter { hits[it.key]?.fresh != true }
                .sortedBy { s ->
                    ExploreCache.cost(exploreSig(s.key, "")).let { if (it <= 0) 5_000L else it }
                }
            for (s in need) {
                currentCoroutineContext().ensureActive()
                val t0 = System.currentTimeMillis()
                val r = kotlinx.coroutines.withTimeoutOrNull(12_000) { engine.explore(s.key, 0) }
                val cost = System.currentTimeMillis() - t0
                if (r != null && r.optBoolean("ok")) {
                    val secs = parseSections(r.optJSONObject("data"), s.key, s.name)
                        .filter { it.comics.isNotEmpty() }
                    if (secs.isNotEmpty()) {
                        ExploreCache.markOk(s.key)
                        ExploreCache.putSections(exploreSig(s.key, ""), secs, cost)
                    }
                } else {
                    ExploreCache.markFail(s.key)
                }
            }

            val pool = targets.flatMap { s ->
                ExploreCache.getSections(exploreSig(s.key, ""))?.sections?.flatMap { it.comics }
                    ?: emptyList()
            }
            state.hot = groupWorks(pool).take(12)
            val have = targets.count { ExploreCache.getSections(exploreSig(it.key, "")) != null }
            state.hotLabel = if (have > 0) "$have 个源" else ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 首页拿不到推荐不算错误：整块不显示就行，不要弹东西吓人
        } finally {
            state.busy = false
            state.settledRev = sourceRev
            state.settledVpn = vpnNow
        }
    }

    // 探测每个源的排行选项（同步调用，无网络）；默认选第一个有排行的源（jm 优先）
    LaunchedEffect(sourceRev) {
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
        if (state.rankSrc.isBlank() || opts[state.rankSrc] == null) {
            state.rankSrc = opts.keys.firstOrNull { it == "jm" }
                ?: opts.keys.firstOrNull() ?: ""
        }
        state.rankOptIdx = 0
    }

    val rankOpts = state.rankOptions[state.rankSrc] ?: emptyList()
    val rankOptValue = rankOpts.getOrNull(state.rankOptIdx)?.get("value") ?: ""
    val rankSrcName = state.rows.firstOrNull { it.key == state.rankSrc }?.name ?: state.rankSrc

    // 拉当前源当前周期的榜单（缓存 key = 源|周期）
    LaunchedEffect(state.rankSrc, state.rankOptIdx, state.rankOptions.size) {
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
        // 品牌头（v4）
        item(span = { GridItemSpan(maxLineSpan) }) {
            BrandHeader(
                title = "画质漫画",
                sub = "${state.rows.count { it.enabled }} 个源在用" +
                    if (state.hotLabel.isNotBlank()) " · 已连上 ${state.hotLabel}" else "",
            ) {
                Box(
                    Modifier
                        .padding(top = 8.dp, end = 14.dp)
                        .clickable { onDiscover() },
                ) { CircleToolButton(Glyph.SEARCH, onClick = { onDiscover() }) }
            }
        }

        // ── 今日热门 ──
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                "今日热门",
                more = state.hotLabel.ifBlank { null },
                onMore = if (state.hotLabel.isBlank()) null else ({ onDiscover() }),
                inset = false,
            )
        }
        if (state.busy && state.hot.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox(150.dp) }
        } else if (state.hot.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "这些源这次都没给出内容，回头再刷一次试试。",
                    color = c.fg3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.hot.size) { idx ->
                        val w = state.hot[idx]
                        Box(Modifier.width(118.dp)) {
                            ComicCell(
                                cover = w.cover,
                                title = w.title,
                                sub = if (w.entries.size > 1) w.sourceNames else w.primary.sourceName,
                                seed = w.norm.hashCode(),
                                badge = w.badge,
                                onClick = { onOpenWork(w) },
                            )
                        }
                    }
                }
            }
        }

        // ── 排行榜（各源网站自己的排行）──
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                "排行榜",
                more = if (rankSrcName.isNotBlank() && rankOpts.isNotEmpty())
                    "$rankSrcName · ${rankOpts.getOrNull(state.rankOptIdx)?.get("text") ?: ""}" else null,
                inset = false,
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
            // 周期 chips：来自源自己的排行选项
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
private fun parseRankComics(data: org.json.JSONObject?, sourceKey: String, sourceName: String): List<ComicRow> {
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
