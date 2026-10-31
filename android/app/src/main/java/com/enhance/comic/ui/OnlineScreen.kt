package com.enhance.comic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.OnlineRecord
import com.enhance.comic.data.SourceEngine
import com.enhance.comic.data.UiStore
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.ReaderLayout
import com.enhance.comic.model.ReaderPrefs
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private sealed class Nav {
    object Sources : Nav()
    data class Browse(val key: String, val title: String) : Nav()
    data class Info(
        val key: String,
        val id: String,
        val title: String,
        /** 同作品多源聚合的其它入口；空表示这本只在当前源出现 */
        val alts: List<WorkEntry> = emptyList(),
    ) : Nav()
    data class Read(val urls: List<String>, val referer: String?, val title: String, val back: Nav) : Nav()
    /** 惰性取图：只给页数，每页真的地址由阅读器按需解 */
    data class ReadLazy(
        val key: String,
        val comicId: String,
        val epId: String,
        val count: Int,
        val title: String,
        val back: Nav,
        /** 整本目录（id to 标题）—— 阅读时能直接换话，不用退出去重进 */
        val chapters: List<Pair<String, String>> = emptyList(),
        val index: Int = 0,
    ) : Nav()
    /** 独立的账号页：登录 / 退出 / 看账号信息，退出后回到来的那一页 */
    data class Account(val key: String, val name: String, val back: Nav) : Nav()
}

/**
 * 在线部分的全部页面。带 startComicId 进来时直接落在详情页，
 * 这样首页/阅读记录点一本书不用再从源列表一层层点下去。
 */
@Composable
fun OnlineSourceScreen(
    startKey: String? = null,
    startComicId: String? = null,
    startTitle: String = "",
    startAlts: List<WorkEntry> = emptyList(),
    uiStore: UiStore? = null,
    /** 阅读器设置：和本地阅读器共用同一份，在线阅读也能调 */
    prefs: ReaderPrefs = ReaderPrefs(),
    enhance: EnhanceSettings = EnhanceSettings(),
    onPrefs: (ReaderPrefs) -> Unit = {},
    onEnhance: (EnhanceSettings) -> Unit = {},
    onRecordsChanged: () -> Unit = {},
    onSourcesChanged: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { SourceEngine.get(context) }
    var nav by remember {
        mutableStateOf<Nav>(
            if (startKey != null && startComicId != null) {
                Nav.Info(startKey, startComicId, startTitle, startAlts)
            } else {
                Nav.Sources
            },
        )
    }

    when (val n = nav) {
        is Nav.Sources -> SourcesScreen(
            engine = engine,
            ui = uiStore,
            onBack = onBack,
            onChanged = onSourcesChanged,
            onOpen = { key, name -> nav = Nav.Browse(key, name) },
            onAccount = { key, name -> nav = Nav.Account(key, name, Nav.Sources) },
        )

        is Nav.Browse -> BrowseScreen(
            engine = engine,
            key = n.key,
            title = n.title,
            onBack = { nav = Nav.Sources },
            onAccount = { name -> nav = Nav.Account(n.key, name, Nav.Browse(n.key, n.title)) },
            onOpen = { id, title -> nav = Nav.Info(n.key, id, title) },
        )

        is Nav.Info -> DetailScreen(
            engine = engine,
            key = n.key,
            id = n.id,
            title = n.title,
            alts = n.alts,
            uiStore = uiStore,
            onBack = { nav = Nav.Sources },
            onSwitch = { e -> nav = Nav.Info(e.sourceKey, e.comicId, e.title, n.alts) },
            onAccount = { name ->
                nav = Nav.Account(n.key, name, Nav.Info(n.key, n.id, n.title, n.alts))
            },
            onRead = { comicId, epId, data, t, chapters, index ->
                val back = Nav.Info(n.key, n.id, n.title, n.alts)
                nav = if (data.optBoolean("lazy")) {
                    Nav.ReadLazy(
                        n.key, comicId, epId, data.optInt("count"), t, back, chapters, index,
                    )
                } else {
                    Nav.Read(imageUrls(data), null, t, back)
                }
            },
            onRemember = {
                uiStore?.remember(it)
                onRecordsChanged()
            },
        )

        is Nav.Account -> AccountScreen(
            engine = engine,
            key = n.key,
            fallbackName = n.name,
            onBack = { nav = n.back },
        )

        is Nav.Read -> OnlineReader(
            urls = n.urls,
            referer = n.referer,
            title = n.title,
            prefs = prefs,
            enhance = enhance,
            onPrefs = onPrefs,
            onEnhance = onEnhance,
            onBack = { nav = n.back },
        )

        // 换话时 epId 变了，用 key() 让阅读器整个重建（页缓存、滚动位置都归零）
        is Nav.ReadLazy -> key(n.epId) {
            OnlineLazyReader(
                engine = engine,
                nav = n,
                prefs = prefs,
                enhance = enhance,
                onPrefs = onPrefs,
                onEnhance = onEnhance,
                onPickChapter = { i ->
                    n.chapters.getOrNull(i)?.let { (id, t) ->
                        nav = n.copy(epId = id, title = t, index = i)
                    }
                },
                onBack = { nav = n.back },
            )
        }
    }
}

