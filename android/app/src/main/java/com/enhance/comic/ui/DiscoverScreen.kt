package com.enhance.comic.ui

import androidx.activity.compose.BackHandler

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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 发现页：搜索 + 选源 + 三列网格。
 *
 * 「全部」模式下把各源的首页拉回来，按**标题归一化后完全一致**聚合成一张卡，
 * 卡角标显示有几个源。各源没有互通的 ID，所以只做「完全一致才合并」，
 * 宁可少合也不把两本不同的书串成一本。只用**用户开着**的源。
 *
 * 加载纪律（这三条决定了「点进来是不是要等」）：
 *  1. 串行，不并发 —— 引擎是同步桥，并发只会排队，还让后面的源被超时误杀；
 *  2. 每个源的结果单独缓存（`s|<源>|<关键词>`），点单个 chip 直接命中；
 *  3. [sourceRev] 变了才整页重算，切 Tab 回来不算。
 */
/**
 * 发现页的跨 Tab 状态。
 *
 * 之前这些全是 `remember`，而 Tab 是 `when(tab)` 渲染的 —— 切走就把整个 composition
 * 连同状态一起丢掉，切回来等于重新进一次页面：源要重读、8 个源要重拉。
 * 提到 Activity 级别之后，切回来内容还在，配合 [ExploreCache] 连网络都不打。
 */
class DiscoverState {
    var kw by mutableStateOf("")
    var query by mutableStateOf("")
    var selected by mutableStateOf("")
    var works by mutableStateOf<List<Work>>(emptyList())
    var sections by mutableStateOf<List<ExploreSection>>(emptyList())
    var rows by mutableStateOf<List<SourceRow>>(emptyList())
    var srcs by mutableStateOf<org.json.JSONArray?>(null)
    // 默认 true：首帧别闪一下「没有取到内容」
    var busy by mutableStateOf(true)
    var okCount by mutableIntStateOf(0)
    var failures by mutableIntStateOf(0)
    /** 这一轮因为「连续失败、暂时连不上」被跳过的源数 */
    var skipped by mutableIntStateOf(0)
    var err by mutableStateOf<String?>(null)
    var tick by mutableIntStateOf(0)
    /** 「正在取 X（2/5）」这类进度，只在还有源在刷新时显示 */
    var progress by mutableStateOf("")
    /** 已经完整处理过的参数组合；同组合再进来直接跳过（内容还在屏幕上） */
    var settledFor by mutableStateOf("")
    var seeded by mutableStateOf(false)
    /** 当前是否走着 VPN（只影响提示文案和「要不要多试几次」） */
    var vpn by mutableStateOf(false)
    /** 点「12 本 ›」打开的分区全部列表（切 Tab 不丢） */
    var openSection by mutableStateOf<ExploreSection?>(null)
}

/** 单个源一次请求最多等这么久。超过就别再等了，换下一个源。 */
private const val SOURCE_TIMEOUT = 14_000L

