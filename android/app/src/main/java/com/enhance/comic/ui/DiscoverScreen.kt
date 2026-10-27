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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.SourceEngine
import com.enhance.comic.data.UiStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 发现页：搜索 + 选源 + 三列网格。
 *
 * 「全部」模式下把这些源的首页并发拉回来，再按**标题归一化后完全一致**的条目聚合成一张卡，
 * 卡角标显示有几个源，点进去能切源。各源没有互通的 ID，所以只做「完全一致才合并」，
 * 宁可少合也不把两本不同的书串成一本。
 *
 * 「全部」只用**用户开着**的源；关掉的源在下方 chips 里也不出现，避免点进去发现搜不到东西。
 * [sourceRev] 是源开关/顺序的版本号，改了之后靠它把这一页的请求重跑一遍。
 */
@Composable
fun DiscoverScreen(
    engine: SourceEngine,
    ui: UiStore,
    sourceRev: Int = 0,
    initialQuery: String = "",
    onOpenWork: (Work) -> Unit,
    onSourceManager: () -> Unit,
) {
    val c = LocalColors.current
    var kw by remember { mutableStateOf(initialQuery) }
    var query by remember { mutableStateOf(initialQuery) }
    var selected by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var works by remember { mutableStateOf<List<Work>>(emptyList()) }
    var sections by remember { mutableStateOf<List<ExploreSection>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var failures by remember { mutableIntStateOf(0) }
    var okCount by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // 源元数据（名字/能否登录）在一次页面生命周期里不会变，缓存住；
    // 点 chips 换源时只用缓存的这份套一遍开关，不再去问一次 JS 引擎。
    var srcs by remember { mutableStateOf<org.json.JSONArray?>(null) }

    LaunchedEffect(selected, query, tick, sourceRev) {
        busy = true
        err = null
        failures = 0
        okCount = 0
        works = emptyList()
        sections = emptyList()
        if (srcs == null) {
            runCatching { srcs = engine.sources() }
        }
        runCatching {
            rows = buildRows(srcs ?: org.json.JSONArray(), ui.disabledSources(), ui.sourceOrder())
        }

        val onRows = rows.filter { it.enabled }
        // 选中的源被关掉了：退回「全部」，不然这一页会一直停在一个已经关掉的源上
        if (selected.isNotBlank() && onRows.none { it.key == selected }) {
            selected = ""
            return@LaunchedEffect
        }

        if (selected.isBlank()) {
            val targets = onRows.take(8)
            // 3 分钟内刚拉过同一批源、同一个关键词：直接用上次的结果
            ExploreCache.get("v|" + query + "|" + targets.joinToString(",") { it.key })?.let { hit ->
                works = hit.works
                okCount = hit.ok
                failures = hit.fail
                busy = false
                err = null
                return@LaunchedEffect
            }
            if (targets.isEmpty()) {
                busy = false
                err = if (rows.isEmpty()) {
                    "还没读到源列表，稍后再试"
                } else {
                    "你把所有源都关掉了。去源管理至少打开一个。"
                }
                return@LaunchedEffect
            }
            // 流式：哪个源先回来就先上屏，不等最慢的那个。
            // 引擎的 JS 是同步桥，多个源其实在同一个线程上排队，
            // 「等全部跑完再画」= 等所有源依次跑完，用户就以为卡死了。
            val acc = ArrayList<ComicRow>()
            val cacheSig = "v|" + query + "|" + targets.joinToString(",") { it.key }
            var done = 0
            coroutineScope {
                targets.forEach { s ->
                    launch {
                        // 15 秒拿不到就放弃这个源，别让它一个人占着线程
                        val got = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                            if (query.isBlank()) {
                                val r = engine.explore(s.key, 0)
                                if (!r.optBoolean("ok")) {
                                    null
                                } else {
                                    val secs = parseSections(r.optJSONObject("data"))
                                    val list = secs.flatMap { sec ->
                                        sec.comics.map { it.copy(sourceKey = s.key, sourceName = s.name) }
                                    }
                                    list.ifEmpty { null }
                                }
                            } else {
                                val r = engine.search(s.key, query, "[]", 1)
                                if (!r.optBoolean("ok")) {
                                    null
                                } else {
                                    val list = parseComics(r.optJSONObject("data"))
                                        .map { it.copy(sourceKey = s.key, sourceName = s.name) }
                                    list.ifEmpty { null }
                                }
                            }
                        }
                        done += 1
                        if (got != null) {
                            okCount += 1
                            acc.addAll(got)
                            // sort=false：先按到达顺序摆，否则每来一个源整片格子都重排一次，看着在跳
                            works = groupWorks(acc.toList(), sort = false)
                            if (busy) busy = false
                        } else {
                            failures += 1
                        }
                        if (done == targets.size) {
                            works = groupWorks(acc.toList())
                            busy = false
                            ExploreCache.put(cacheSig, works, okCount, failures)
                            if (works.isEmpty()) {
                                err = if (query.isNotBlank()) {
                                    "没有找到「$query」"
                                } else {
                                    "这些源这次都没返回内容。可能是网站本身暂时打不开，" +
                                        "换个时间再试，或者只留几个快的源。"
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val row = rows.firstOrNull { it.key == selected }
            if (row == null) {
                busy = false
                err = "这个源不在列表里了"
                return@LaunchedEffect
            }
            val secs = kotlinx.coroutines.withTimeoutOrNull(18_000) {
                if (query.isBlank()) {
                    val r = engine.explore(row.key, 0)
                    if (r.optBoolean("ok")) {
                        parseSections(r.optJSONObject("data")).map { sec ->
                            sec.copy(
                                comics = sec.comics.map {
                                    it.copy(sourceKey = row.key, sourceName = row.name)
                                },
                            )
                        }
                    } else {
                        null
                    }
                } else {
                    val r = engine.search(row.key, query, "[]", 1)
                    if (r.optBoolean("ok")) {
                        val list = parseComics(r.optJSONObject("data"))
                            .map { it.copy(sourceKey = row.key, sourceName = row.name) }
                        if (list.isEmpty()) emptyList() else listOf(ExploreSection("搜索结果", list))
                    } else {
                        null
                    }
                }
            }
            sections = secs ?: emptyList()
            okCount = if (sections.isEmpty()) 0 else 1
            failures = if (sections.isEmpty()) 1 else 0
            busy = false
            if (sections.isEmpty()) {
                err = "这个源没返回内容。可能需要先登录，或者换个关键词。"
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BigTitle(
            text = "发现",
            sub = when {
                busy -> "正在从各源取内容…"
                selected.isBlank() -> buildString {
                    append("${works.size} 部作品 · $okCount 个源")
                    val multi = works.count { it.entries.size > 1 }
                    if (multi > 0) append(" · $multi 部是跨源同一本")
                    if (failures > 0) append(" · $failures 个源没响应")
                }
                else -> "只看这个源"
            },
        )

        SearchField(
            value = kw,
            placeholder = "搜漫画名、作者、题材",
            onValue = { kw = it },
            onSearch = { query = kw.trim() },
            onClear = { kw = ""; query = "" },
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item { Chip("全部", selected.isBlank()) { selected = "" } }
            items(rows.filter { it.enabled }, key = { it.key }) { r ->
                Chip(r.name, selected == r.key) { selected = r.key }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (failures > 0 && (works.isNotEmpty() || sections.isNotEmpty())) {
            NoteBar("$failures 个源没响应，已显示其它源的结果", Tone.WARN)
            Spacer(Modifier.height(8.dp))
        }

        when {
            busy && works.isEmpty() && sections.isEmpty() -> LoadingBox(200.dp)

            selected.isBlank() && works.isEmpty() -> EmptyBlock(err, onSourceManager)

            selected.isNotBlank() && sections.isEmpty() -> EmptyBlock(err, onSourceManager)

            selected.isBlank() -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGapV),
            ) {
                items(works, key = { it.norm }) { work ->
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

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGapV),
            ) {
                sections.forEachIndexed { si, sec ->
                    if (sec.title.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h$si") {
                            SectionHeader(
                                title = sec.title,
                                more = "${sec.comics.size} 本",
                                inset = false,
                            )
                        }
                    }
                    items(sec.comics, key = { "c$si-" + it.id }) { comic ->
                        ComicCell(
                            cover = comic.cover,
                            title = comic.title,
                            sub = comic.sub,
                            seed = comic.title.hashCode(),
                            onClick = { onOpenWork(comic.asWork()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBlock(err: String?, onSourceManager: () -> Unit) {
    Column {
        EmptyHint(
            title = "没有取到内容",
            message = friendlyError(err ?: "换个关键词，或者去源管理把慢的源先关掉。"),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MiniButton("去源管理", onClick = onSourceManager)
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(38.dp)
            .background(c.chip, RoundedCornerShape(10.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphView(Glyph.SEARCH, c.fg3, 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = c.fg3, fontSize = 13.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = c.fg, fontSize = 13.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                "×",
                color = c.fg3,
                fontSize = 17.sp,
                modifier = Modifier
                    .clickable(onClick = onClear)
                    .padding(start = 8.dp),
            )
        } else {
            Text(
                "搜索",
                color = c.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onSearch)
                    .padding(start = 8.dp),
            )
        }
    }
}

/** 顶部一颗小号的「源」标签，详情页和网格脚注共用。 */
@Composable
fun SourceBadge(name: String, logged: Boolean) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = c.fg3, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (logged) {
            Spacer(Modifier.width(4.dp))
            Text("已登录", color = c.ok, fontSize = 9.sp)
        }
    }
}

/** 供「我的 → 阅读设置」直接复用阅读器的设置面板时用的占位说明。 */
@Composable
fun SettingsHintRow(label: String, value: String) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.fg, fontSize = 13.5.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.weight(1f))
        Text(value, color = c.fg3, fontSize = 11.5.sp)
    }
}
