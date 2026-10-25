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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private var web: WebView? = null
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val arrived = ConcurrentHashMap<String, JSONObject>()
    private val prefs = app.getSharedPreferences("source_kv", Context.MODE_PRIVATE)
    private var cookieJar: JSONObject = loadCookies()
    private var ready = false
    @Volatile private var pageReady = false

    companion object {
        @Volatile
        private var inst: SourceEngine? = null

        /** 上次线路探测时间。从源里退回列表页不该再等一遍探测，5 分钟内复用上次结果 */
        @Volatile
        private var lastProbeAt = 0L
        private const val PROBE_TTL_MS = 5 * 60 * 1000L

        fun get(context: Context): SourceEngine =
            inst ?: synchronized(this) {
                inst ?: SourceEngine(context.applicationContext).also { inst = it }
            }
    }

    // ---------------------------------------------------------------- 生命周期

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): WebView {
        web?.let { return it }
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
        web = w
        return w
    }

    /** 在主线程准备引擎并初始化线路表；幂等。 */
    suspend fun start(): Boolean {
        if (ready) return true
        runOnMain { ensureWeb() }
        // 等骨架页面加载完，NativeBridge 才挂上
        var waited = 0
        while (!pageReady && waited < 3000) {
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

    suspend fun routeStatus(): JSONArray =
        runCatching { JSONArray(callSync("routeStatus")) }.getOrElse { JSONArray() }

    suspend fun sources(): JSONArray =
        runCatching { JSONArray(callSync("sources")) }.getOrElse { JSONArray() }

    suspend fun searchOptions(key: String): JSONArray =
        runCatching { JSONArray(callSync("searchOptions", JSONObject.quote(key))) }.getOrElse { JSONArray() }

    suspend fun search(key: String, kw: String, optsJson: String, page: Int): JSONObject =
        callAsync("search", JSONObject.quote(key), JSONObject.quote(kw), JSONObject.quote(optsJson), page.toString())

    suspend fun detail(key: String, id: String): JSONObject =
        callAsync("detail", JSONObject.quote(key), JSONObject.quote(id))

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

    suspend fun setHost(key: String, host: String) {
        callSync("setHost", JSONObject.quote(key), JSONObject.quote(host))
    }

    /**
     * 原生侧并发探测线路。
     *
     * 按「源」分组探，不是按候选条目一条条探：一个源的所有 host（直连 + VPN）一起并发打，
     * 然后**按通道顺序**取第一条通的——直连能通就绝不用 VPN。
     *
     * 三种结果都要写回引擎，不然后面判断不了「这个源压根不通」：
     *   探到 host  -> routeApply(key, ci, host)
     *   全都不通    -> routeApply(key, 0, "")  → offline，界面显示「不通」
     *   结束时      -> routeMarkProbed(key)     → 只标这个源，不再一把全标（旧逻辑会把没探的也标成已探）
     */
    suspend fun probeRoutes(force: Boolean = false): List<Triple<String, Int, String>> {
        if (!force && System.currentTimeMillis() - lastProbeAt < PROBE_TTL_MS) return emptyList()
        val cands = runCatching { JSONArray(callSync("routeCandidates")) }.getOrElse { JSONArray() }
        // key -> 该源的候选 host（含通道序号与探测路径）
        val byKey = LinkedHashMap<String, MutableList<Triple<Int, String, String>>>()
        for (i in 0 until cands.length()) {
            val c = cands.optJSONObject(i) ?: continue
            val key = c.optString("key")
            if (key.isBlank()) continue
            val ci = c.optInt("ci")
            val path = c.optString("probe").ifBlank { "/" }
            val hosts = c.optJSONArray("hosts") ?: continue
            val list = byKey.getOrPut(key) { mutableListOf() }
            for (j in 0 until hosts.length()) {
                val h = hosts.optString(j)
                if (h.isNotBlank()) list += Triple(ci, h, path)
            }
        }

        val out = mutableListOf<Triple<String, Int, String>>()
        for ((key, list) in byKey) {
            if (list.isEmpty()) continue
            callSync("routeBeginProbe", JSONObject.quote(key))
            val hits = coroutineScope {
                list.map { c -> async(Dispatchers.IO) { if (probeHost(c.second, c.third)) c else null } }
                    .awaitAll().filterNotNull()
            }
            val pick = hits.minWithOrNull(compareBy({ it.first }, { list.indexOf(it) }))
            if (pick != null) {
                callSync("routeApply", JSONObject.quote(key), pick.first.toString(), JSONObject.quote(pick.second))
                out += Triple(key, pick.first, pick.second)
            } else {
                callSync("routeApply", JSONObject.quote(key), "0", JSONObject.quote(""))
            }
            callSync("routeMarkProbed", JSONObject.quote(key))
        }
        lastProbeAt = System.currentTimeMillis()
        return out
    }

    private fun probeHost(host: String, path: String = "/"): Boolean = runCatching {
        val p = if (path.startsWith("/")) path else "/$path"
        val conn = URL("https://$host$p").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        )
        val code = conn.responseCode
        conn.disconnect()
        // 2xx~4xx 说明 TCP/TLS/HTTP 全通；403 是风控页但线路本身可达
        code in 200..499
    }.getOrElse { false }

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
        "isLogged" -> prefs.getBoolean("logged_" + msg.optString("key"), false)
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
        "htmanga_state" -> {
            // 绅士漫画问「现在用哪个 API 域名」；回线路层里存下的 host，没有就让源用它的 fallback
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

    private fun http(msg: JSONObject): JSONObject {
        var rawUrl = msg.optString("url")
        // 源脚本常把中文选项值直接拼进 URL（如 o=mr-最新），原始字节会 400
        rawUrl = normalizeUrl(rawUrl)
        val method = msg.optString("http_method", "GET").uppercase()
        val conn = (URL(rawUrl).openConnection() as HttpURLConnection)
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
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

    private fun cookiesFor(host: String): List<Pair<String, String>> {
        val arr = cookieJar.optJSONArray(host) ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += o.optString("name") to o.optString("value")
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
            "set", "delete" -> {
                // 简化：由真实响应里的 Set-Cookie 维护，这里清空或覆盖
                if (msg.optString("function") == "delete") {
                    cookieJar.remove(host)
                    prefs.edit().putString("cookies", cookieJar.toString()).apply()
                }
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
        fun bytes(v: Any?): ByteArray = when (v) {
            is String -> v.toByteArray(Charsets.UTF_8)
            // 引擎把 Uint8Array 打包成 {"__b64":"..."} 传下来
            is JSONObject -> {
                val b = v.optString("__b64", "")
                if (b.isNotEmpty()) Base64.decode(b, Base64.DEFAULT) else v.toString().toByteArray(Charsets.UTF_8)
            }
            else -> v.toString().toByteArray(Charsets.UTF_8)
        }
        return when (type) {
            "utf8" -> if (encode) String(bytes(value), Charsets.UTF_8) else value?.toString() ?: ""
            "gbk" -> if (encode) bytes(value) else String(bytes(value), charset("GBK"))
            "base64" -> if (encode) Base64.encodeToString(bytes(msg.optString("value")), Base64.NO_WRAP)
            else Base64.decode(msg.optString("value"), Base64.DEFAULT)
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
        main.post {
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
