package com.enhance.comic.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JsPromptResult
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * 漫画源引擎（Android 侧宿主）
 *
 * 源是 JS 脚本（Venera 协议），跑在隐藏 WebView 里。之所以用 WebView 而不是自己实现解释器：
 * 源脚本用了 async/await、可选链、正则回溯、DOMParser 等现代语法，WebView 原生支持且零依赖。
 *
 * 桥的关键是 **同步**：源脚本里 `sendMessage({method:'http'})` 是同步调用，
 * 而 evaluateJavascript 只有异步回调。这里用 WebView 的 onJsPrompt —— 它是 JS 线程上
 * 同步调用并同步返回字符串的，正好当同步 RPC 用。
 *
 * JS -> 原生：prompt('__host__', msgJson)   返回 valueJson（同步）
 * JS -> 原生：prompt('__event__', evtJson)  返回 ''（异步事件，用于任务结果回传）
 */
class SourceEngine private constructor(private val app: Context) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 引擎自己的 Looper 线程。
     *
     * 为什么必须单独开一条：JS 是**同步**桥，源脚本里 `sendMessage({method:'http'})` 会一直等到
     * 请求结束才返回。WebView 建在哪个线程，`@JavascriptInterface` 就在哪个线程跑 —— 以前建在
     * 主线程，于是一个不响应的源就能把界面（包括转圈动画、返回按钮）整块冻住几十秒，
     * 用户看到的就是「点了漫画一直转圈、根本用不了」。
     * 放到独立线程后，网络再慢也只卡引擎，UI 照常动，超时后还能给出错误提示。
     */
    private val engineThread = android.os.HandlerThread("src-engine-js").apply { start() }
    @Volatile private var handler: Handler = Handler(engineThread.looper)
    @Volatile private var web: WebView? = null
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val arrived = ConcurrentHashMap<String, JSONObject>()
    private val prefs = app.getSharedPreferences("source_kv", Context.MODE_PRIVATE)
    private var cookieJar: JSONObject = loadCookies()
    private var ready = false
    @Volatile private var pageReady = false

    companion object {
        @Volatile
        private var inst: SourceEngine? = null

        fun get(context: Context): SourceEngine =
            inst ?: synchronized(this) {
                inst ?: SourceEngine(context.applicationContext).also { inst = it }
            }
    }

    // ---------------------------------------------------------------- 生命周期

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb(): WebView {
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        w.addJavascriptInterface(Bridge(), "NativeBridge")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
            }
        }
        w.loadDataWithBaseURL("https://local.source/", "<html><body></body></html>", "text/html", "utf-8", null)
        return w
    }

    /**
     * 在引擎线程上建 WebView；万一某些机型不允许后台线程建 WebView，
     * 就退回主线程（旧行为，能用但会卡 UI），不让整个在线模块直接挂掉。
     */
    private fun ensureWeb(): WebView? {
        web?.let { return it }
        val w = runCatching { buildWeb() }.getOrNull()
        if (w != null) {
            web = w
            return w
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var fallback: WebView? = null
            main.post {
                handler = main
                fallback = runCatching { buildWeb() }.getOrNull()
                latch.countDown()
            }
            runCatching { latch.await(6, java.util.concurrent.TimeUnit.SECONDS) }
            web = fallback
            return fallback
        }
        return null
    }

    /** 在引擎线程上准备引擎并把域名别名表灌进去；幂等。 */
    suspend fun start(): Boolean {
        if (ready) return true
        val built = suspendCancellableCoroutine<Boolean> { cont ->
            handler.post { cont.resume(ensureWeb() != null) }
        }
        if (!built) return false
        // 等骨架页面加载完，NativeBridge 才挂上
        var waited = 0
        while (!pageReady && waited < 4000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        val ok = injectBundle()
        if (!ok) return false
        ready = true
        return true
    }

    private suspend fun injectBundle(): Boolean {
        val bundleJs = readAsset("source_engine/bundle.js") ?: return false
        val routesJs = readAsset("source_engine/routes.json") ?: "{\"sources\":[]}"
        val storedState = prefs.getString("route_state", null)
        return try {
            // onJsPrompt 不能处理太长的字符串时会被系统截断，bundle 475KB 用 evaluateJavascript 注入
            evalJs("(function(){ " + HOSTCALL_PRELUDE + " })()")
            evalJs(bundleJs)
            evalJs("SourceEngine.setEventSink(function(o){ NativeBridge.event(JSON.stringify(o)); })")
            evalJs("SourceEngine.init(" + JSONObject.quote(routesJs) + "," +
                    (storedState?.let { JSONObject.quote(it) } ?: "null") + ")")
            evalJs("SourceEngine.boot()")
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun readAsset(path: String): String? = try {
        app.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 对外 API

    /** 调引擎里的同步方法（如 routeStatus / sources），返回原始 JSON 文本。 */
    suspend fun callSync(method: String, vararg argsJson: String): String {
        start()
        val raw = evalJs("JSON.stringify(SourceEngine.$method(${argsJson.joinToString(",")}))")
        return unquote(raw)
    }

    /** 调异步方法，返回 taskId 对应的最终事件。 */
    suspend fun callAsync(method: String, vararg argsJson: String, timeoutMs: Long = 45_000): JSONObject {
        start()
        val raw = evalJs("SourceEngine.$method(${argsJson.joinToString(",")})")
        val id = unquote(raw)
        if (id.isBlank() || id == "null") return JSONObject().put("ok", false).put("error", "引擎未返回任务号")
        arrived.remove(id)?.let { return it }
        val d = CompletableDeferred<JSONObject>()
        waiters[id] = d
        val res = withTimeoutOrNull(timeoutMs) { d.await() }
        waiters.remove(id)
        return res ?: JSONObject().put("ok", false).put("error", "超时（${timeoutMs / 1000} 秒）")
    }

    suspend fun sources(): JSONArray =
        runCatching { JSONArray(callSync("sources")) }.getOrElse { JSONArray() }

    suspend fun searchOptions(key: String): JSONArray =
        runCatching { JSONArray(callSync("searchOptions", JSONObject.quote(key))) }.getOrElse { JSONArray() }

    suspend fun search(key: String, kw: String, optsJson: String, page: Int): JSONObject =
        callAsync("search", JSONObject.quote(key), JSONObject.quote(kw), JSONObject.quote(optsJson), page.toString())

    suspend fun detail(key: String, id: String): JSONObject =
        // 30 秒还没回来就别让用户干等了：转圈一定要能变成「看得懂的失败 + 重试」
        callAsync("detail", JSONObject.quote(key), JSONObject.quote(id), timeoutMs = 30_000)

    suspend fun pages(key: String, comicId: String, epId: String): JSONObject =
        callAsync("pages", JSONObject.quote(key), JSONObject.quote(comicId), JSONObject.quote(epId))

    /** 惰性取图：解第 index 页的真地址（E-Hentai 这类一页一次 API 的源用） */
    suspend fun pageImage(key: String, comicId: String, epId: String, index: Int): JSONObject =
        callAsync("pageImage", JSONObject.quote(key), JSONObject.quote(comicId), JSONObject.quote(epId), index.toString())

    suspend fun logout(key: String): JSONObject =
        callAsync("logout", JSONObject.quote(key))

    suspend fun explore(key: String, index: Int = 0): JSONObject =
        callAsync("explore", JSONObject.quote(key), index.toString())

    suspend fun login(key: String, user: String, pass: String): JSONObject =
        callAsync("login", JSONObject.quote(key), JSONObject.quote(user), JSONObject.quote(pass))

    /** 账号页：登录态 + 源自己声明的信息项（account.infoItems） */
    suspend fun account(key: String): JSONObject =
        callAsync("account", JSONObject.quote(key))

    /** 网页登录：把可见 WebView 登录成功后的 Cookie 交给引擎（绅士漫画要过滑块） */
    suspend fun loginWithCookieString(key: String, url: String, cookie: String): JSONObject =
        callAsync(
            "loginWithCookieString",
            JSONObject.quote(key), JSONObject.quote(url), JSONObject.quote(cookie),
            timeoutMs = 40_000,
        )

    // ---------------------------------------------------------------- JS 侧调用进来

    /** JS 同步桥。@JavascriptInterface 方法在 JS 线程同步返回，正好当同步 RPC 用。 */
    private inner class Bridge {
        @android.webkit.JavascriptInterface
        fun call(json: String): String = handleHostCall(json)

        @android.webkit.JavascriptInterface
        fun event(json: String) {
            handleEvent(json)
        }
    }

    private fun handleEvent(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val id = o.optString("id")
        if (id.isEmpty()) return
        if (id == "route_state") {
            prefs.edit().putString("route_state", o.optJSONObject("data")?.toString() ?: "").apply()
            return
        }
        val d = waiters[id]
        if (d != null) d.complete(o) else arrived[id] = o
    }

    private fun handleHostCall(json: String): String {
        val msg = runCatching { JSONObject(json) }.getOrNull()
            ?: return JSONObject().put("__err", "bad json").toString()
        val value: Any? = try {
            dispatch(msg)
        } catch (e: Throwable) {
            return JSONObject().put("__err", e.message ?: e.toString()).toString()
        }
        return JSONObject().put("v", jsonSafe(value)).toString()
    }

    /// JSON 表达不了二进制：统一转成 {"__b64":"..."}，由引擎 prelude 还原成 Uint8Array。
    /// 原来直接把 ByteArray 塞进 JSONObject，toString() 出来是 "[B@1a2b"，
    /// md5/AES 全在算垃圾数据（禁漫签名、拷贝正文解密都受影响）。
    private fun jsonSafe(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is ByteArray -> JSONObject().put("__b64", Base64.encodeToString(v, Base64.NO_WRAP))
        else -> v
    }

    private fun srcKey(msg: JSONObject): String =
        msg.optString("key", "").ifBlank { "default" }

    private fun dispatch(msg: JSONObject): Any? = when (msg.optString("method")) {
        "http" -> http(msg)
        "convert" -> convert(msg)
        "cookie" -> cookie(msg)
        // KV 必须按源隔离：prelude 每个请求都带 key（源自己的 key），
        // 不加前缀的话「登录了 A 源」会让 B 源也显示已登录，两个源的 token 也会互相覆盖。
        "load_data" -> prefs.getString("d_" + srcKey(msg) + "_" + msg.optString("data_key"), null)
            ?.let { JSONTokener(it).nextValue() }
        "save_data" -> {
            prefs.edit().putString("d_" + srcKey(msg) + "_" + msg.optString("data_key"),
                msg.opt("data").toString()).apply(); null
        }
        "delete_data" -> {
            prefs.edit().remove("d_" + srcKey(msg) + "_" + msg.optString("data_key")).apply(); null
        }
        "load_setting" -> prefs.getString("s_" + srcKey(msg) + "_" + msg.optString("setting_key"), null)
            ?.let { JSONTokener(it).nextValue() }
        "save_setting" -> {
            prefs.edit().putString("s_" + srcKey(msg) + "_" + msg.optString("setting_key"),
                msg.opt("value").toString()).apply(); null
        }
        // 登录态只有一处真相：引擎写进 d_<key>___logged 的那条。
        // 以前这里读的是 logged_<key>，从来没被写过 → 恒 false，
        // 源脚本里的 this.isLogged 就永远是「没登录」（EH 的收藏/观看页就是这么挂的）。
        "isLogged" -> prefs.getString("d_" + srcKey(msg) + "___logged", null) != null
        "UI" -> if (msg.optString("function") == "showDialog") 0 else null
        "random" -> {
            val min = if (msg.has("min")) msg.optDouble("min") else 0.0
            val max = if (msg.has("max")) msg.optDouble("max") else 1.0
            min + Math.random() * (max - min)
        }
        "uuid" -> java.util.UUID.randomUUID().toString()
        "delay" -> null
        "getPlatform" -> "android"
        "getLocale" -> "zh"
        "log" -> null
        // 禁漫会问宿主「有没有原生 UI 增强」，回 false 让它用源自带的默认 UI。
        // 不认这个方法会抛「未实现的宿主方法」，源的 ui getter 直接炸。
        "jm_ui" -> false
        "htmanga_state" -> {
            // 绅士漫画问「现在用哪个 API 域名」。
            // 1.9.14 之后已经没有线路层了，域名交给 prelude 的别名表改写；
            // 这里只把**老版本升级时残留**在 route_state 里的 host 当线索回一次
            // （老用户继续用上次探测到的域名），拿不到就是空，让源用它自己的 fallback。
            // 全新安装永远是空 —— 这条路径不是活机制，别再往 route_state 里写东西。
            val saved = prefs.getString("route_state", null)
            var base = ""
            runCatching {
                val st = JSONObject(saved ?: "{}").optJSONObject("state")
                val me = st?.optJSONObject(msg.optString("key"))
                base = me?.optString("host") ?: ""
            }
            if (base.isBlank()) JSONObject() else JSONObject().put("baseUrl", "https://$base")
        }
        else -> throw IllegalStateException("未实现的宿主方法: " + msg.optString("method"))
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * 真正的网络请求。**绝对不能跑在主线程**：JS 是同步桥，回调被 WebView 的 JS 线程
     * （也就是主线程）调用，直接在这里 openConnection 会抛 NetworkOnMainThreadException。
     * 所以把活儿丢到 netPool，主线程只等 Future。
     */
    private val netPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(r, "src-net").apply { isDaemon = true }
        }

    private fun http(msg: JSONObject): JSONObject {
        val f = netPool.submit(java.util.concurrent.Callable { httpIO(msg) })
        return try {
            f.get(90, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            f.cancel(true)
            JSONObject().put("status", 0).put("headers", JSONObject()).put("body", "")
                .put("error", "请求超时（90 秒）")
        } catch (e: Throwable) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            JSONObject().put("status", 0).put("headers", JSONObject()).put("body", "")
                .put("error", cause.message ?: cause.toString())
        }
    }

    private fun httpIO(msg: JSONObject): JSONObject {
        var rawUrl = msg.optString("url")
        // 源脚本常把中文选项值直接拼进 URL（如 o=mr-最新），原始字节会 400
        rawUrl = normalizeUrl(rawUrl)
        val method = msg.optString("http_method", "GET").uppercase()
        val conn = (URL(rawUrl).openConnection() as HttpURLConnection)
        // 被墙的域名是「不发 RST、直接没响应」，connects 只能靠超时结束。
        // 15 秒 × 好几个源串行排队 = 发现页要等一分钟，所以收到 6 秒：
        // 国内正常的站点握手都在 300ms 内，6 秒还连不上的基本可以判定这次不通。
        conn.connectTimeout = 6_000
        conn.readTimeout = 20_000
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.doInput = true

        val headers = msg.optJSONObject("headers")
        var acceptGzip = false
        if (headers != null) {
            for (k in headers.keys()) {
                val v = headers.optString(k)
                if (v.isEmpty()) continue
                val lk = k.lowercase()
                if (lk == "accept-encoding") {
                    // 只声明 gzip/deflate，声明 br 我们解不开
                    conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
                    acceptGzip = true
                    continue
                }
                if (lk == "connection" || lk == "content-length" || lk == "host") continue
                conn.setRequestProperty(k, v)
            }
        }
        if (!acceptGzip) {
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
            acceptGzip = true
        }

        val host = runCatching { URL(rawUrl).host }.getOrNull() ?: ""
        val cookieHeader = cookiesFor(host).joinToString("; ") { it.first + "=" + it.second }
        if (cookieHeader.isNotEmpty()) conn.setRequestProperty("Cookie", cookieHeader)

        val data = msg.opt("data")
        if (data != null && data != JSONObject.NULL && method != "GET" && method != "HEAD") {
            val bytes = when (data) {
                is String -> data.toByteArray(Charsets.UTF_8)
                is JSONObject -> {
                    val b = data.optString("__b64", "")
                    if (b.isNotEmpty()) Base64.decode(b, Base64.DEFAULT) else data.toString().toByteArray(Charsets.UTF_8)
                }
                else -> data.toString().toByteArray(Charsets.UTF_8)
            }
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }

        val status = try {
            conn.responseCode
        } catch (e: Throwable) {
            return JSONObject().put("status", 0).put("headers", JSONObject()).put("body", "").put("error", e.message ?: "")
        }
        val enc = conn.contentEncoding?.lowercase() ?: ""
        val stream = (if (status >= 400) conn.errorStream else conn.inputStream)?.let { raw ->
            when {
                enc.contains("gzip") -> GZIPInputStream(raw)
                enc.contains("deflate") -> InflaterInputStream(raw)
                else -> raw
            }
        }
        val body = stream?.use { readAll(it) } ?: ByteArray(0)

        // 收集 Set-Cookie
        conn.headerFields?.forEach { (k, vs) ->
            if (k != null && k.equals("Set-Cookie", true) && vs != null) vs.forEach { saveCookie(host, it) }
        }

        val wantBytes = msg.optBoolean("bytes", false)
        val out = JSONObject()
        out.put("status", status)
        out.put("headers", JSONObject())
        // 二进制不塞成裸 base64 字符串了，统一 {"__b64":...}，由引擎 prelude 还原
        out.put("body", if (wantBytes) JSONObject().put("__b64", Base64.encodeToString(body, Base64.NO_WRAP))
        else String(body, Charsets.UTF_8))
        conn.disconnect()
        return out
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun normalizeUrl(u: String): String {
        val sb = StringBuilder(u.length + 16)
        for (ch in u) {
            if (ch.code in 0x21..0x7E) sb.append(ch)
            else for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- Cookie

    private fun loadCookies(): JSONObject = runCatching {
        JSONObject(prefs.getString("cookies", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    /**
     * Cookie 表按 host 存，但源给的 domain 常是 `.e-hentai.org` 这种父域，
     * 实际请求却发到 forums.e-hentai.org。所以取的时候必须做后缀匹配，
     * 不然「填了 Cookie 却一个都没带上」，校验永远失败。
     */
    private fun hostMatch(host: String, jarKey: String): Boolean {
        val h = host.lowercase().trim()
        val k = jarKey.lowercase().trim().trimStart('.')
        if (h.isEmpty() || k.isEmpty()) return false
        return h == k || h.endsWith(".$k")
    }

    private fun cookiesFor(host: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        // 更精确的 host 排前面，同名 Cookie 以它为准
        val keys = cookieJar.keys().asSequence().filter { hostMatch(host, it) }.sortedByDescending { it.length }
        for (key in keys) {
            val arr = cookieJar.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isEmpty()) continue
                if (!seen.add(name)) continue
                out += name to o.optString("value")
            }
        }
        return out
    }

    private fun saveCookie(host: String, setCookie: String) {
        val first = setCookie.split(";").firstOrNull()?.trim() ?: return
        val eq = first.indexOf('=')
        if (eq <= 0) return
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        val arr = cookieJar.optJSONArray(host) ?: JSONArray()
        val next = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) {
                next.put(JSONObject().put("name", name).put("value", value).put("domain", host))
                replaced = true
            } else next.put(o)
        }
        if (!replaced) next.put(JSONObject().put("name", name).put("value", value).put("domain", host))
        cookieJar.put(host, next)
        prefs.edit().putString("cookies", cookieJar.toString()).apply()
    }

    private fun cookie(msg: JSONObject): Any? {
        val host = runCatching { URL(msg.optString("url")).host }.getOrNull() ?: ""
        when (msg.optString("function")) {
            "get" -> {
                val arr = JSONArray()
                cookiesFor(host).forEach {
                    arr.put(JSONObject().put("name", it.first).put("value", it.second).put("domain", host))
                }
                return arr
            }
            // 源会自己塞 Cookie：E-Hentai 校验前先 setCookies 把 4 个值写进来，
            // 拷贝登录成功也把 token/name/user_id 写进来。这里以前是空实现，
            // 等于把这些 Cookie 直接扔掉——表现就是「填对了也提示 Cookie 无效」。
            "set" -> {
                val list = msg.optJSONArray("cookies") ?: JSONArray()
                val put = mutableListOf<JSONObject>()
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    if (name.isEmpty()) continue
                    put += JSONObject()
                        .put("name", name)
                        .put("value", o.optString("value"))
                        .put("domain", o.optString("domain").ifBlank { host })
                }
                val names = put.map { it.optString("name") }.toSet()
                val next = JSONArray()
                val old = cookieJar.optJSONArray(host) ?: JSONArray()
                for (i in 0 until old.length()) {
                    val o = old.optJSONObject(i) ?: continue
                    if (o.optString("name") in names) continue
                    next.put(o)
                }
                put.forEach { next.put(it) }
                cookieJar.put(host, next)
                prefs.edit().putString("cookies", cookieJar.toString()).apply()
                return null
            }
            "delete" -> {
                val namesArr = msg.optJSONArray("names")
                val drop = if (namesArr != null && namesArr.length() > 0) {
                    (0 until namesArr.length()).map { namesArr.optString(it) }.toSet()
                } else emptySet()
                for (key in cookieJar.keys().asSequence().toList()) {
                    if (!hostMatch(host, key)) continue
                    if (drop.isEmpty()) {
                        cookieJar.remove(key)
                        continue
                    }
                    val old = cookieJar.optJSONArray(key) ?: continue
                    val next = JSONArray()
                    var changed = false
                    for (i in 0 until old.length()) {
                        val o = old.optJSONObject(i) ?: continue
                        if (o.optString("name") in drop) changed = true else next.put(o)
                    }
                    if (changed) cookieJar.put(key, next)
                }
                prefs.edit().putString("cookies", cookieJar.toString()).apply()
                return null
            }
        }
        return null
    }

    // ---------------------------------------------------------------- convert

    private fun convert(msg: JSONObject): Any? {
        val type = msg.optString("type").lowercase()
        val encode = msg.optBoolean("isEncode", false)
        val value = msg.opt("value")
        /**
         * 入参 → 字节。引擎把 Uint8Array 打包成 {"__b64":"..."}，偶尔也会是普通数组。
         * 只有 String 走 UTF-8，别的必须当真字节看——以前 base64 编码用 optString 取，
         * 二进制就被当成了字符串，编出来是一串 JSON 文本的 base64（全错）。
         */
        fun bytes(v: Any?): ByteArray = when (v) {
            null, JSONObject.NULL -> ByteArray(0)
            is ByteArray -> v
            is String -> v.toByteArray(Charsets.UTF_8)
            is JSONObject -> {
                val b = v.optString("__b64", "")
                if (b.isNotEmpty()) Base64.decode(b, Base64.NO_WRAP) else v.toString().toByteArray(Charsets.UTF_8)
            }
            is JSONArray -> ByteArray(v.length()) { i -> v.optInt(i).toByte() }
            else -> v.toString().toByteArray(Charsets.UTF_8)
        }
        val raw = bytes(value)
        return when (type) {
            // isEncode=true 是「字符串 → 字节」（源拿去做 AES/hmac 的 key），
            // false 是「字节 → 字符串」。以前两种都返回字符串，禁漫的
            // convertData 解出来是 {"__b64":"..."} 这层壳，JSON.parse 之后没有 uid，
            // 于是密码对也报「账号或密码不对」——安卓上禁漫所有接口都受这个影响。
            "utf8" -> if (encode) raw else String(raw, Charsets.UTF_8)
            "gbk" -> if (encode) raw else String(raw, charset("GBK"))
            "base64" -> if (encode) Base64.encodeToString(raw, Base64.NO_WRAP)
            else Base64.decode(String(raw, Charsets.UTF_8).trim(), Base64.DEFAULT)
            "md5" -> MessageDigest.getInstance("MD5").digest(bytes(value))
            "sha1" -> MessageDigest.getInstance("SHA-1").digest(bytes(value))
            "sha256" -> MessageDigest.getInstance("SHA-256").digest(bytes(value))
            "sha512" -> MessageDigest.getInstance("SHA-512").digest(bytes(value))
            "hmac" -> {
                val alg = when (msg.optString("hash", "sha256").lowercase()) {
                    "sha1" -> "HmacSHA1"; "sha512" -> "HmacSHA512"; "md5" -> "HmacMD5"; else -> "HmacSHA256"
                }
                val mac = Mac.getInstance(alg)
                mac.init(SecretKeySpec(bytes(msg.opt("key")), alg))
                val d = mac.doFinal(bytes(value))
                if (msg.optBoolean("isString", false)) d.toHex() else d
            }
            "aes-ecb" -> {
                val key = bytes(msg.opt("key"))
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                c.init(if (encode) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                c.doFinal(bytes(value))
            }
            "aes-cbc" -> {
                val key = bytes(msg.opt("key"))
                val iv = bytes(msg.opt("iv"))
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(
                    if (encode) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"), IvParameterSpec(iv)
                )
                c.doFinal(bytes(value))
            }
            else -> throw IllegalArgumentException("convert 未实现: $type")
        }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    // ---------------------------------------------------------------- 工具

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post(block)
    }

    private suspend fun evalJs(code: String): String = suspendCancellableCoroutine { cont ->
        handler.post {
            val w = web
            if (w == null) {
                cont.resume("null")
                return@post
            }
            w.evaluateJavascript(code) { r -> cont.resume(r ?: "null") }
        }
    }

    private fun callSyncBlocking(method: String, vararg args: String): String {
        // 仅在 JS 线程上被 htmanga_state 之类调用；此时 WebView 正忙，读缓存值即可
        return "[]"
    }

    private fun unquote(raw: String): String {
        if (raw.isEmpty() || raw == "null") return ""
        return runCatching {
            val v = JSONTokener(raw).nextValue()
            when (v) {
                is String -> v
                JSONObject.NULL -> ""
                else -> v.toString()
            }
        }.getOrElse { raw.trim('"') }
    }
}

/** 注入到 JS 全局的宿主桥。引擎 prelude 的 sendMessage 会调它，拆包装/二进制转换都在 prelude 里。 */
private const val HOSTCALL_PRELUDE = """
window.__hostRaw = function(json) { return NativeBridge.call(json); };
"""
