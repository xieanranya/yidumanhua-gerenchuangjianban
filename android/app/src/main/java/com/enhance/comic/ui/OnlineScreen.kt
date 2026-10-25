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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
    /** 探测确认所有通道都不通 */
    val offline: Boolean = false,
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
    /** 独立的账号页：登录 / 退出 / 看账号信息，退出后回到来的那一页 */
    data class Account(val key: String, val name: String, val back: Nav) : Nav()
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
                        // reloadTick>0 说明是用户主动点「重新检测」，必须重新探
                        engine.probeRoutes(force = reloadTick > 0)
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
                        actions = {
                            // 开关代理之后线路会变，必须能手动重探，不能等缓存过期
                            TextButton(onClick = { probed = false; reloadTick++ }) {
                                Icon(Icons.Default.Refresh, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重新检测", fontSize = 13.sp)
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
                            "「直连」= 探测确认这台机器的网络能直接打开；「需 VPN」= 要开代理；" +
                                "「不通」= 两条线路都没探通，开关代理后点右上角「重新检测」。" +
                                "要登录就点右边的「登录」。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { it.key }) { r ->
                                SourceCard(
                                    r = r,
                                    onClick = { nav = Nav.Browse(r.key, r.name) },
                                    onLogin = { nav = Nav.Account(r.key, r.name, Nav.Sources) },
                                )
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

        is Nav.Browse -> BrowseScreen(
            engine = engine,
            key = n.key,
            title = n.title,
            onBack = { nav = Nav.Sources },
            onAccount = { name -> nav = Nav.Account(n.key, name, Nav.Browse(n.key, n.title)) },
            onOpen = { id, title -> nav = Nav.Info(n.key, id, title) },
        )

        is Nav.Info -> DetailScreen(engine, n.key, n.id, n.title,
            onBack = { nav = Nav.Browse(n.key, n.title) },
            onAccount = { nav = Nav.Account(n.key, n.title, Nav.Info(n.key, n.id, n.title)) },
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

        is Nav.Account -> AccountScreen(
            engine = engine,
            key = n.key,
            fallbackName = n.name,
            onBack = { nav = n.back },
        )

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
        // pick=false 说明 ci/host 还只是线路表里的默认值，不是探测结果。
        // 旧代码直接把默认值当「直连」显示，于是出现「标着直连、其实打的是被墙域名」。
        val picked = r?.optBoolean("pick") == true
        val offline = r?.optBoolean("offline") == true
        out += SourceRow(
            key = key,
            name = s.optString("name", key),
            host = r?.optString("host") ?: "",
            line = line,
            label = when {
                offline -> "不通"
                !picked -> "检测中"
                line == "direct" -> "直连"
                line == "proxy" -> "需 VPN"
                else -> ""
            },
            note = r?.optString("note") ?: "",
            canLogin = s.optBoolean("canLogin"),
            logged = s.optBoolean("logged"),
            needLogin = s.optBoolean("needLogin"),
            offline = offline,
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
            val tone = when {
                r.offline -> Color(0xFFC62828)
                r.label == "直连" -> Color(0xFF2E7D32)
                r.label == "检测中" -> Color(0xFF6B6B6B)
                else -> Color(0xFFB26A00)
            }
            Text(
                r.label,
                fontSize = 11.sp,
                color = tone,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tone.copy(alpha = 0.10f))
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

/** 独立账号页：登录 / 退出 / 看账号信息。照着 PicACG 那种「先登录再进内容」的习惯做的。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountScreen(
    engine: SourceEngine,
    key: String,
    fallbackName: String,
    onBack: () -> Unit,
) {
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
                if (o.optString("key") == key) { route = o; break }
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
            loginBusy = true; loginErr = null; notice = null
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
                        "回源列表点右上角「重新检测」再试。"
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
    val host = route?.optString("host").orEmpty()
    val routePick = route?.optBoolean("pick") == true
    val routeOffline = route?.optBoolean("offline") == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // 源信息头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            host.ifBlank { null },
                            when {
                                routeOffline -> "不通"
                                !routePick -> "检测中"
                                line == "direct" -> "直连"
                                line == "proxy" -> "需 VPN"
                                else -> null
                            },
                        ).joinToString(" · ").ifBlank { "线路未检测" },
                        fontSize = 11.sp,
                        color = if (routeOffline) Color(0xFFC62828)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val stateColor = when {
                logged -> Color(0xFF2E7D32)
                canLogin -> Color(0xFFB26A00)
                canWeb -> Color(0xFFB26A00)
                else -> Color(0xFF616161)
            }
            val stateText = when {
                logged -> "已登录"
                canLogin -> "未登录"
                canWeb -> "网页登录"
                else -> "该源不用登录"
            }
            Text(
                stateText,
                fontSize = 11.sp,
                color = stateColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(stateColor.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(18.dp))

            when {
                busy -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }

                canLogin -> {
                    if (logged) {
                        Text("已登录账号", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            info?.optString("savedAccount").orEmpty().ifBlank { "（该源未回传账号名）" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(16.dp))
                        AccountInfoItems(info)
                        Spacer(Modifier.height(20.dp))
                        notice?.let {
                            Text(it, fontSize = 12.sp, color = Color(0xFF2E7D32))
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                if (loginBusy) return@OutlinedButton
                                scope.launch {
                                    loginBusy = true; loginErr = null; notice = null
                                    engine.logout(key)
                                    loginBusy = false
                                    pass = ""
                                    notice = "已退出登录"
                                    refresh()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("账号", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            label = { Text("账号 / 邮箱") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
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
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { doLogin() },
                            enabled = !loginBusy && user.isNotBlank() && pass.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            if (loginBusy) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("登录中…")
                            } else {
                                Text("登录")
                            }
                        }
                        if (registerUrl.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            TextButton(
                                onClick = { runCatching { uri.openUri(registerUrl) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("还没有账号？去 $name 注册") }
                        }
                    }
                }

                canWeb -> {
                    Text(
                        "这个源不支持账号密码，登录是在网页里完成的（靠 Cookie）。" +
                            "App 内暂时没法替你填，公开内容不登录也能看。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                    if (webUrl.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { runCatching { uri.openUri(webUrl) } },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) { Text("打开登录网页") }
                    }
                    if (registerUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { runCatching { uri.openUri(registerUrl) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("去 $name 注册") }
                    }
                }

                else -> Text(
                    "这个源不用登录，直接搜索就行。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            err?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "账号密码只填在登录那一刻，登录凭证保存在本机，不上传任何服务器；" +
                    "换设备要重新登录。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 源自己声明的账号信息（account.infoItems），禁漫/PicACG 会回等级、积分这些 */
@Composable
private fun AccountInfoItems(info: JSONObject?) {
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
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp),
                )
                Text(
                    v.ifBlank { "需在源设置里填（App 暂不支持）" },
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
    if (shown == 0) {
        Text(
            "这个源没有回传账号信息。如果功能还是用不了，先退出再登录一次。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


private data class ExploreSection(val title: String, val comics: List<ComicRow>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(
    engine: SourceEngine,
    key: String,
    title: String,
    onBack: () -> Unit,
    onAccount: (String) -> Unit,
    onOpen: (String, String) -> Unit,
) {
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
        busy = true; err = null
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
            if (sections.isEmpty()) {
                err = null
                searched = false
            }
        } else {
            err = r.optString("error", "加载失败")
        }
    }

    fun doSearch(q: String) {
        if (busy) return
        scope.launch {
            busy = true; err = null
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onAccount(title) }) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (logged) "已登录" else "账号", fontSize = 13.sp)
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
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            val flat = sections.flatMap { it.comics }
            when {
                busy -> {}
                flat.isEmpty() -> {
                    val e = err
                    val loginLikely = e != null && (e.contains("登录") || e.contains("login", true))
                    BrowseEmpty(
                        icon = if (loginLikely) Icons.Default.Person else Icons.Default.Search,
                        title = when {
                            loginLikely -> "这个源要先登录"
                            searched -> "没有结果"
                            e != null -> "没能取到内容"
                            else -> "这个源没有首页"
                        },
                        message = when {
                            loginLikely -> "点下面的「${if (logged) "重新加载" else "去登录"}」" +
                                (if (logged) "重试；已登录还这样，多半是登录态过期，进账号页退出重登。" else "填这个站的账号，登录后就能搜索和看图。")
                            e != null -> friendlyError(e)
                            else -> "直接在搜索框里输关键词试试。"
                        },
                        primaryText = if (canLogin && !logged) "去登录" else null,
                        onPrimary = if (canLogin && !logged) ({ onAccount(title) }) else null,
                        onRetry = { tick++ },
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEach { sec ->
                        if (sec.title.isNotBlank()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    sec.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                )
                            }
                        }
                        items(sec.comics, key = { sec.title + "|" + it.id }) { c ->
                            Column(Modifier.clickable { onOpen(c.id, c.title) }) {
                                RemoteImage(
                                    url = c.cover,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    c.title, fontSize = 11.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 引擎抛出来的网络错误太生硬（"HTTP 0"），换成用户能照做的说法 */
private fun friendlyError(e: String): String {
    val low = e.lowercase()
    return when {
        low.contains("status code: 0") || e.contains("HTTP 0") ||
            low.contains("请求失败: 0") || low.contains("timed out") || low.contains("timeout") ->
            "连不上这个源。回到源列表点右上角「重新检测」，看它标「直连」还是「需 VPN」——" +
                "标「需 VPN」就要先开代理。"
        else -> e
    }
}

/** 空状态：把「为什么空」和「下一步点哪」写清楚，别只留一个搜索框 */
@Composable
private fun BrowseEmpty(
    icon: ImageVector,
    title: String,
    message: String,
    primaryText: String?,
    onPrimary: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(46.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            message, fontSize = 12.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryText != null && onPrimary != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                Text(primaryText)
            }
        }
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新加载")
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** 探索页统一结构：{ sections: [{ title, comics: [...] }] } */
private fun parseSections(data: JSONObject?): List<ExploreSection> {
    data ?: return emptyList()
    val arr = data.optJSONArray("sections") ?: return emptyList()
    val out = mutableListOf<ExploreSection>()
    for (i in 0 until arr.length()) {
        val s = arr.optJSONObject(i) ?: continue
        val cs = s.optJSONArray("comics") ?: continue
        val list = (0 until cs.length()).mapNotNull { idx ->
            cs.optJSONObject(idx)?.let { o ->
                val id = o.optString("id")
                if (id.isBlank()) null
                else ComicRow(id, o.optString("title"), o.optString("cover"), o.optString("subTitle"))
            }
        }
        if (list.isNotEmpty()) out += ExploreSection(s.optString("title"), list)
    }
    return out
}

private fun parseComics(data: JSONObject?): List<ComicRow> {
    data ?: return emptyList()
    val arr = data.optJSONArray("comics") ?: return emptyList()
    val out = mutableListOf<ComicRow>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id")
        if (id.isBlank()) continue
        out += ComicRow(
            id = id,
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
    onAccount: () -> Unit,
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
                actions = {
                    TextButton(onClick = onAccount) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("账号", fontSize = 13.sp)
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
