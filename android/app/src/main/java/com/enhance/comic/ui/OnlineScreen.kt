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
)

data class ComicRow(val id: String, val title: String, val cover: String, val sub: String)
data class ChapterRow(val id: String, val title: String)

private sealed class Nav {
    object Sources : Nav()
    data class Browse(val key: String, val title: String) : Nav()
    data class Info(val key: String, val id: String, val title: String) : Nav()
    data class Read(val urls: List<String>, val referer: String?, val title: String) : Nav()
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

            LaunchedEffect(Unit) {
                busy = true
                // 先把源列表显示出来（不等探测），探测结果回来再刷新
                runCatching {
                    val srcs = engine.sources()
                    val st = engine.routeStatus()
                    rows = buildRows(srcs, st)
                }.onFailure { err = it.message }
                busy = false
                runCatching {
                    engine.probeRoutes()
                    val srcs = engine.sources()
                    val st = engine.routeStatus()
                    rows = buildRows(srcs, st)
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
                            "能用直连的走直连；标「需 VPN」的打开代理后自动可用，切换不用重启。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { it.key }) { r ->
                                SourceCard(r) { nav = Nav.Browse(r.key, r.name) }
                            }
                        }
                    }
                    err?.let {
                        Text("加载失败：$it", color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        is Nav.Browse -> BrowseScreen(engine, n.key, n.title,
            onBack = { nav = Nav.Sources },
            onOpen = { id, title -> nav = Nav.Info(n.key, id, title) })

        is Nav.Info -> DetailScreen(engine, n.key, n.id, n.title,
            onBack = { nav = Nav.Browse(n.key, n.title) },
            onRead = { urls, ref, t -> nav = Nav.Read(urls, ref, t) })

        is Nav.Read -> OnlineReader(n.urls, n.referer, n.title, onBack = { nav = Nav.Sources })
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
        )
    }
    return out
}

@Composable
private fun SourceCard(r: SourceRow, onClick: () -> Unit) {
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
    onRead: (List<String>, String?, String) -> Unit,
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
                                                    val data = r.optJSONObject("data")
                                                    val imgs = data?.optJSONArray("images")
                                                    val urls = buildList {
                                                        if (imgs != null) for (i in 0 until imgs.length()) {
                                                            imgs.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                                                        }
                                                    }
                                                    if (urls.isEmpty()) err = "这一话没有图片（可能需要登录）"
                                                    else onRead(urls, null, ch.optString("title"))
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