@Composable
fun DiscoverScreen(
    engine: SourceEngine,
    ui: UiStore,
    state: DiscoverState,
    sourceRev: Int = 0,
    initialQuery: String = "",
    onOpenWork: (Work) -> Unit,
    onSourceManager: () -> Unit,
) {
    val c = LocalColors.current

    // 从搜索页跳进来带的关键词只认第一次，之后别覆盖用户自己输的
    LaunchedEffect(Unit) {
        if (!state.seeded) {
            state.seeded = true
            if (initialQuery.isNotBlank()) {
                state.kw = initialQuery
                state.query = initialQuery
            }
        }
    }

    LaunchedEffect(state.selected, state.query, state.tick, sourceRev) {
        // VPN 状态放进签名：开了/关了都要按新网络重来一遍
        // （关着时被判定为「连不上」的源，开 VPN 后应该立刻重试）
        val vpnNow = ExploreCache.vpnOn()
        state.vpn = vpnNow
        val sig = state.selected + "|" + state.query + "|" + state.tick + "|" + sourceRev + "|" + vpnNow
        // 切 Tab 回来时 key 没变、内容还在：直接跳过，一个请求都不发
        if (state.settledFor == sig &&
            (state.works.isNotEmpty() || state.sections.isNotEmpty() || state.err != null)
        ) {
            return@LaunchedEffect
        }
        state.err = null
        try {
            // 空数组也当作「还没读到」：引擎刚起时可能返回空，别把它永久缓存住
            if (state.srcs == null || state.srcs?.length() == 0) {
                runCatching { state.srcs = engine.sources() }
            }
            val built = runCatching {
                buildRows(state.srcs ?: org.json.JSONArray(), ui.disabledSources(), ui.sourceOrder())
            }.getOrElse { emptyList() }
            state.rows = built
            val onRows = built.filter { it.enabled }

            // 选中的源被关掉了：退回「全部」，不然会一直停在一个已经关掉的源上
            if (state.selected.isNotBlank() && onRows.none { it.key == state.selected }) {
                state.selected = ""
                return@LaunchedEffect
            }

            if (state.selected.isBlank()) {
                loadAll(engine, state, onRows)
            } else {
                loadOne(engine, state, built)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.err = "加载失败：" + (e.message ?: e.toString())
        } finally {
            state.busy = false
            state.progress = ""
            state.settledFor = sig
        }
    }

    // 点「12 本 ›」：这一区的完整列表
    BackHandler(enabled = state.openSection != null) { state.openSection = null }
    state.openSection?.let { sec ->
        SectionListScreen(
            title = sec.title.ifBlank { "发现" },
            comics = sec.comics,
            onBack = { state.openSection = null },
            onOpenComic = { onOpenWork(it.asWork()) },
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BigTitle(
            text = "发现",
            sub = when {
                state.busy && state.progress.isNotBlank() -> state.progress
                state.busy -> "正在取内容…"
                // 内容已经在屏幕上了，后台还在补剩下的源
                state.progress.isNotBlank() -> "${state.works.size} 部作品 · 后台更新中"
                state.selected.isBlank() -> buildString {
                    append("${state.works.size} 部作品 · ${state.okCount} 个源")
                    val multi = state.works.count { it.entries.size > 1 }
                    if (multi > 0) append(" · $multi 部是跨源同一本")
                    if (state.failures > 0) append(" · ${state.failures} 个源没响应")
                    if (state.skipped > 0) {
                        append(
                            if (state.vpn) " · ${state.skipped} 个源暂时跳过"
                            else " · ${state.skipped} 个源要开代理"
                        )
                    }
                }
                else -> "只看这个源"
            },
        )

        SearchField(
            value = state.kw,
            placeholder = "搜漫画名、作者、题材",
            onValue = { state.kw = it },
            onSearch = { state.query = state.kw.trim() },
            onClear = { state.kw = ""; state.query = "" },
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item { Chip("全部", state.selected.isBlank()) { state.selected = "" } }
            items(state.rows.filter { it.enabled }, key = { it.key }) { r ->
                // 点一下先看缓存（立刻有内容），需要刷新时在后台补
                Chip(r.name, state.selected == r.key) { state.selected = r.key }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (state.failures > 0 && (state.works.isNotEmpty() || state.sections.isNotEmpty())) {
            NoteBar("${state.failures} 个源没响应，已显示其它源的结果", Tone.WARN)
            Spacer(Modifier.height(8.dp))
        }

        when {
            state.busy && state.works.isEmpty() && state.sections.isEmpty() -> LoadingBox(200.dp)

            state.selected.isBlank() && state.works.isEmpty() ->
                EmptyBlock(state.err, onSourceManager) { state.tick++ }

            state.selected.isNotBlank() && state.sections.isEmpty() ->
                EmptyBlock(state.err, onSourceManager) { state.tick++ }

            state.selected.isBlank() -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGapV),
            ) {
                items(state.works, key = { it.norm }) { work ->
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
                state.sections.forEachIndexed { si, sec ->
                    if (sec.title.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h$si") {
                            SectionHeader(
                                title = sec.title,
                                more = "${sec.comics.size} 本",
                                onMore = { state.openSection = sec },
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

/**
 * 「全部」：多源聚合。
 *
 * 三个关键点（之前「一直加载」的原因）：
 *  1. **串行**，不是一个源一个协程。引擎是同步桥，发 8 个协程 = 让它们排队，
 *     而且排在后面的源会因为「排队时间也算进超时」被误判成失败。
 *  2. 每个源返回后单独写一份缓存（`s|<源>|<关键词>`），下次点单个源的 chip 直接命中。
 *  3. 已新鲜的源（30 分钟内拉过）**一个请求都不发**，直接用缓存。
 */
private suspend fun loadAll(engine: SourceEngine, state: DiscoverState, onRows: List<SourceRow>) {
    val targets = onRows.take(8)
    if (targets.isEmpty()) {
        state.works = emptyList()
        state.sections = emptyList()
        state.busy = false
        state.okCount = 0
        state.failures = 0
        state.err = if (state.rows.isEmpty()) {
            "还没读到源列表，稍后再试"
        } else {
            "你把所有源都关掉了。去源管理至少打开一个。"
        }
        return
    }

    // 1) 先用每源缓存拼一份能立刻看的内容 —— 点进来不转圈
    val hits = LinkedHashMap<String, ExploreCache.SectionHit?>()
    targets.forEach { hits[it.key] = ExploreCache.getSections(exploreSig(it.key, state.query)) }
    val cachedPool = targets.flatMap { s ->
        hits[s.key]?.sections?.flatMap { it.comics } ?: emptyList()
    }
    val hadCached = cachedPool.isNotEmpty()
    state.sections = emptyList()
    if (hadCached) {
        state.works = groupWorks(cachedPool)
        state.okCount = hits.count { it.value != null }
        state.busy = false
    } else {
        state.works = emptyList()
        state.okCount = 0
        state.busy = true
    }
    state.failures = 0

    // 2) 挑这一轮真正要跑的源：缓存新鲜的不跑；连续失败、还在冷却期的也先不跑
    val runnable = targets.filter { !ExploreCache.isCooling(it.key) }
    state.skipped = targets.size - runnable.size
    // 全都在冷却：至少试几个，否则页面永远停在旧内容上
    val pool2 = if (runnable.isEmpty()) targets.take(2) else runnable
    val need = pool2.filter { hits[it.key]?.fresh != true }
    if (need.isEmpty()) {
        state.progress = ""
        return
    }
    // 上次快的先跑：同步桥上顺序执行，先回来的先上屏
    val ordered = need.sortedBy { s ->
        ExploreCache.cost(exploreSig(s.key, state.query)).let { if (it <= 0) 5_000L else it }
    }

    val acc = ArrayList<ComicRow>()
    var ok = 0
    var bad = 0
    for (s in ordered) {
        // 切源 / 切 Tab / 退出页面：立刻停，别再往引擎队列里塞新请求
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        state.progress = "正在取 ${s.name}（${ok + bad + 1}/${ordered.size}）"
        val t0 = System.currentTimeMillis()
        val secs = kotlinx.coroutines.withTimeoutOrNull(SOURCE_TIMEOUT) {
            if (state.query.isBlank()) {
                val r = engine.explore(s.key, 0)
                if (!r.optBoolean("ok")) {
                    null
                } else {
                    parseSections(r.optJSONObject("data"), s.key, s.name)
                        .filter { it.comics.isNotEmpty() }
                }
            } else {
                val r = engine.search(s.key, state.query, "[]", 1)
                if (!r.optBoolean("ok")) {
                    null
                } else {
                    val list = parseComics(r.optJSONObject("data"))
                        .map { it.copy(sourceKey = s.key, sourceName = s.name) }
                    if (list.isEmpty()) emptyList() else listOf(ExploreSection("搜索结果", list))
                }
            }
        }
        val cost = System.currentTimeMillis() - t0
        if (secs != null && secs.isNotEmpty()) {
            ok += 1
            ExploreCache.markOk(s.key)
            ExploreCache.putSections(exploreSig(s.key, state.query), secs, cost)
            acc += secs.flatMap { it.comics }
        } else {
            bad += 1
            // 只有「请求失败/超时」才记账；成功但没内容（要登录、空频道）不算站挂了
            if (secs == null) ExploreCache.markFail(s.key)
        }
        // 有缓存时不在刷新过程中替换 —— 否则列表会先缩成一两个源，看着像掉内容
        if (!hadCached) {
            state.works = groupWorks(acc.toList(), sort = false)
            state.okCount = ok
            state.failures = bad
            if (acc.isNotEmpty()) state.busy = false
        }
    }

    // 3) 收尾：用「每源最新缓存」重组，这次没刷出来的源保留上次的内容
    val finalPool = targets.flatMap { s ->
        ExploreCache.getSections(exploreSig(s.key, state.query))?.sections?.flatMap { it.comics }
            ?: emptyList()
    }
    val finalWorks = groupWorks(finalPool)
    state.works = finalWorks
    state.okCount = targets.count { ExploreCache.getSections(exploreSig(it.key, state.query)) != null }
    state.failures = bad
    state.busy = false
    if (finalWorks.isNotEmpty()) {
        ExploreCache.put(
            "w|" + state.query + "|" + targets.joinToString(",") { it.key },
            finalWorks,
            state.okCount,
            bad,
        )
    } else {
        state.err = if (state.query.isNotBlank()) {
            "没有找到「${state.query}」"
        } else {
            "这些源这次都没返回内容。可能是网站本身暂时打不开，" +
                "换个时间再试，或者只留几个快的源。"
        }
    }
}

/** 单个源：先显示上次的，再决定要不要刷新（30 分钟内不刷）。 */
private suspend fun loadOne(engine: SourceEngine, state: DiscoverState, rows: List<SourceRow>) {
    val row = rows.firstOrNull { it.key == state.selected }
    if (row == null) {
        state.sections = emptyList()
        state.works = emptyList()
        state.busy = false
        state.err = "这个源不在列表里了"
        return
    }
    val sig = exploreSig(row.key, state.query)
    val hit = ExploreCache.getSections(sig)
    if (hit != null) {
        state.sections = hit.sections
        state.works = emptyList()
        state.okCount = 1
        state.failures = 0
        state.err = null
        state.busy = false
        // 新鲜：连网络都不打，秒出
        if (hit.fresh) return
    } else {
        state.sections = emptyList()
        state.works = emptyList()
        state.okCount = 0
        state.failures = 0
        state.busy = true
    }

    state.progress = "正在取 ${row.name}"
    val t0 = System.currentTimeMillis()
    val secs = kotlinx.coroutines.withTimeoutOrNull(SOURCE_TIMEOUT) {
        if (state.query.isBlank()) {
            val r = engine.explore(row.key, 0)
            if (!r.optBoolean("ok")) {
                null
            } else {
                parseSections(r.optJSONObject("data"), row.key, row.name)
                    .filter { it.comics.isNotEmpty() }
            }
        } else {
            val r = engine.search(row.key, state.query, "[]", 1)
            if (!r.optBoolean("ok")) {
                null
            } else {
                val list = parseComics(r.optJSONObject("data"))
                    .map { it.copy(sourceKey = row.key, sourceName = row.name) }
                if (list.isEmpty()) emptyList() else listOf(ExploreSection("搜索结果", list))
            }
        }
    }
    val cost = System.currentTimeMillis() - t0
    when {
        secs == null -> {
            // 请求失败/超时：有旧内容就留着，只在顶部提示；什么都没才报错
            ExploreCache.markFail(row.key)
            state.failures = 1
            if (hit == null) {
                state.okCount = 0
                state.err = "这个源没返回内容（超时或者站点的接口挂了）。"
            }
        }
        secs.isEmpty() -> {
            state.failures = 0
            state.okCount = 1
            state.sections = emptyList()
            state.err = if (state.query.isNotBlank()) {
                "这个源没搜到「${state.query}」"
            } else {
                "这个源现在没有可显示的内容。可能要先去登录，或者换个关键词。"
            }
        }
        else -> {
            state.sections = secs
            state.okCount = 1
            state.failures = 0
            state.err = null
            ExploreCache.markOk(row.key)
            ExploreCache.putSections(sig, secs, cost)
        }
    }
    state.busy = false
}

@Composable
private fun EmptyBlock(err: String?, onSourceManager: () -> Unit, onRetry: () -> Unit) {
    Column {
        EmptyHint(
            title = "没有取到内容",
            message = friendlyError(err ?: "换个关键词，或者去源管理把慢的源先关掉。"),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            MiniButton("重试", onClick = onRetry, accent = true)
            Spacer(Modifier.width(10.dp))
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
