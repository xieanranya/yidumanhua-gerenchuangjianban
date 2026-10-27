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
import kotlinx.coroutines.launch
import com.enhance.comic.data.UiStore
import com.enhance.comic.model.Book

/**
 * 首页：搜索入口 + 继续阅读 + 快捷入口 + 热门推荐 + 源状态。
 * 「热门推荐」是真的去问源要一页首页，拿不到就整块不显示，不留假数据。
 */
@Composable
fun HomeScreen(
    books: List<Book>,
    engine: SourceEngine,
    ui: UiStore,
    sourceRev: Int = 0,
    onOpenBook: (Book) -> Unit,
    onDiscover: () -> Unit,
    onSourceManager: () -> Unit,
    onImport: () -> Unit,
    onOpenWork: (Work) -> Unit,
) {
    val c = LocalColors.current

    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var hot by remember { mutableStateOf<List<Work>>(emptyList()) }
    var hotLabel by remember { mutableStateOf("") }
    var hotBusy by remember { mutableStateOf(true) }

    LaunchedEffect(sourceRev) {
        hotBusy = true
        runCatching {
            rows = buildRows(engine.sources(), ui.disabledSources(), ui.sourceOrder())
            // 取前三个**开着的**源合起来聚一次，同一本在多个源出现就显示「N 源」
            val targets = rows.filter { it.enabled }.take(3)
            val sig = "home|" + targets.joinToString(",") { it.key }

            // 3 分钟内刚拉过同一批源：直接用上次的，切回首页不再等一遍
            val cached = ExploreCache.get(sig)
            if (cached != null) {
                hot = cached.works.take(12)
                hotLabel = if (cached.ok > 0) "${cached.ok} 个源" else ""
                hotBusy = false
                return@runCatching
            }

            // 流式：哪个源先回来就先上屏，别为最慢的那个把整页按住
            val pool = mutableListOf<ComicRow>()
            var hit = 0
            var done = 0
            kotlinx.coroutines.coroutineScope {
                targets.forEach { s ->
                    launch {
                        val got = kotlinx.coroutines.withTimeoutOrNull(12_000) { engine.explore(s.key, 0) }
                        done += 1
                        if (got != null && got.optBoolean("ok")) {
                            val secs = parseSections(got.optJSONObject("data"), s.key, s.name)
                            val list = secs.flatMap { it.comics }
                            if (list.isNotEmpty()) {
                                hit += 1
                                pool += list
                                hot = groupWorks(pool.toList(), sort = false).take(12)
                                hotLabel = "$hit 个源"
                                if (hotBusy) hotBusy = false
                            }
                        }
                        if (done == targets.size) {
                            hot = groupWorks(pool.toList()).take(12)
                            hotLabel = if (hit > 0) "$hit 个源" else ""
                            hotBusy = false
                            ExploreCache.put(sig, groupWorks(pool.toList()), hit, targets.size - hit)
                        }
                    }
                }
            }
        }
        hotBusy = false
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

        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("快速入口", inset = false) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                QuickEntry(
                    glyph = Glyph.DISCOVER,
                    label = "发现",
                    sub = if (rows.isEmpty()) "全部源" else "${rows.count { it.enabled }} 个源",
                    onClick = onDiscover,
                    modifier = Modifier.weight(1f),
                )
                QuickEntry(
                    glyph = Glyph.SHELF,
                    label = "本地导入",
                    sub = "ZIP / PDF",
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                )
                QuickEntry(
                    glyph = Glyph.GEAR,
                    label = "源管理",
                    sub = if (rows.isEmpty()) "读取中" else "${rows.count { it.enabled }} 个源",
                    onClick = onSourceManager,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (hot.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("热门推荐", more = hotLabel, onMore = onDiscover, inset = false)
            }
            items(hot, key = { it.norm }) { work ->
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
            Box(Modifier.height(if (hot.isEmpty() && hotBusy) 60.dp else 8.dp))
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader("漫画源", more = "管理", onMore = onSourceManager, inset = false)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                NoteBar(
                    text = sourceSummary(rows),
                    tone = Tone.PLAIN,
                    inset = false,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "共 ${books.size} 本本地漫画 · 画质增强 ${if (reading != null) "在读" else "待导入"}",
                    color = c.fg3,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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