private fun imageUrls(data: JSONObject): List<String> {
    val imgs = data.optJSONArray("images") ?: return emptyList()
    return buildList {
        for (i in 0 until imgs.length()) {
            imgs.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

// ==================================================================================== 源列表

/**
 * 源管理：登录状态 + 开关 + 顺序。
 *
 * 开关只影响「全部源」的搜索、发现页的源 chips 和首页推荐；单独点进某个源仍然能看，
 * 免得用户关了之后连临时打开看一眼都做不到。
 * 顺序决定首页推荐取哪几个、发现页 chips 的先后。
 */
@Composable
private fun SourcesScreen(
    engine: SourceEngine,
    ui: UiStore?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpen: (String, String) -> Unit,
    onAccount: (String, String) -> Unit,
) {
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        runCatching {
            rows = buildRows(
                engine.sources(),
                ui?.disabledSources() ?: emptySet(),
                ui?.sourceOrder() ?: emptyList(),
            )
        }.onFailure { err = it.message }
    }

    LaunchedEffect(Unit) {
        busy = rows.isEmpty()
        reload()
        busy = false
    }

    fun toggle(key: String, on: Boolean) {
        ui?.setSourceEnabled(key, on)
        rows = rows.map { if (it.key == key) it.copy(enabled = on) else it }
        // 源集合变了，聚合结果不再对应当前这批源；但单源结果还能用，别一起清
        ExploreCache.clearAggregates()
        onChanged()
    }

    fun move(from: Int, delta: Int) {
        val to = from + delta
        if (from !in rows.indices || to !in rows.indices) return
        val reordered = rows.map { it.key }.toMutableList().also {
            val t = it[from]
            it[from] = it[to]
            it[to] = t
        }
        ui?.setSourceOrder(reordered)
        rows = reordered.mapNotNull { k -> rows.firstOrNull { it.key == k } }
        ExploreCache.clearAggregates()
        onChanged()
    }

    val onCount = rows.count { it.enabled }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar("漫画源", onBack)
        if (busy && rows.isEmpty()) {
            LoadingBox(220.dp)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    SectionHeader("全部源", more = "$onCount/${rows.size} 在用")
                }
                itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                    SourceRowItem(
                        r = r,
                        index = i,
                        total = rows.size,
                        onClick = { onOpen(r.key, r.name) },
                        onLogin = { onAccount(r.key, r.name) },
                        onToggle = { on -> toggle(r.key, on) },
                        onMove = { delta -> move(i, delta) },
                    )
                }
                item {
                    Text(
                        "关掉的源不参与「全部源」的搜索和首页推荐，但单独点进去仍然能看。" +
                            "E-Hentai / 绅士漫画 / PicACG 这类站点国内仍需自备代理。",
                        color = LocalColors.current.fg3,
                        fontSize = 10.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                err?.let {
                    item {
                        Text(
                            "加载失败：$it",
                            color = LocalColors.current.bad,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRowItem(
    r: SourceRow,
    index: Int,
    total: Int,
    onClick: () -> Unit,
    onLogin: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.chip),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                r.name.take(2),
                color = if (r.enabled) c.fg else c.fg3,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.name,
                    color = if (r.enabled) c.fg else c.fg3,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MiniSwitch(checked = r.enabled, onChange = onToggle)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        r.needLogin && !r.logged -> "不登录看不了"
                        r.canLogin && !r.logged -> "未登录"
                        r.logged -> "已登录"
                        else -> "公开内容"
                    },
                    color = if (r.logged) c.ok else c.fg3,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (r.canLogin) {
                    MiniButton(if (r.logged) "账号" else "登录", onLogin)
                    Spacer(Modifier.width(6.dp))
                }
                MoveButton("↑", enabled = index > 0) { onMove(-1) }
                Spacer(Modifier.width(3.dp))
                MoveButton("↓", enabled = index < total - 1) { onMove(1) }
            }
        }
    }
    HLine(Modifier.padding(start = 68.dp))
}

/** 开关关掉时整行的文字压暗，但行本身仍可点进去。 */
@Composable
private fun MiniSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier
            .size(38.dp, 22.dp)
            .clip(CircleShape)
            .background(if (checked) c.accent else c.chip)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) c.accentInk else c.fg3),
        )
    }
}

@Composable
private fun MoveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) c.chip else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) c.fg2 else c.fg3.copy(alpha = 0.3f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}


// ==================================================================================== 浏览

