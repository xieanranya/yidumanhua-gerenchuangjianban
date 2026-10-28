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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.SourceEngine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.enhance.comic.data.UiStore
import com.enhance.comic.model.Book

/** 首页跨 Tab 状态（同 DiscoverState 的理由：Tab 是 when 渲染的，放页面里会随切走一起丢）。 */
class HomeState {
    var rows by mutableStateOf<List<SourceRow>>(emptyList())
    var hot by mutableStateOf<List<Work>>(emptyList())
    var hotLabel by mutableStateOf("")
    var busy by mutableStateOf(true)
    /** 已经为哪个 sourceRev 拉过；切 Tab 回来不重拉 */
    var settledRev by mutableIntStateOf(-1)
    var settledVpn by mutableStateOf(false)
}

/**
 * 首页：只留搜索入口 + 继续阅读 + 热门推荐（越简洁越好，入口都挪到下面的 Tab 里）。
 * 「热门推荐」是真的去问源要一页首页，拿不到就整块不显示，不留假数据。
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

    // 首页推荐的状态放在 Activity 级（见 HomeState）；本地只留「本地书架」相关的。
    LaunchedEffect(sourceRev) {
        // 切 Tab 回来：同一个 sourceRev、同一个网络环境、内容还在，直接跳过
        val vpnNow = ExploreCache.vpnOn()
        if (state.settledRev == sourceRev && state.settledVpn == vpnNow && state.hot.isNotEmpty()) {
            return@LaunchedEffect
        }
        state.busy = true
        try {
            state.rows = buildRows(engine.sources(), ui.disabledSources(), ui.sourceOrder())
            // 取前三个**开着的**源：同一本在多个源出现就显示「N 源」
            val targets = state.rows.filter { it.enabled }.take(3)
            if (targets.isEmpty()) {
                state.hot = emptyList()
                state.hotLabel = ""
                return@LaunchedEffect
            }

            // 1) 先拿缓存（和发现页共用同一份单源缓存），一进首页就有内容
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

            // 2) 只刷过期/没缓存的源。串行 —— 同步桥上并发等于排队，还会让后面的源被超时误杀
            // 缓存新鲜的、以及「连续失败还在冷却期」的都先不跑：首页只放三个源，
            // 里面要是有连不上的，光等超时就能让首页空着半天。
            val runnable = targets.filter { !ExploreCache.isCooling(it.key) }
            val need = (if (runnable.isEmpty()) targets.take(1) else runnable)
                .filter { hits[it.key]?.fresh != true }
                .sortedBy { s ->
                    ExploreCache.cost(exploreSig(s.key, "")).let { if (it <= 0) 5_000L else it }
                }
            for (s in need) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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

            // 3) 用每源最新缓存重组
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

    val reading = remember(books) {
        books.filter { it.lastPage > 0 }
            .maxByOrNull { if (it.lastReadAt > 0) it.lastReadAt else it.addedAt }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(GridGapV),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BigTitle("首页", inset = false)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(bottom = 4.dp)) {
                SearchStub("搜漫画名、作者、题材", onClick = { onDiscover() }, inset = false)
            }
        }

        if (reading != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("继续阅读", more = "书架", onMore = onDiscover, inset = false)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                val progress = if (reading.pageCount > 0) {
                    "第 ${reading.lastPage + 1} / ${reading.pageCount} 页"
                } else {
                    "${reading.pageCount} 页"
                }
                ContinueCard(
                    seed = reading.title.hashCode(),
                    title = reading.title,
                    meta = "$progress · 本地",
                    action = "继续",
                    onClick = { onOpenBook(reading) },
                    coverBitmap = rememberFileCover(reading.coverPath),
                    inset = false,
                )
            }
        }

        if (state.hot.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("热门推荐", more = state.hotLabel, onMore = onDiscover, inset = false)
            }
            items(state.hot, key = { it.norm }) { work ->
                ComicCell(
                    cover = work.cover,
                    title = work.title,
                    sub = if (work.entries.size > 1) work.sourceNames else work.primary.sourceName,
                    seed = work.norm.hashCode(),
                    badge = work.badge,
                    onClick = { onOpenWork(work) },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.height(if (state.hot.isEmpty() && state.busy) 60.dp else 8.dp))
        }

    }
}

@Composable
private fun QuickEntry(
    glyph: Glyph,
    label: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(c.card2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        GlyphView(glyph, c.fg2, 16.dp)
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            color = c.fg,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            sub,
            color = c.fg3,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
