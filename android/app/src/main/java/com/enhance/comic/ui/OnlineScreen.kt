package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.SourceEngine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 源的展示信息 */
data class SourceRow(
    val key: String,
    val name: String,
    val host: String,
    val line: String,
    val label: String,
    val note: String,
    val canLogin: Boolean = false,
    val logged: Boolean = false,
    val needLogin: Boolean = false,
)

data class ComicRow(val id: String, val title: String, val cover: String, val sub: String)
data class ChapterRow(val id: String, val title: String)

private sealed class Nav {
    object Sources : Nav()
    data class Browse(val key: String, val title: String) : Nav()
    data class Info(val key: String, val id: String, val title: String) : Nav()
    data class Read(val urls: List<String>, val referer: String?, val title: String) : Nav()
    /** 惰性取图：只给页数，每页真的地址由阅读器按需解 */
    data class ReadLazy(val key: String, val comicId: String, val epId: String,
                        val count: Int, val title: String) : Nav()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSourceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { SourceEngine.get(context) }
    val scope = rememberCoroutineScope()
    var nav by remember { mutableStateOf<Nav>(Nav.Sources) }

    when (val n = nav) {
        is Nav.Sources -> {
            var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
            var busy by remember { mutableStateOf(true) }
            var err by remember { mutableStateOf<String?>(null) }
            var loginFor by remember { mutableStateOf<SourceRow?>(null) }
            var toast by remember { mutableStateOf<String?>(null) }
            var reloadTick by remember { mutableStateOf(0) }
            var probed by remember { mutableStateOf(false) }

            LaunchedEffect(reloadTick) {
                busy = rows.isEmpty()
                // 先把源列表显示出来（不等探测），探测结果回来再刷新
                runCatching {
                    rows = buildRows(engine.sources(), engine.routeStatus())
                }.onFailure { err = it.message }
                busy = false
                if (!probed) {
                    probed = true
                    runCatching {
                        engine.probeRoutes()
                        rows = buildRows(engine.sources(), engine.routeStatus())
                    }
                }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("在线漫画源", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                    )
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    if (busy && rows.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Text(
                            "能用直连的走直连；标「需 VPN」的打开代理后自动可用，切换不用重启。" +
                                "点右边的「登录」填这个站的账号，登录后点书名进目录。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { it.key }) { r ->
                                SourceCard(
                                    r = r,
                                    onClick = { nav = Nav.Browse(r.key, r.name) },
                                    onLogin = { loginFor = r },
                                )
                            }
                        }
                    }
                    err?.let {
                        Text("加载失败：$it", color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    }
                    toast?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            loginFor?.let { r ->
                LoginDialog(
                    engine = engine,
                    row = r,
                    onDismiss = { loginFor = null },
                    onDone = { msg ->
                        loginFor = null
                        toast = msg
                        reloadTick++
                    },
                )
            }
        }

        is Nav.Browse -> BrowseScreen(engine, n.key, n.title,
            onBack = { nav = Nav.Sources },
            onOpen = { id, title -> nav = Nav.Info(n.key, id, title) })

        is Nav.Info -> DetailScreen(engine, n.key, n.id, n.title,
            onBack = { nav = Nav.Browse(n.key, n.title) },
            onRead = { comicId, epId, data, t ->
                nav = if (data.optBoolean("lazy")) {
                    Nav.ReadLazy(n.key, comicId, epId, data.optInt("count"), t)
                } else {
                    val imgs = data.optJSONArray("images")
                    val urls = buildList {
                        if (imgs != null) for (i in 0 until imgs.length()) {
                            imgs.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                    Nav.Read(urls, null, t)
                }
            })

        is Nav.Read -> OnlineReader(n.urls, n.referer, n.title, onBack = { nav = Nav.Sources })

        is Nav.ReadLazy -> OnlineLazyReader(engine, n, onBack = { nav = Nav.Sources })

    }
}

private fun buildRows(srcs: JSONArray, st: JSONArray): List<SourceRow> {
    val byKey = HashMap<String, JSONObject>()
    for (i in 0 until st.length()) st.optJSONObject(i)?.let { byKey[it.optString("key")] = it }
    val out = mutableListOf<SourceRow>()
    for (i in 0 until srcs.length()) {
        val s = srcs.optJSONObject(i) ?: continue
        val key = s.optString("key")
        val r = byKey[key]
        val line = r?.optString("line") ?: ""
        out += SourceRow(
            key = key,
            name = s.optString("name", key),
            host = r?.optString("host") ?: "",
            line = line,
            label = if (line == "direct") "直连" else if (line == "proxy") "需 VPN" else "",
            note = r?.optString("note") ?: "",
            canLogin = s.optBoolean("canLogin"),
            logged = s.optBoolean("logged"),
            needLogin = s.optBoolean("needLogin"),
        )
    }
    return out
}

@Composable
private fun SourceCard(r: SourceRow, onClick: () -> Unit, onLogin: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(r.name.take(1), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                if (r.host.isBlank()) "未检测" else r.host,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (r.label.isNotEmpty()) {
            val direct = r.line == "direct"
            Text(
                r.label,
                fontSize = 11.sp,
                color = if (direct) Color(0xFF2E7D32) else Color(0xFFB26A00),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (direct) Color(0x1A2E7D32) else Color(0x1AB26A00)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (r.canLogin) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (r.logged) "已登录" else "登录",
                fontSize = 11.sp,
                color = if (r.logged) Color(0xFF2E7D32) else Color(0xFF1565C0),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (r.logged) Color(0x1A2E7D32) else Color(0x1A1565C0))
                    .clickable { onLogin() }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

/** 登录表单：账号 + 密码 → 源脚本的 account.login()。登录态存在宿主 KV 里，重启还在。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginDialog(
    engine: SourceEngine,
    row: SourceRow,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
        ) {
            Text("登录 ${row.name}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "账号密码只会存在本机，不会上传。登录后这个源才能搜索/看图。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("账号 / 邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            err?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.logged) {
                    Text(
                        "退出登录",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable {
                            if (busy) return@clickable
                            scope.launch {
                                busy = true
                                engine.logout(row.key)
                                busy = false
                                onDone("${row.name} 已退出登录")
                            }
                        }.padding(6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "取消",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { if (!busy) onDismiss() }.padding(8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (busy) "登录中…" else "登录",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (busy || user.isBlank() || pass.isBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            if (busy || user.isBlank() || pass.isBlank()) return@clickable
                            scope.launch {
                                busy = true; err = null
                                val r = engine.login(row.key, user.trim(), pass)
                                busy = false
                                if (r.optBoolean("ok")) onDone("${row.name} 登录成功")
                                else err = "登录失败：" + r.optString("error", "未知原因")
                            }
                        }
                        .padding(8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(
    engine: SourceEngine,
    key: String,
    title: String,
    onBack: () -> Unit,
    onOpen: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var kw by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<ComicRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var didLoad by remember { mutableStateOf(false) }

    fun doSearch(q: String) {
        if (busy) return
        scope.launch {
            busy = true; err = null
            val r = engine.search(key, q, "[]", 1)
            busy = false
            if (r.optBoolean("ok")) {
                list = parseComics(r.optJSONObject("data"))
                if (list.isEmpty()) err = "没有结果"
            } else {
                err = r.optString("error", "搜索失败")
            }
        }
    }

    // 进来先给一屏内容：搜索框空着就取探索页
    LaunchedEffect(Unit) {
        if (didLoad) return@LaunchedEffect
        didLoad = true
        busy = true
        val r = engine.explore(key, 0)
        busy = false
        if (r.optBoolean("ok")) list = parseComics(r.optJSONObject("data"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = kw,
                onValueChange = { kw = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索关键词") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (kw.isNotBlank()) doSearch(kw) }),
            )
            if (busy) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { c ->
                    Column(Modifier.clickable { onOpen(c.id, c.title) }) {
                        RemoteImage(
                            url = c.cover,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Text(c.title, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}

private fun parseComics(data: JSONObject?): List<ComicRow> {
    data ?: return emptyList()
    // explore 返回的是 {分区: [comic]}，search 返回 {comics: [...]}
    val arr = data.optJSONArray("comics") ?: run {
        val merged = JSONArray()
        // 探索页：取第一个非空分区
        val keys = data.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = data.optJSONArray(k) ?: continue
            for (i in 0 until v.length()) merged.put(v.get(i))
            if (merged.length() > 0) break
        }
        merged
    }
    val out = mutableListOf<ComicRow>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out += ComicRow(
            id = o.optString("id"),
            title = o.optString("title"),
            cover = o.optString("cover"),
            sub = o.optString("subTitle"),
        )
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(
    engine: SourceEngine,
    key: String,
    id: String,
    title: String,
    onBack: () -> Unit,
    onRead: (String, String, JSONObject, String) -> Unit,
) {
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        busy = true
        val r = engine.detail(key, id)
        busy = false
        if (r.optBoolean("ok")) d = r.optJSONObject("data")
        else err = r.optString("error", "加载失败")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val info = d
            if (busy || info == null) {
                if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(16.dp)) {
                        RemoteImage(
                            url = info.optString("cover"),
                            modifier = Modifier.width(96.dp).aspectRatio(0.72f).clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(info.optString("title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                info.optString("description").replace(Regex("\\s+"), " ").take(160),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val groups = info.optJSONArray("groups") ?: JSONArray()
                    if (groups.length() == 0) {
                        Text("这本没有解析到章节（可能需要登录，或该源改版了）",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        for (g in 0 until groups.length()) {
                            val grp = groups.optJSONObject(g) ?: continue
                            val gt = grp.optString("title")
                            if (gt.isNotBlank()) {
                                item { Text(gt, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                            }
                            val arr = grp.optJSONArray("items") ?: continue
                            val chs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                            items(chs, key = { it.optString("id") }) { ch ->
                                val cid = ch.optString("id")
                                Text(
                                    ch.optString("title").ifBlank { cid },
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (pending != null) return@clickable
                                            pending = cid
                                            scope.launch {
                                                val r = engine.pages(key, id, cid)
                                                pending = null
                                                if (r.optBoolean("ok")) {
                                                    val data = r.optJSONObject("data") ?: JSONObject()
                                                    val n = if (data.optBoolean("lazy")) data.optInt("count")
                                                            else data.optJSONArray("images")?.length() ?: 0
                                                    if (n == 0) err = "这一话没有图片（可能需要登录）"
                                                    else onRead(id, cid, data, ch.optString("title"))
                                                } else err = r.optString("error", "取图失败")
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (pending != null) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun OnlineReader(urls: List<String>, referer: String?, title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(urls) { idx, u ->
                RemoteImage(
                    url = u,
                    referer = referer,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        // 顶部条：返回 + 页码提示
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(title.ifBlank { "阅读" }, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${urls.size} 页", color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
    }
}

/**
 * 惰性阅读：翻到哪解到哪，另加一页预取。
 * E-Hentai 这类源一页要一次 API（约 1 秒），一次性预解 165 页会卡十几分钟，
 * 所以 pages() 只回页数，图片地址在真正要显示时才解。
 */
@Composable
private fun OnlineLazyReader(
    engine: SourceEngine,
    nav: Nav.ReadLazy,
    onBack: () -> Unit,
) {
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
                // 显示到这一页时才解它，顺便把下一页也预取上
                LaunchedEffect(idx) { load(idx); load(idx + 1) }
                val u = pages[idx]
                val f = fails[idx]
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.7f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        u != null -> RemoteImage(
                            url = u,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        f != null -> Text(
                            "第 ${idx + 1} 页没取到：$f\n点这里重试",
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { fails.remove(idx); load(idx) }
                                .padding(14.dp),
                        )
                        else -> CircularProgressIndicator(
                            color = Color(0xCCFFFFFF), strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(nav.title.ifBlank { "阅读" }, color = Color.White, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            Spacer(Modifier.weight(1f))
            Text("${pages.size}/${nav.count} 页", color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
    }
}
