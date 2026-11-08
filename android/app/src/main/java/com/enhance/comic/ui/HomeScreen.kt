package com.enhance.comic.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

/** 首页跨 Tab 状态（Tab 是 when 渲染的，放页面里会随切走一起丢）。 */
class HomeState {
    var rows by mutableStateOf<List<SourceRow>>(emptyList())
    var hot by mutableStateOf<List<Work>>(emptyList())
    var hotLabel by mutableStateOf("")
    var busy by mutableStateOf(true)
    /** 已经为哪个 sourceRev 拉过；切 Tab 回来不重拉 */
    var settledRev by mutableIntStateOf(-1)
    var settledVpn by mutableStateOf(false)

    // ── 排行榜（各源单独）──
    var rankSrc by mutableStateOf("jm")          // 当前排行源（默认禁漫）
    var rankTab by mutableIntStateOf(0)          // 0 今日 / 1 本周 / 2 月 / 3 总
    var rankSections by mutableStateOf<Map<String, List<ExploreSection>>>(emptyMap())
    var rankBusy by mutableStateOf(false)
    var rankError by mutableStateOf<String?>(null)
}

/** 排行周期 → explore 分区标题的关键字匹配表。匹配不到时「总」兜底用第一个分区。 */
private val RANK_KEYS = listOf(
    listOf("日", "今日", "daily"),
    listOf("週", "周", "week"),
    listOf("月", "month"),
    listOf("總", "总", "排行", "rank", "人气", "人氣", "hot"),
)
internal val RANK_TAB_NAMES = listOf("今日", "本周", "月", "总")

fun matchRankSection(secs: List<ExploreSection>, tab: Int): ExploreSection? {
    if (secs.isEmpty()) return null
    val pats = RANK_KEYS[tab.coerceIn(0, RANK_KEYS.size - 1)]
    for (sec in secs) {
        val t = sec.title.lowercase()
        if (pats.any { t.contains(it.lowercase()) }) return sec
    }
    return if (tab == 3) secs.first() else null
}

/**
 * 首页（v4 简洁版）：品牌头 + 今日热门横排 + 各源单独排行榜。
 * 排行榜数据 = 选中源 explore 的分区里按周期关键字匹配（匹配不到显示空态，不造假）。
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

    // 今日热门（保留 1.9.54 的三源并发/缓存/冷却逻辑）
    LaunchedEffect(sourceRev) {
        val vpnNow = ExploreCache.vpnOn()
        if (state.settledRev == sourceRev && state.settledVpn == vpnNow && state.hot.isNotEmpty()) {
            return@LaunchedEffect
        }
        state.busy = true
        try {
            state.rows = buildRows(engine.sources(), ui.disabledSources(), ui.sourceOrder())
            // 排行源失效了（被关掉）就退回第一个可用源
            if (state.rows.none { it.key == state.rankSrc && it.enabled }) {
                state.rankSrc = state.rows.firstOrNull { it.enabled }?.key ?: "jm"
            }
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

    // 排行榜：选中源变化/刷新时拉它的 explore（复用发现页的单源缓存）
    LaunchedEffect(state.rankSrc, state.rankTab, state.rankSections.size, sourceRev) {
        val key = state.rankSrc
        if (key.isBlank()) return@LaunchedEffect
        if (state.rankSections[key] != null) return@LaunchedEffect   // 已拉过，chips 切换即时显示
        val cached = ExploreCache.getSections(exploreSig(key, ""))
        if (cached != null && cached.sections.isNotEmpty()) {
            state.rankSections = state.rankSections + (key to cached.sections)
            return@LaunchedEffect
        }
        state.rankBusy = true
        state.rankError = null
        val r = kotlinx.coroutines.withTimeoutOrNull(15_000) { engine.explore(key, 0) }
        if (r != null && r.optBoolean("ok")) {
            val secs = parseSections(r.optJSONObject("data"), key, key)
                .filter { it.comics.isNotEmpty() }
            if (secs.isNotEmpty()) {
                ExploreCache.putSections(exploreSig(key, ""), secs, 0)
                state.rankSections = state.rankSections + (key to secs)
            } else {
                state.rankError = "这个源没有返回内容。"
            }
        } else {
            state.rankError = "这个源没返回内容（可能需要代理或登录）。"
        }
        state.rankBusy = false
    }

    val rankMatched = matchRankSection(state.rankSections[state.rankSrc] ?: emptyList(), state.rankTab)
    val rankSrcName = state.rows.firstOrNull { it.key == state.rankSrc }?.name
        ?: state.rankSrc

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

        // ── 排行榜（各源单独）──
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                "排行榜",
                more = "$rankSrcName · ${RANK_TAB_NAMES[state.rankTab]}",
                inset = false,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(state.rows.filter { it.enabled }, key = { "r" + it.key }) { r ->
                    Chip(r.name, state.rankSrc == r.key) {
                        if (state.rankSrc != r.key) {
                            state.rankSrc = r.key
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(RANK_TAB_NAMES.size) { i ->
                    Chip(RANK_TAB_NAMES[i], state.rankTab == i) {
                        if (state.rankTab != i) state.rankTab = i
                    }
                }
            }
        }

        when {
            state.rankBusy -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox(180.dp) }
            state.rankError != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    state.rankError!!,
                    color = c.fg3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            rankMatched != null -> itemsIndexed(rankMatched.comics, key = { _, it -> it.id }) { i, comic ->
                RankCell(
                    index = i,
                    cover = comic.cover,
                    title = comic.title,
                    sub = comic.sub,
                    seed = comic.title.hashCode(),
                    onClick = { onOpenWork(comic.asWork()) },
                )
            }
            else -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "这个源没有「${RANK_TAB_NAMES[state.rankTab]}」的排行数据，换一个周期或源试试。",
                    color = c.fg3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(8.dp)) }
    }
}
