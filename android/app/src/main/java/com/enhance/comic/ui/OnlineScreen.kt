package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.OnlineRecord
import com.enhance.comic.data.SourceEngine
import com.enhance.comic.data.UiStore
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
    onRecordsChanged: () -> Unit = {},
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
            onBack = onBack,
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
            onRead = { comicId, epId, data, t ->
                val back = Nav.Info(n.key, n.id, n.title, n.alts)
                nav = if (data.optBoolean("lazy")) {
                    Nav.ReadLazy(n.key, comicId, epId, data.optInt("count"), t, back)
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

        is Nav.Read -> OnlineReader(n.urls, n.referer, n.title, onBack = { nav = n.back })

        is Nav.ReadLazy -> OnlineLazyReader(engine, n, onBack = { nav = n.back })
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

@Composable
private fun SourcesScreen(
    engine: SourceEngine,
    onBack: () -> Unit,
    onOpen: (String, String) -> Unit,
    onAccount: (String, String) -> Unit,
) {
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var probed by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        busy = rows.isEmpty()
        // 先把源列表显示出来（不等探测），探测结果回来再刷新
        runCatching { rows = buildRows(engine.sources(), engine.routeStatus()) }
            .onFailure { err = it.message }
        busy = false
        if (!probed) {
            probed = true
            runCatching {
                // tick>0 说明是用户主动点「重新检测」，必须重新探
                engine.probeRoutes(force = tick > 0)
                rows = buildRows(engine.sources(), engine.routeStatus())
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar("漫画源", onBack, action = {
            MiniButton("重新检测", onClick = { probed = false; tick++ })
        })
        if (busy && rows.isEmpty()) {
            LoadingBox(220.dp)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    SectionHeader("全部源", more = routeSummary(rows))
                }
                items(rows, key = { it.key }) { r ->
                    SourceRowItem(
                        r = r,
                        onClick = { onOpen(r.key, r.name) },
                        onLogin = { onAccount(r.key, r.name) },
                    )
                }
                item {
                    Text(
                        "「直连」= 这台机器能直接打开；「需 VPN」= 要开代理；" +
                            "「不通」= 两条线路都没探通，开关代理后点「重新检测」。",
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
private fun SourceRowItem(r: SourceRow, onClick: () -> Unit, onLogin: () -> Unit) {
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
                color = c.fg,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.name,
                color = c.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                r.offline -> "两条线路都没探通"
                r.needLogin && !r.logged -> "登录后才能看内容"
                r.canLogin && !r.logged -> "需要账号 · 点右边登录"
                r.logged -> "已登录"
                r.note.isNotBlank() -> r.note
                else -> r.host.ifBlank { "公开内容可直接看" }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                sub,
                color = c.fg3,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            r.offline -> Pill("不通", Tone.BAD)
            !r.picked -> Pill("检测中", Tone.PLAIN)
            r.line == "direct" -> Pill("直连", Tone.OK)
            r.line == "proxy" -> Pill("需 VPN", Tone.WARN)
            else -> Pill("未探", Tone.PLAIN)
        }
        if (r.canLogin) {
            Spacer(Modifier.width(7.dp))
            if (r.logged) {
                Text(
                    "已登录",
                    color = c.ok,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(c.okBg)
                        .clickable(onClick = onLogin)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            } else {
                MiniButton("登录", onLogin)
            }
        }
    }
    HLine(Modifier.padding(start = 68.dp))
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

    LaunchedEffect(tick) {
        busy = true
        err = null
        runCatching {
            val a = engine.account(key)
            if (a.optBoolean("ok")) {
                val d = a.optJSONObject("data")
                logged = d?.optBoolean("logged") == true
                canLogin = d?.optBoolean("canLogin") != false
            }
        }
        val r = engine.explore(key, 0)
        busy = false
        if (r.optBoolean("ok")) {
            sections = parseSections(r.optJSONObject("data"))
            if (sections.isEmpty()) searched = false
        } else {
            err = r.optString("error", "加载失败")
        }
    }

    fun doSearch(q: String) {
        if (busy || q.isBlank()) return
        scope.launch {
            busy = true
            err = null
            val r = engine.search(key, q, "[]", 1)
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
                            SectionHeader(sec.title, more = "${sec.comics.size} 本")
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
    onRead: (String, String, JSONObject, String) -> Unit,
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
        val r = engine.detail(key, id)
        busy = false
        if (r.optBoolean("ok")) d = r.optJSONObject("data")
        else err = r.optString("error", "加载失败")
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
                if (cid.isBlank()) continue
                out += Chap(cid, o.optString("title").ifBlank { cid }, out.size)
            }
        }
        out
    }
    val last = remember(id, chapters.size) { uiStore?.lastChapter(key, id) }

    fun openChapter(cid: String, epTitle: String) {
        if (pending != null) return
        pending = cid
        scope.launch {
            val r = engine.pages(key, id, cid)
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
            onRead(id, cid, data, epTitle)
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
    var route by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginErr by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val r = engine.account(key)
        if (r.optBoolean("ok")) {
            val d = r.optJSONObject("data")
            info = d
            err = null
            val saved = d?.optString("savedAccount").orEmpty()
            if (saved.isNotBlank() && user.isBlank()) user = saved
        } else {
            err = r.optString("error", "账号信息读取失败")
        }
        runCatching {
            val st = engine.routeStatus()
            for (i in 0 until st.length()) {
                val o = st.optJSONObject(i) ?: continue
                if (o.optString("key") == key) {
                    route = o
                    break
                }
            }
        }
    }

    LaunchedEffect(key) {
        busy = true
        refresh()
        busy = false
    }

    fun doLogin() {
        if (loginBusy || user.isBlank() || pass.isBlank()) return
        scope.launch {
            loginBusy = true
            loginErr = null
            notice = null
            val r = engine.login(key, user.trim(), pass)
            loginBusy = false
            if (r.optBoolean("ok")) {
                pass = ""
                notice = "登录成功"
                refresh()
            } else {
                val code = r.optString("code")
                val raw = r.optString("error", "账号或密码不对")
                loginErr = when (code) {
                    "net" -> "登录失败：连不上这个源（当前线路不通）。确认代理开关后，" +
                        "回源列表点「重新检测」再试。"
                    "forbidden" -> "登录失败：站点拒绝了请求（403），多半是云防护拦了，开/关代理后重试。"
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
    val line = route?.optString("line").orEmpty()
    val routePick = route?.optBoolean("pick") == true
    val routeOffline = route?.optBoolean("offline") == true

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
                        buildString {
                            append(
                                when {
                                    routeOffline -> "线路不通"
                                    !routePick -> "检测中"
                                    line == "direct" -> "直连"
                                    line == "proxy" -> "需 VPN"
                                    else -> "线路未检测"
                                },
                            )
                            append(" · ")
                            append(if (logged) "已登录" else if (canLogin) "未登录" else "无需登录")
                        },
                        color = if (routeOffline) c.bad else c.fg3,
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
                        info?.optString("savedAccount").orEmpty().ifBlank { "（该源未回传账号名）" },
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
                        "这个源不支持账号密码，登录在网页里完成（靠 Cookie）。" +
                            "公开内容不登录也能看。",
                        color = c.fg2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    if (webUrl.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        MiniButton("打开登录网页", { runCatching { uri.openUri(webUrl) } }, accent = true)
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

// ==================================================================================== 阅读

@Composable
private fun OnlineReader(urls: List<String>, referer: String?, title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(urls.size) { idx ->
                RemotePageImage(
                    url = urls[idx],
                    referer = referer,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                )
            }
        }
        ReaderOverlayBar(title, "${urls.size} 页", onBack)
    }
}

/**
 * 惰性阅读：翻到哪解到哪，另加一页预取。
 * E-Hentai 这类源一页要一次 API（约 1 秒），一次性预解 165 页会卡十几分钟，
 * 所以 pages() 只回页数，图片地址在真正要显示时才解。
 */
@Composable
private fun OnlineLazyReader(engine: SourceEngine, nav: Nav.ReadLazy, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateMapOf<Int, String>() }
    val fails = remember { mutableStateMapOf<Int, String>() }
    val inflight = remember { mutableSetOf<Int>() }

    fun load(idx: Int) {
        if (idx < 0 || idx >= nav.count) return
        if (pages.containsKey(idx) || fails.containsKey(idx)) return
        if (!inflight.add(idx)) return
        scope.launch {
            val r = engine.pageImage(nav.key, nav.comicId, nav.epId, idx)
            inflight.remove(idx)
            if (r.optBoolean("ok")) {
                val u = r.optJSONObject("data")?.optString("url").orEmpty()
                if (u.isBlank()) fails[idx] = "服务端没给地址" else pages[idx] = u
            } else {
                fails[idx] = r.optString("error", "取图失败")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(nav.count) { idx ->
                LaunchedEffect(idx) {
                    load(idx)
                    load(idx + 1)
                }
                val u = pages[idx]
                val f = fails[idx]
                Box(Modifier.fillMaxWidth().aspectRatio(0.7f), contentAlignment = Alignment.Center) {
                    when {
                        u != null -> RemotePageImage(u, modifier = Modifier.fillMaxSize())
                        f != null -> Text(
                            "第 ${idx + 1} 页没取到：$f\n点这里重试",
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable {
                                    fails.remove(idx)
                                    load(idx)
                                }
                                .padding(14.dp),
                        )
                        else -> CircularProgressIndicator(
                            color = Color(0xCCFF6E9C),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        ReaderOverlayBar(nav.title, "${pages.size}/${nav.count} 页", onBack)
    }
}

@Composable
private fun ReaderOverlayBar(title: String, pageLabel: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .statusBarsPadding()
            .height(44.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.BACK, Color.White, 20.dp) }
        Text(
            title.ifBlank { "阅读" },
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(pageLabel, color = Color(0xCCFFFFFF), fontSize = 11.5.sp, modifier = Modifier.padding(end = 10.dp))
    }
}
