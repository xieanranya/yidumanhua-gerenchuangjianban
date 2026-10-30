package com.enhance.comic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.URL
import org.json.JSONObject

/**
 * 网页登录 —— 独立进程（manifest 里 android:process=":login"）。
 *
 * 为什么必须单独一个进程：主进程里源引擎的 JS 桥 WebView 是建在后台 Looper 线程上的
 * （1.9.20 起），Chromium 只允许在一个线程上初始化。等用户逛过在线功能之后，
 * 主进程的主线程再建登录 WebView，Chromium 会直接崩（native 级，Java 的 try/catch
 * 和 UncaughtExceptionHandler 都接不住，1.9.34 的防御式创建实测无效）。
 *
 * 独立进程 = 全新进程，Chromium 第一次初始化就在主线程，跟引擎那个后台 WebView
 * 互不相干，从根上绕开冲突。
 *
 * Cookie 交接：登录完成后把 CookieManager 里的 Cookie 写进
 * Android/data/com.enhance.comic/files/web_login.json，主进程从
 * WebLoginScreen（Compose）里读走、交给引擎 loginWithCookieString。
 */
class WebLoginActivity : Activity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 独立进程也要留崩溃日志，文件名跟主进程的 crash.log 分开
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(getExternalFilesDir(null) ?: filesDir, "login_crash.log")
                    .appendText("\n==== ${System.currentTimeMillis()} thread=${t.name} ====\n" +
                        android.util.Log.getStackTraceString(e))
            } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }

        val key = intent.getStringExtra("key").orEmpty()
        val url = intent.getStringExtra("url") ?: "https://www.wnacg.com/users-login.html"
        val name = intent.getStringExtra("name").orEmpty().ifBlank { "站点" }

        val px = { dp: Int -> TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // ---- 顶栏：返回 / 标题 / 已完成 ----------------------------------
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(6), px(8), px(6))
        }
        fun barText(text: String, color: Int, size: Float, bold: Boolean, block: () -> Unit) =
            TextView(this).apply {
                this.text = text
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                paint.isFakeBoldText = bold
                setPadding(px(12), px(10), px(12), px(10))
                setOnClickListener { block() }
            }
        bar.addView(barText("✕", 0xFFCCCCCC.toInt(), 16f, false) {
            setResult(RESULT_CANCELED); finish()
        })
        bar.addView(TextView(this).apply {
            text = "网页登录 · $name"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(4)
            }
        })
        bar.addView(barText("已完成", 0xFFFF6E9C.toInt(), 14f, true) {
            // 把多个可能的主机的 Cookie 都读一遍再合并：登录页在 www.x.com，
            // 源请求的可能是 x.com，只读一个 URL 会漏掉站点写到父域的 Cookie。
            val candidates = LinkedHashSet<String>()
            candidates.add(url)
            webView?.url?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            for (u in candidates.toList()) {
                try {
                    val parsed = URL(u)
                    candidates.add("${parsed.protocol}://${parsed.host}/")
                    if (parsed.host.startsWith("www."))
                        candidates.add("${parsed.protocol}://${parsed.host.removePrefix("www.")}/")
                } catch (_: Exception) { }
            }
            val cookies = candidates.mapNotNull { c ->
                try { CookieManager.getInstance().getCookie(c) } catch (_: Exception) { null }
            }.filter { it.isNotBlank() }.joinToString("; ")
            val obj = JSONObject()
                .put("key", key)
                .put("url", webView?.url ?: url)
                .put("cookies", cookies)
            try {
                File(getExternalFilesDir(null) ?: filesDir, RESULT_FILE).writeText(obj.toString())
            } catch (_: Exception) { }
            finish()
        })
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 说明行 -----------------------------------------------------
        root.addView(TextView(this).apply {
            text = "在下面的网页里完成登录（有滑块就拖一下）。看到自己的账号 / 已登录状态后，点右上角「已完成」。"
            setTextColor(0xFF999999.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(px(16), px(2), px(16), px(8))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- WebView ----------------------------------------------------
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(holder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val wv = try {
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                try { CookieManager.getInstance().setAcceptCookie(true) } catch (_: Exception) { }
                // 滑块验证码（TCaptcha / 极验）跑在第三方 iframe 里，
                // 不开第三方 Cookie 滑块会一直转圈失败。
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) } catch (_: Exception) { }
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    // 很多登录页用 alert/confirm 提示「密码错误」等，不实现就点了没反应
                    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                        AlertDialog.Builder(this@WebLoginActivity)
                            .setMessage(message)
                            .setPositiveButton("好的") { _, _ -> result.confirm() }
                            .setOnCancelListener { result.cancel() }
                            .show()
                        return true
                    }
                    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                        AlertDialog.Builder(this@WebLoginActivity)
                            .setMessage(message)
                            .setPositiveButton("确定") { _, _ -> result.confirm() }
                            .setNegativeButton("取消") { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }
                            .show()
                        return true
                    }
                }
                loadUrl(url)
            }
        } catch (e: Throwable) {
            // 独立进程里理论上不会再撞 Chromium 线程冲突，兜底保平安
            holder.addView(TextView(this).apply {
                text = "网页组件起不来：${e.javaClass.simpleName}\n可以改用系统浏览器登录后，\n" +
                    "再回账号页走「Cookie 登录」手动填 Cookie。"
                setTextColor(0xFF999999.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(px(20), px(40), px(20), px(20))
            })
            // 顶栏的「已完成」还留着：万一系统浏览器登完，CookieManager 未必有份，至少不空转
            null
        }
        wv?.let {
            holder.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        webView = wv

        setContentView(root)
    }

    override fun onDestroy() {
        webView?.let {
            try { it.stopLoading() } catch (_: Exception) { }
            try { it.destroy() } catch (_: Exception) { }
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        /** Cookie 交接文件名，必须与 OnlineScreen.kt 里 WebLoginScreen 读取的一致 */
        const val RESULT_FILE = "web_login.json"

        /** 主进程用来启动登录页 */
        fun intentOf(activity: android.content.Context, key: String, url: String, name: String): Intent =
            Intent(activity, WebLoginActivity::class.java)
                .putExtra("key", key)
                .putExtra("url", url)
                .putExtra("name", name)

        private const val BG = 0xFF0A0A0C.toInt()
    }
}