@Composable
private fun BrowseScreen(
    engine: SourceEngine,
    key: String,
    title: String,
    onBack: () -> Unit,
    onAccount: (String) -> Unit,
    onOpen: (String, String) -> Unit,
) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var kw by remember { mutableStateOf("") }
    var sections by remember { mutableStateOf<List<ExploreSection>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var searched by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var canLogin by remember { mutableStateOf(true) }
    // 点「12 本 ›」看这一区的完整列表
    var openSection by remember { mutableStateOf<ExploreSection?>(null) }
    BackHandler(enabled = openSection != null) { openSection = null }
    openSection?.let { sec ->
        SectionListScreen(
            title = sec.title.ifBlank { title },
            comics = sec.comics,
            onBack = { openSection = null },
            onOpenComic = { onOpen(it.id, it.title) },
        )
        return
    }

    LaunchedEffect(tick) {
        busy = true
        err = null
        try {
            runCatching {
                val a = engine.account(key)
                if (a.optBoolean("ok")) {
                    val d = a.optJSONObject("data")
                    logged = d?.optBoolean("logged") == true
                    canLogin = d?.optBoolean("canLogin") != false
                }
            }
            val r = engine.explore(key, 0)
            if (r.optBoolean("ok")) {
                sections = parseSections(r.optJSONObject("data"))
                if (sections.isEmpty()) searched = false
            } else {
                err = r.optString("error", "加载失败")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            err = "加载失败：" + (e.message ?: e.toString())
        } finally {
            busy = false
        }
    }

    fun doSearch(q: String) {
        if (busy || q.isBlank()) return
        scope.launch {
            busy = true
            err = null
            val r = try {
                engine.search(key, q, "[]", 1)
            } catch (e: kotlinx.coroutines.CancellationException) {
                busy = false
                throw e
            } catch (e: Throwable) {
                busy = false
                searched = true
                sections = emptyList()
                err = "搜索失败：" + (e.message ?: e.toString())
                return@launch
            }
            busy = false
            searched = true
            if (r.optBoolean("ok")) {
                val list = parseComics(r.optJSONObject("data"))
                sections = if (list.isEmpty()) emptyList() else listOf(ExploreSection("", list))
                if (list.isEmpty()) err = "没有找到「$q」"
            } else {
                sections = emptyList()
                err = r.optString("error", "搜索失败")
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar(title, onBack, action = {
            MiniButton(if (logged) "已登录" else "账号", onClick = { onAccount(title) })
        })
        SearchBar(value = kw, placeholder = "在这个源里搜索", onValue = { kw = it }, onSubmit = { doSearch(kw) })
        Spacer(Modifier.height(8.dp))

        val flat = sections.flatMap { it.comics }
        when {
            busy && flat.isEmpty() -> LoadingBox(180.dp)
            flat.isEmpty() -> {
                val e = err
                val loginLikely = e != null && (e.contains("登录") || e.contains("login", true))
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint(
                        title = when {
                            loginLikely -> "这个源要先登录"
                            searched -> "没有结果"
                            e != null -> "没能取到内容"
                            else -> "这个源没有首页"
                        },
                        message = when {
                            loginLikely -> if (logged) {
                                "已登录还这样，多半是登录态过期：进账号页退出再登一次。"
                            } else {
                                "点右上角「账号」填这个站的账号，登录后就能搜索和看图。"
                            }
                            e != null -> friendlyError(e)
                            else -> "直接在搜索框里输关键词试试。"
                        },
                        icon = if (loginLikely) Glyph.USER else Glyph.SEARCH,
                    )
                    MiniButton("重新加载", onClick = { tick++ })
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGapV),
            ) {
                sections.forEachIndexed { si, sec ->
                    if (sec.title.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h$si") {
                            SectionHeader(
                                sec.title,
                                more = "${sec.comics.size} 本",
                                onMore = { openSection = sec },
                            )
                        }
                    }
                    itemsIndexed(sec.comics, key = { i, it -> "c$si-$i-" + it.id }) { _, comic ->
                        ComicCell(
                            cover = comic.cover,
                            title = comic.title,
                            sub = comic.sub,
                            seed = comic.title.hashCode(),
                            onClick = { onOpen(comic.id, comic.title) },
                        )
                    }
                }
            }
        }
        if (err != null && flat.isNotEmpty()) {
            Text(err!!, color = c.bad, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}

@Composable
private fun SearchBar(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    onSubmit: () -> Unit,
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
            if (value.isEmpty()) Text(placeholder, color = c.fg3, fontSize = 13.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = c.fg, fontSize = 13.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "搜索",
            color = c.accent,
            fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onSubmit).padding(start = 8.dp),
        )
    }
}

// ==================================================================================== 详情

/** 章节摊平：groups[].items[] 保持原顺序，顺便记住序号给「继续阅读」定位。 */
private data class Chap(val id: String, val title: String, val index: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    engine: SourceEngine,
    key: String,
    id: String,
    title: String,
    alts: List<WorkEntry> = emptyList(),
    uiStore: UiStore?,
    onBack: () -> Unit,
    onSwitch: (WorkEntry) -> Unit = {},
    onAccount: (String) -> Unit,
    onRead: (String, String, JSONObject, String, List<Pair<String, String>>, Int) -> Unit,
    onRemember: (OnlineRecord) -> Unit,
) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var sourceName by remember { mutableStateOf(title) }

    // 详情页只有源 key，没有源名；存阅读记录和开账号页都要用它
    LaunchedEffect(key) {
        runCatching {
            val arr = engine.sources()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("key") == key) {
                    sourceName = o.optString("name", key)
                    break
                }
            }
        }
    }

    LaunchedEffect(id) {
        busy = true
        err = null
        try {
            val r = engine.detail(key, id)
            if (r.optBoolean("ok")) d = r.optJSONObject("data")
            else err = r.optString("error", "加载失败")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            err = "打开失败：" + (e.message ?: e.toString())
        } finally {
            // 不管成功、失败还是异常，转圈都必须停 —— 否则就是「点一本漫画一直转」
            busy = false
        }
    }

    val chapters = remember(d) {
        val groups = d?.optJSONArray("groups") ?: JSONArray()
        val out = mutableListOf<Chap>()
        for (g in 0 until groups.length()) {
            val grp = groups.optJSONObject(g) ?: continue
            val arr = grp.optJSONArray("items") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cid = o.optString("id")
                val ctitle = o.optString("title")
                // 「整本一话」的源（NoyACG）章节 id 可能是空的，只要还有标题就要保留，
                // 否则详情页会变成「没解析到章节」。
                if (cid.isBlank() && ctitle.isBlank()) continue
                out += Chap(cid, ctitle.ifBlank { cid.ifBlank { "全部页面" } }, out.size)
            }
        }
        out
    }
    val last = remember(id, chapters.size) { uiStore?.lastChapter(key, id) }

    fun openChapter(cid: String, epTitle: String) {
        if (pending != null) return
        pending = cid
        scope.launch {
            val r = try {
                engine.pages(key, id, cid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                pending = null
                throw e
            } catch (e: Throwable) {
                pending = null
                err = "这一话打不开：" + (e.message ?: e.toString())
                return@launch
            }
            pending = null
            if (!r.optBoolean("ok")) {
                err = r.optString("error", "取图失败")
                return@launch
            }
            val data = r.optJSONObject("data") ?: JSONObject()
            val n = if (data.optBoolean("lazy")) data.optInt("count")
            else data.optJSONArray("images")?.length() ?: 0
            if (n == 0) {
                err = "这一话没有图片（可能需要登录）"
                return@launch
            }
            val idx = chapters.firstOrNull { it.id == cid }?.index ?: 0
            onRemember(
                OnlineRecord(
                    source = key,
                    sourceName = sourceName,
                    comicId = id,
                    title = d?.optString("title").orEmpty().ifBlank { title },
                    cover = d?.optString("cover").orEmpty(),
                    epId = cid,
                    epTitle = epTitle,
                    epIndex = idx,
                    epTotal = chapters.size,
                    at = System.currentTimeMillis(),
                ),
            )
            onRead(id, cid, data, epTitle, chapters.map { it.id to it.title }, idx)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar("", onBack, action = {
            MiniButton("账号", onClick = { onAccount(sourceName) })
        })
        val info = d
        if (busy && info == null) {
            LoadingBox(240.dp)
        } else if (info == null) {
            EmptyHint("没能打开这本", friendlyError(err ?: "详情加载失败"), Glyph.SEARCH)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Box(
                            Modifier
                                .width(100.dp)
                                .height(134.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(coverBrush(title.hashCode(), c.dark)),
                        ) {
                            val cover = info.optString("cover")
                            if (cover.isNotBlank()) {
                                RemoteImage(
                                    url = cover,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    title.take(2),
                                    color = placeholderInk(c.dark),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(top = 2.dp)) {
                            Text(
                                info.optString("title").ifBlank { title },
                                color = c.fg,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 21.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = info.optString("subTitle").ifBlank {
                                info.optString("author")
                            }
                            if (sub.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(sub, color = c.fg2, fontSize = 11.sp, maxLines = 2)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Pill(sourceName, Tone.PLAIN)
                                if (chapters.isNotEmpty()) Pill("${chapters.size} 话", Tone.PLAIN)
                            }
                        }
                    }
                }

                // 同作品多源：一本在几个源都有时列出来，直接切（切了就是换一本详情重载）
                if (alts.size > 1) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(
                                "换一个源看",
                                color = c.fg2,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 18.dp, bottom = 7.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                items(alts, key = { it.sourceKey + "|" + it.comicId }) { e ->
                                    val on = e.sourceKey == key && e.comicId == id
                                    Chip(e.sourceName, selected = on) {
                                        if (!on) onSwitch(e)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val target = last?.let { l -> chapters.firstOrNull { it.id == l.epId } }
                            ?: chapters.firstOrNull()
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (target != null) c.accent else c.chip)
                                .clickable(enabled = target != null) {
                                    target?.let { openChapter(it.id, it.title) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when {
                                    pending != null -> "取图中…"
                                    target == null -> "没有可读的章节"
                                    last != null -> "继续阅读 · ${last.epTitle}"
                                    else -> "开始阅读 · ${chapters.first().title}"
                                },
                                color = if (target != null) c.accentInk else c.fg3,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.chip)
                                .clickable { onAccount(sourceName) },
                            contentAlignment = Alignment.Center,
                        ) { GlyphView(Glyph.USER, c.fg2, 18.dp) }
                    }
                }

                val desc = info.optString("description").replace(Regex("\\s+"), " ").trim()
                if (desc.isNotBlank()) {
                    item {
                        Text(
                            desc,
                            color = c.fg2,
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                        )
                    }
                }

                err?.let { e ->
                    item {
                        Text(
                            friendlyError(e),
                            color = c.bad,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                    }
                }

                item {
                    SectionHeader("目录", more = if (chapters.isEmpty()) null else "${chapters.size} 话")
                }

                if (chapters.isEmpty()) {
                    item {
                        EmptyHint(
                            title = "没解析到章节",
                            message = "可能需要登录，或者这个源改版了。点右上角「账号」登录后再试。",
                            icon = Glyph.LIST,
                        )
                    }
                } else {
                    item {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            chapters.forEach { ch ->
                                val cur = ch.id == last?.epId
                                Box(
                                    Modifier
                                        .width(100.dp)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (cur) c.accent else c.card2, RoundedCornerShape(7.dp))
                                        .clickable { openChapter(ch.id, ch.title) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        ch.title,
                                        color = if (cur) c.accentInk else c.fg,
                                        fontSize = 11.sp,
                                        fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================================================================================== 账号

@Composable
private fun AccountScreen(
    engine: SourceEngine,
    key: String,
    fallbackName: String,
    onBack: () -> Unit,
) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginErr by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Cookie 登录（E-Hentai 这类）要按源声明的字段数填，比如 ipb_member_id / ipb_pass_hash / igneous / star
    var cookieVals by remember { mutableStateOf<List<String>>(emptyList()) }
    // 网页登录（绅士漫画要过滑块，账号密码接口过不去）
    var webLogin by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val r = engine.account(key)
        if (r.optBoolean("ok")) {
            val d = r.optJSONObject("data")
            info = d
            err = null
            val saved = d?.optString("savedAccount").orEmpty()
            if (saved.isNotBlank() && saved != "cookies" && user.isBlank()) user = saved
            val n = d?.optJSONArray("loginFields")?.length() ?: 0
            if (n > 0 && cookieVals.size != n) cookieVals = List(n) { "" }
        } else {
            err = r.optString("error", "账号信息读取失败")
        }
    }

    LaunchedEffect(key) {
        busy = true
        try {
            refresh()
        } catch (e: Throwable) {
            err = "账号信息读取失败：" + (e.message ?: e.toString())
        } finally {
            busy = false
        }
    }

    fun doLogin() {
        val cookieMode = info?.optString("loginMode") == "cookie"
        val canGo = if (cookieMode) {
            cookieVals.any { it.isNotBlank() }
        } else {
            user.isNotBlank() && pass.isNotBlank()
        }
        if (loginBusy || !canGo) return
        scope.launch {
            loginBusy = true
            loginErr = null
            notice = null
            val r = if (cookieMode) {
                engine.login(key, org.json.JSONArray(cookieVals.map { it.trim() }).toString(), "")
            } else {
                engine.login(key, user.trim(), pass)
            }
            loginBusy = false
            if (r.optBoolean("ok")) {
                pass = ""
                cookieVals = cookieVals.map { "" }
                notice = "登录成功"
                refresh()
            } else {
                val code = r.optString("code")
                val raw = r.optString("error", "账号或密码不对")
                // 引擎侧的错误已经带具体原因（哪个站、什么状态码、要不要代理），
                // 这里在原文后面补一行通用建议，别把细节吞掉
                loginErr = when (code) {
                    "net" -> "登录失败：$raw\n（连不上多半是线路问题，E-Hentai / 绅士漫画 / PicACG 这类站点国内要开代理）"
                    "forbidden" -> "登录失败：$raw\n（403 多半是云防护/防盗链，开/关代理或换个节点重试）"
                    else -> "登录失败：$raw"
                }
            }
        }
    }

    val name = info?.optString("name").orEmpty().ifBlank { fallbackName }
    val logged = info?.optBoolean("logged") == true
    val canLogin = info?.optBoolean("canLogin") == true
    val canWeb = info?.optBoolean("canWebLogin") == true
    val registerUrl = info?.optString("registerUrl").orEmpty()
    val webUrl = info?.optString("loginWebUrl").orEmpty()

    if (webLogin) {
        WebLoginScreen(
            engine = engine,
            key = key,
            url = webUrl.ifBlank { "https://www.wnacg.com/users-login.html" },
            name = name,
            onBack = { webLogin = false },
            onDone = {
                webLogin = false
                notice = "登录成功"
                scope.launch { refresh() }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar("账号", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(2), color = c.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = c.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (logged) "已登录" else if (canLogin) "未登录" else "无需登录",
                        color = if (logged) c.fg3 else c.fg3,
                        fontSize = 10.5.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            when {
                busy -> LoadingBox(120.dp)

                canLogin -> if (logged) {
                    Text("已登录账号", color = c.fg3, fontSize = 11.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        info?.optString("savedAccount").orEmpty()
                            .let { if (it == "cookies") "Cookie 登录" else it }
                            .ifBlank { "（该源未回传账号名）" },
                        color = c.fg,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(14.dp))
                    AccountInfoItems(info)
                    notice?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = c.ok, fontSize = 11.5.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    MiniButton(
                        text = "退出登录",
                        onClick = {
                            if (loginBusy) return@MiniButton
                            scope.launch {
                                loginBusy = true
                                loginErr = null
                                notice = null
                                engine.logout(key)
                                loginBusy = false
                                pass = ""
                                notice = "已退出登录"
                                refresh()
                            }
                        },
                    )
                } else if (info?.optString("loginMode") == "cookie") {
                    val fields = (0 until (info?.optJSONArray("loginFields")?.length() ?: 0))
                        .map { info?.optJSONArray("loginFields")?.optString(it).orEmpty() }
                    Text("Cookie 登录", color = c.fg3, fontSize = 11.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "在浏览器里登录 $name，打开开发者工具 → Application → Cookies，" +
                            "把下面这 ${fields.size} 项逐个复制过来。填一次就不用再管。",
                        color = c.fg2,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    fields.forEachIndexed { i, f ->
                        OutlinedTextField(
                            value = cookieVals.getOrElse(i) { "" },
                            onValueChange = { v ->
                                cookieVals = cookieVals.toMutableList().also {
                                    while (it.size <= i) it.add("")
                                    it[i] = v
                                }
                            },
                            label = { Text(f) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (i == fields.size - 1) ImeAction.Done else ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (i < fields.size - 1) Spacer(Modifier.height(9.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "igneous / star 可以为空（没有就是空着）。",
                        color = c.fg3,
                        fontSize = 10.5.sp,
                    )
                    loginErr?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = c.bad, fontSize = 11.5.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!loginBusy && cookieVals.any { it.isNotBlank() }) c.accent else c.chip)
                            .clickable { doLogin() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loginBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = c.accentInk,
                                )
                                Spacer(Modifier.width(9.dp))
                                Text("验证中…", color = c.accentInk, fontSize = 13.5.sp)
                            }
                        } else {
                            Text("登录", color = c.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text("账号", color = c.fg3, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("账号 / 邮箱") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(9.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { doLogin() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    loginErr?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = c.bad, fontSize = 11.5.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!loginBusy && user.isNotBlank() && pass.isNotBlank()) c.accent else c.chip)
                            .clickable { doLogin() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loginBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = c.accentInk,
                                )
                                Spacer(Modifier.width(9.dp))
                                Text("登录中…", color = c.accentInk, fontSize = 13.5.sp)
                            }
                        } else {
                            Text("登录", color = c.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (canWeb) {
                        Spacer(Modifier.height(12.dp))
                        HLine()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "如果登录时要求滑块验证（比如绅士漫画），账号密码接口过不去，" +
                                "点下面在网页里登录一次：滑块拖一下，之后 Cookie 自动带上。",
                            color = c.fg3,
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        MiniButton("网页登录（带滑块）", { webLogin = true }, accent = true)
                    }
                    if (registerUrl.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "还没有账号？去 $name 注册",
                            color = c.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { runCatching { uri.openUri(registerUrl) } },
                        )
                    }
                }

                canWeb -> {
                    Text(
                        "这个源在网页里登录（有滑块也在网页里拖）。登录后 Cookie 会存在本机，" +
                            "之后所有请求自动带上。",
                        color = c.fg2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    if (webUrl.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        MiniButton("网页登录（带滑块）", { webLogin = true }, accent = true)
                    }
                    if (registerUrl.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "去 $name 注册",
                            color = c.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { runCatching { uri.openUri(registerUrl) } },
                        )
                    }
                }

                else -> Text(
                    "这个源不用登录，直接搜索就行。",
                    color = c.fg2,
                    fontSize = 12.5.sp,
                )
            }

            err?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = c.bad, fontSize = 11.5.sp)
            }

            Spacer(Modifier.height(22.dp))
            HLine()
            Spacer(Modifier.height(10.dp))
            Text(
                "账号密码只填在登录那一刻，登录凭证保存在本机，不上传任何服务器；换设备要重新登录。",
                color = c.fg3,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AccountInfoItems(info: JSONObject?) {
    val c = LocalColors.current
    val items = info?.optJSONArray("items")
    var shown = 0
    if (items != null) {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val t = o.optString("title")
            val v = o.optString("value")
            val act = o.optString("action")
            if (t.isBlank()) continue
            if (v.isBlank() && act.isBlank()) continue
            shown++
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                Text(t, color = c.fg3, fontSize = 11.5.sp, modifier = Modifier.width(78.dp))
                Text(
                    v.ifBlank { "需在源设置里填（App 暂不支持）" },
                    color = c.fg,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            HLine()
        }
    }
    if (shown == 0) {
        Text(
            "这个源没有回传账号信息。功能还是用不了就先退出再登录一次。",
            color = c.fg3,
            fontSize = 10.5.sp,
        )
    }
}

/**
 * 解好的一页：地址 + 源要求的请求头 + 打乱段数。
 * 三者缺一不可 —— 少了请求头，Komiic/NoyACG/绅士漫画 直接 403；
 * 少了段数，禁漫整页是错位的横条。
 */
private data class PageRef(
    val url: String,
    val headers: Map<String, String>,
    val num: Int,
)

private fun parsePage(data: JSONObject): PageRef {
    val url = data.optString("url")
    val headers = mutableMapOf<String, String>()
    data.optJSONObject("headers")?.let { h ->
        for (k in h.keys()) {
            val v = h.optString(k)
            if (v.isNotBlank()) headers[k] = v
        }
    }
    val num = data.optJSONObject("descramble")?.optInt("num") ?: 0
    return PageRef(url, headers, num)
}

// ==================================================================================== 阅读

// ============================================================== 在线阅读器的五种呈现方式

/**
 * 一页的内容：有地址就画图，失败给个能点的重试，还没解出地址就转圈。
 *
 * [fit] 决定图片怎么摆：Webtoon 按宽度铺满（长条图），相册/双页缩进屏幕（可能左右留边）。
 */
@Composable
private fun OnlinePageCell(
    index: Int,
    page: PageRef?,
    fail: String?,
    prefs: ReaderPrefs,
    fit: Boolean,
    onRetry: () -> Unit,
) {
    val boxMod = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(0.7f)
    when {
        page != null -> RemotePageImage(
            url = page.url,
            headers = page.headers,
            descramble = page.num,
            prefs = prefs,
            fit = fit,
            modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        )
        fail != null -> Box(boxMod, contentAlignment = Alignment.Center) {
            Text(
                "第 ${index + 1} 页没取到：$fail\n点这里重试",
                color = Color(0xCCFFFFFF),
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(14.dp),
            )
        }
        else -> Box(boxMod, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = Color(0xCCFF6E9C),
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 「自由放大」：整页可以双指缩放 / 双指拖动。
 *
 * 用 `transformable` 而不是 `detectTransformGestures` —— 后者会把单指手势也吃掉，
 * 竖条就再也滚不动了；transformable 只处理多指，列表滚动照常。
 */
@Composable
private fun ZoomablePage(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale <= 1.001f) Offset.Zero else offset + panChange
    }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .transformable(state),
    ) { content() }
}

/**
 * 按 [ReaderPrefs.onlineLayout] 渲染整本。
 *
 * 五种布局共用同一套「第几页 → 一个格子」的抽象，两个阅读器（地址现成 / 惰性解析）
 * 只需要提供 `pageAt` 和 `onNeed`，不用各写五遍。
 *
 * [pager] / [list] 由外层持有：底部滑条要能直接命令它们滚到某一页
 * （放在里面的话外面就没法「拖到哪翻到哪」了）。
 */
@Composable
private fun OnlinePages(
    count: Int,
    prefs: ReaderPrefs,
    pager: PagerState,
    list: LazyListState,
    current: Int,
    pageAt: (Int) -> PageRef?,
    failAt: (Int) -> String?,
    onRetry: (Int) -> Unit,
    onNeed: (Int) -> Unit,
    onPage: (Int) -> Unit,
    onTap: () -> Unit,
) {
    if (count <= 0) return
    val gap = pageGap(prefs)
    val rtl = prefs.rightToLeft
    val tap = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onTap,
    )

    // 换阅读器类型时把位置带过去：在 Webtoon 里滚到第 50 页，切相册不该又从第 1 页开始
    LaunchedEffect(prefs.onlineLayout) {
        val target = current
        when (prefs.onlineLayout) {
            ReaderLayout.PAGED -> if (target < count) pager.scrollToPage(target)
            ReaderLayout.DOUBLE -> pager.scrollToPage(target / 2)
            ReaderLayout.SLIDE, ReaderLayout.WEBTOON, ReaderLayout.FREE ->
                if (target < count) list.scrollToItem(target)
        }
    }

    when (prefs.onlineLayout) {
        ReaderLayout.WEBTOON, ReaderLayout.FREE -> {
            LaunchedEffect(list.firstVisibleItemIndex) { onPage(list.firstVisibleItemIndex) }
            val zoom = prefs.onlineLayout == ReaderLayout.FREE
            LazyColumn(
                modifier = Modifier.fillMaxSize().then(tap),
                state = list,
                verticalArrangement = if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Top,
            ) {
                items(count) { i ->
                    LaunchedEffect(i) { onNeed(i) }
                    val cell: @Composable () -> Unit = {
                        OnlinePageCell(i, pageAt(i), failAt(i), prefs, fit = false) { onRetry(i) }
                    }
                    if (zoom) ZoomablePage(cell) else cell()
                }
            }
        }

        ReaderLayout.SLIDE -> {
            LaunchedEffect(list.firstVisibleItemIndex) { onPage(list.firstVisibleItemIndex) }
            val w = LocalConfiguration.current.screenWidthDp.dp
            LazyRow(
                modifier = Modifier.fillMaxSize().then(tap),
                state = list,
                horizontalArrangement = if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Start,
            ) {
                items(count) { i ->
                    LaunchedEffect(i) { onNeed(i) }
                    Box(Modifier.width(w).fillMaxHeight()) {
                        OnlinePageCell(i, pageAt(i), failAt(i), prefs, fit = true) { onRetry(i) }
                    }
                }
            }
        }

        ReaderLayout.PAGED -> {
            LaunchedEffect(pager.currentPage) {
                onPage(pager.currentPage)
                onNeed(pager.currentPage)
            }
            HorizontalPager(
                state = pager,
                reverseLayout = rtl,
                modifier = Modifier.fillMaxSize(),
            ) { p ->
                Box(Modifier.fillMaxSize().then(tap)) {
                    OnlinePageCell(p, pageAt(p), failAt(p), prefs, fit = true) { onRetry(p) }
                }
            }
        }

        ReaderLayout.DOUBLE -> {
            LaunchedEffect(pager.currentPage) {
                onPage(pager.currentPage * 2)
                onNeed(pager.currentPage * 2)
                onNeed(pager.currentPage * 2 + 1)
            }
            HorizontalPager(
                state = pager,
                reverseLayout = rtl,
                modifier = Modifier.fillMaxSize(),
            ) { p ->
                Row(Modifier.fillMaxSize().then(tap)) {
                    val a = p * 2
                    val b = a + 1
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        OnlinePageCell(a, pageAt(a), failAt(a), prefs, fit = true) { onRetry(a) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (b < count) {
                            OnlinePageCell(b, pageAt(b), failAt(b), prefs, fit = true) { onRetry(b) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineReader(
    urls: List<String>,
    referer: String?,
    title: String,
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    onPrefs: (ReaderPrefs) -> Unit,
    onEnhance: (EnhanceSettings) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val targetPx = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val refHeaders = remember(referer) {
        if (referer.isNullOrBlank()) null else mapOf("Referer" to referer)
    }
    val refs = remember(urls, refHeaders) { urls.map { PageRef(it, refHeaders ?: emptyMap(), 0) } }
    // 预取也要按「裁不裁白边」分开，否则缓存 key 对不上，预取等于白做
    val crop = if (prefs.autoCropOnline) prefs.cropLevel + 1 else 0
    val colorize = if (prefs.colorizeOnline) (if (prefs.colorizeStyle == "realistic") "realistic" else "anime") else "0"
    var showBar by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(0) }

    val spreads = maxOf(1, (urls.size + 1) / 2)
    val pageCount = if (prefs.onlineLayout == ReaderLayout.DOUBLE) spreads else urls.size
    val pager = rememberPagerState(pageCount = { pageCount })
    val list = rememberLazyListState()

    ReaderSkinEffects(prefs)
    OnlineReaderBars(
        showBar = showBar,
        onShowBar = { showBar = it },
        onBack = onBack,
        settingsOpen = showSettings,
        chaptersOpen = false,
        onCloseSettings = { showSettings = false },
        onCloseChapters = {},
    )

    // 预取后两页：翻到哪儿就跟到哪儿，别等真的划过去才下
    LaunchedEffect(current) {
        // 预取的任何异常都不许把阅读页带崩（最多少预取一页）
        runCatching {
            for (k in 1..2) {
                val j = current + k
                if (j < urls.size) prefetchPage(urls[j], refHeaders, 0, targetPx, crop, colorize)
            }
        }
    }

    ImmersiveFullscreen()
    Box(Modifier.fillMaxSize().background(readerBg(prefs.background))) {
        OnlinePages(
            count = urls.size,
            prefs = prefs,
            pager = pager,
            list = list,
            current = current,
            pageAt = { i -> refs.getOrNull(i) },
            failAt = { null },
            onRetry = {},
            onNeed = { i ->
                urls.getOrNull(i)?.let { u ->
                    scope.launch { runCatching { prefetchPage(u, refHeaders, 0, targetPx, crop, colorize) } }
                }
            },
            onPage = { current = it },
            onTap = { if (showSettings) showSettings = false else showBar = !showBar },
        )
        if (showBar) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                OnlineTopBar(
                    title = title,
                    pageLabel = "${current + 1}/${urls.size} 页",
                    onBack = onBack,
                    onChapters = null,
                    onSettings = { showSettings = true },
                )
            }
            if (prefs.onlineLayout == ReaderLayout.PAGED ||
                prefs.onlineLayout == ReaderLayout.DOUBLE
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    OnlineSeekBar(current, urls.size) { target ->
                        scope.launch {
                            val p = if (prefs.onlineLayout == ReaderLayout.DOUBLE) target / 2 else target
                            pager.scrollToPage(p)
                        }
                    }
                }
            }
        }
        if (showSettings) {
            OnlineSettingsSheet(
                prefs = prefs,
                enhance = enhance,
                onPrefs = onPrefs,
                onEnhance = onEnhance,
                onDismiss = { showSettings = false },
            )
        }
    }
}

/**
 * 惰性阅读：翻到哪解到哪，另加两页预取。
 * E-Hentai 这类源一页要一次 API（约 1 秒），一次性预解 165 页会卡十几分钟，
 * 所以 pages() 只回页数，图片地址（含请求头、打乱参数）在真正要显示时才解。
 */
@Composable
private fun OnlineLazyReader(
    engine: SourceEngine,
    nav: Nav.ReadLazy,
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    onPrefs: (ReaderPrefs) -> Unit,
    onEnhance: (EnhanceSettings) -> Unit,
    /** 点目录里的第 i 话（由外层改 nav，阅读器整个重建） */
    onPickChapter: (Int) -> Unit = {},
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val targetPx = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val pages = remember { mutableStateMapOf<Int, PageRef>() }
    val crop = if (prefs.autoCropOnline) prefs.cropLevel + 1 else 0
    val colorize = if (prefs.colorizeOnline) (if (prefs.colorizeStyle == "realistic") "realistic" else "anime") else "0"
    val fails = remember { mutableStateMapOf<Int, String>() }
    val inflight = remember { mutableSetOf<Int>() }
    var showBar by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(0) }

    val spreads = maxOf(1, (nav.count + 1) / 2)
    val pageCount = if (prefs.onlineLayout == ReaderLayout.DOUBLE) spreads else nav.count
    val pager = rememberPagerState(pageCount = { pageCount })
    val list = rememberLazyListState()

    ReaderSkinEffects(prefs)
    OnlineReaderBars(
        showBar = showBar,
        onShowBar = { showBar = it },
        onBack = onBack,
        settingsOpen = showSettings,
        chaptersOpen = showChapters,
        onCloseSettings = { showSettings = false },
        onCloseChapters = { showChapters = false },
    )

    fun load(idx: Int) {
        if (idx < 0 || idx >= nav.count) return
        if (pages.containsKey(idx) || fails.containsKey(idx)) return
        if (!inflight.add(idx)) return
        scope.launch {
            val r = try {
                engine.pageImage(nav.key, nav.comicId, nav.epId, idx)
            } catch (e: Throwable) {
                inflight.remove(idx)
                fails[idx] = e.message ?: e.toString()
                return@launch
            }
            inflight.remove(idx)
            if (r.optBoolean("ok")) {
                val page = parsePage(r.optJSONObject("data") ?: JSONObject())
                if (page.url.isBlank()) {
                    fails[idx] = "服务端没给地址"
                } else {
                    pages[idx] = page
                    // 拿到地址就顺手把图和后两页解出来，划下去不再每页等
                    scope.launch {
                        runCatching {
                            prefetchPage(page.url, page.headers, page.num, targetPx, crop, colorize)
                            for (k in 1..2) {
                                val j = idx + k
                                if (j < nav.count) {
                                    pages[j]?.let { prefetchPage(it.url, it.headers, it.num, targetPx, crop, colorize) }
                                }
                            }
                        }
                    }
                }
            } else {
                fails[idx] = r.optString("error", "取图失败")
            }
        }
    }

    ImmersiveFullscreen()
    Box(Modifier.fillMaxSize().background(readerBg(prefs.background))) {
        OnlinePages(
            count = nav.count,
            prefs = prefs,
            pager = pager,
            list = list,
            current = current,
            pageAt = { i -> pages[i] },
            failAt = { i -> fails[i] },
            onRetry = { i ->
                fails.remove(i)
                load(i)
            },
            onNeed = { i -> load(i) },
            onPage = { current = it },
            onTap = {
                if (showSettings) showSettings = false
                else if (showChapters) showChapters = false
                else showBar = !showBar
            },
        )
        if (showBar) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                OnlineTopBar(
                    title = nav.title,
                    pageLabel = "${current + 1}/${nav.count} 页",
                    onBack = onBack,
                    onChapters = if (nav.chapters.isEmpty()) null else {
                        { showChapters = true }
                    },
                    onSettings = { showSettings = true },
                )
            }
            if (prefs.onlineLayout == ReaderLayout.PAGED ||
                prefs.onlineLayout == ReaderLayout.DOUBLE
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    OnlineSeekBar(current, nav.count) { target ->
                        scope.launch {
                            val p = if (prefs.onlineLayout == ReaderLayout.DOUBLE) target / 2 else target
                            pager.scrollToPage(p)
                        }
                    }
                }
            }
        }
        if (showSettings) {
            OnlineSettingsSheet(
                prefs = prefs,
                enhance = enhance,
                onPrefs = onPrefs,
                onEnhance = onEnhance,
                onDismiss = { showSettings = false },
            )
        }
        if (showChapters) {
            OnlineChapterSheet(
                chapters = nav.chapters,
                index = nav.index,
                onPick = onPickChapter,
                onDismiss = { showChapters = false },
            )
        }
    }
}

@Composable
private fun OnlineReaderBars(
    showBar: Boolean,
    onShowBar: (Boolean) -> Unit,
    onBack: () -> Unit,
    settingsOpen: Boolean,
    chaptersOpen: Boolean,
    onCloseSettings: () -> Unit,
    onCloseChapters: () -> Unit,
) {
    LaunchedEffect(showBar, settingsOpen, chaptersOpen) {
        if (showBar && !settingsOpen && !chaptersOpen) {
            kotlinx.coroutines.delay(4000)
            onShowBar(false)
        }
    }
    BackHandler {
        when {
            settingsOpen -> onCloseSettings()
            chaptersOpen -> onCloseChapters()
            !showBar -> onShowBar(true)
            else -> onBack()
        }
    }
}

/**
 * 阅读器外观：亮度 + 常亮。
 *
 * 以前只有本地阅读器会改屏幕亮度，在线阅读点「设置 → 亮度」完全没反应 ——
 * 设置是同一份，行为也该一致；退出阅读页时把亮度还原，别把系统亮度锁死。
 */
@Composable
private fun ReaderSkinEffects(prefs: ReaderPrefs) {
    val view = LocalView.current
    val ctx = LocalContext.current
    LaunchedEffect(prefs.autoBrightness, prefs.brightness) {
        val w = (ctx as? android.app.Activity)?.window ?: return@LaunchedEffect
        val lp = w.attributes
        lp.screenBrightness = if (prefs.autoBrightness) {
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            (prefs.brightness / 100f).coerceIn(0.05f, 1f)
        }
        w.attributes = lp
    }
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // 亮度还原必须挂在 Unit 上：挂在 keepScreenOn 上时，一改「常亮」就会把亮度还原，
    // 而 LaunchedEffect 的 key 没变、不会重新设置 —— 调好的亮度白调。
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val w = (ctx as? android.app.Activity)?.window ?: return@runCatching
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }
}

/** 阅读页底色（设置里那三个：纯黑 / 深灰 / 米白） */
private fun readerBg(index: Int): Color = when (index) {
    0 -> Color(0xFF000000)
    1 -> Color(0xFF16161A)
    else -> Color(0xFFEDEDE6)
}

/** 页面间距：设置里存的是「视口高度的百分比」，换成 dp */
@Composable
private fun pageGap(prefs: ReaderPrefs): Dp {
    val h = LocalConfiguration.current.screenHeightDp
    return (h * prefs.pageGapPercent / 100f).dp
}

// ==================================================================================== 网页登录

/**
 * 网页登录：登录 WebView 在独立进程 WebLoginActivity（android:process=":login"）里跑。
 * 主进程里源引擎的 JS 桥 WebView 建在后台 Looper 线程上，Chromium 只允许在一个线程
 * 初始化 —— 主进程再建登录 WebView 就是 native 崩溃，try/catch 都接不住
 * （1.9.34 防御式创建实测无效）。所以这里只负责：拉起独立进程 → 等回来 →
 * 从交接文件 web_login.json 里读 Cookie 交给引擎。
 * 绅士漫画（wnacg）必须这么登 —— 账号密码接口会被滑块拦死。
 */
@Composable
private fun WebLoginScreen(
    engine: SourceEngine,
    key: String,
    url: String,
    name: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        // 从独立进程回来了：读交接文件 → 交给引擎 → 删文件
        busy = true
        scope.launch {
            val f = webLoginResultFile(ctx)
            val read = runCatching {
                if (f.exists()) org.json.JSONObject(f.readText()) else null
            }.getOrNull()
            runCatching { f.delete() }
            val j = read
            when {
                j == null -> {
                    busy = false
                    msg = "没有收到登录结果：请在网页里完成登录后，点登录页右上角「已完成」。"
                }
                j.optString("key") != key -> {
                    busy = false
                    msg = "登录结果和当前源对不上，请重试一次。"
                }
                else -> {
                    val cookies = j.optString("cookies")
                    val loginUrl = j.optString("url", url).ifBlank { url }
                    val r = engine.loginWithCookieString(key, loginUrl, cookies)
                    busy = false
                    if (r.optBoolean("ok")) {
                        onDone()
                    } else {
                        msg = r.optString("error", "登录失败")
                    }
                }
            }
        }
    }

    // 进这个页面就拉起独立进程登录页
    LaunchedEffect(Unit) {
        runCatching {
            launcher.launch(com.enhance.comic.WebLoginActivity.intentOf(ctx, key, url, name))
        }.onFailure {
            msg = "这台设备打不开内置登录页：${it.javaClass.simpleName}"
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        SubTopBar("网页登录 · $name", onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (busy) {
                LoadingBox(96.dp)
                Spacer(Modifier.height(14.dp))
                Text("正在收回登录状态…", color = c.fg2, fontSize = 12.sp)
            } else {
                Text(
                    "登录页在单独的窗口里打开。\n在网页里完成登录（有滑块就拖一下），\n" +
                        "然后点那个窗口右上角的「已完成」，\n回到这里登录状态会自动收回。",
                    color = c.fg2,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                MiniButton("再打开一次登录窗口", {
                    if (busy) return@MiniButton
                    runCatching {
                        launcher.launch(com.enhance.comic.WebLoginActivity.intentOf(ctx, key, url, name))
                    }
                }, accent = true)
            }
            msg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = c.bad, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** Cookie 交接文件：与 WebLoginActivity（:login 进程）写入的是同一路径 */
private fun webLoginResultFile(ctx: android.content.Context): java.io.File =
    java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir,
        com.enhance.comic.WebLoginActivity.RESULT_FILE)
