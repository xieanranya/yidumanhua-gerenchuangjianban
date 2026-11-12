package com.enhance.comic.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 黑白漫画 AI 上色：双模型可切换（设置 → 上色风格）。
 *
 * - ANIME（动漫风）：manga-colorization-v2 generator，ONNX **fp32** 117MB（Tinigon 导出，MIT 系）。
 *   输入 [1,5,H,W]：ch0 = 灰度 0..1，ch1-3 = 颜色提示（自动模式全 0），ch4 = 提示掩码（0）；
 *   H/W 为 32 的倍数（动态维度）。输出 [1,3,H,W] = RGB **[-1,1]**（tanh，需 *0.5+0.5 反归一化）。
 *   合成：亮度-色度分离 —— 模型输出只取 Cb/Cr 色度，亮度用原图全分辨率灰度 Y，
 *   线稿 100% 清晰（1.9.50 之前直接用模型 RGB 输出再放大，糊）。
 *   注意：不要换回 fp16 版 —— fp16 在部分手机的 ORT-ARM 上推理 native 崩溃（桌面 x64 验证
 *   不出来），症状就是「开了上色翻页直接闪退」。
 * - REALISTIC（写实风）：DDColor（达摩院 ICCV 2023），ONNX int8 59.1MB，Apache-2.0。
 *   输入 [1,3,256,256]：灰度复制 3 通道 0..1（ImageNet normalize 在模型图内）。
 *   输出 [1,2,256,256] = Lab 的 ab 色度 —— 需与原尺寸灰度 L 合成后 Lab→RGB。
 *
 * 模型不进 APK（各约 59MB）：首次选用某风格时从 hf-mirror（国内可达）/ huggingface
 * 双源下载到 filesDir/models/，并校验字节数。
 * 任何失败都返回原图，绝不挡阅读。
 */
object ColorizeEngine {

    enum class Model(
        val id: String,
        val file: String,
        val bytes: Long,
        val label: String,
        val urls: List<String>,
    ) {
        ANIME(
            // fp32 导出（Tinigon）：早先的 fp16 版在部分手机的 ORT-ARM 上推理会 native 崩溃
            //（桌面 x64 验证通过但手机 ARM 不行），换 fp32 稳定版；体积 117MB。
            "anime", "manga-colorizer-fp32.onnx", 122_944_482L, "动漫",
            listOf(
                "https://hf-mirror.com/Tinigon/manga-colorizer-onnx/resolve/main/colorizer.onnx",
                "https://huggingface.co/Tinigon/manga-colorizer-onnx/resolve/main/colorizer.onnx",
            ),
        ),
        REALISTIC(
            "realistic", "ddcolor-int8.onnx", 61_926_813L, "写实",
            listOf(
                "https://hf-mirror.com/Faridzar/ddcolor-mirror/resolve/main/ddcolor-int8.onnx",
                "https://huggingface.co/Faridzar/ddcolor-mirror/resolve/main/ddcolor-int8.onnx",
            ),
        );

        companion object {
            fun byId(id: String): Model = if (id == REALISTIC.id) REALISTIC else ANIME
        }
    }

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        SuperRes.init(appContext)
    }

    internal lateinit var appContext: Context
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()
    private val runMutex = Mutex()

    private fun modelFile(m: Model): File =
        File(File(appContext.filesDir, "models").apply { mkdirs() }, m.file)

    fun modelReady(m: Model): Boolean = ::appContext.isInitialized &&
        modelFile(m).let { it.length() == m.bytes }

    // ---- 下载状态（按模型 id） ------------------------------------------

    sealed interface ModelState {
        data object Idle : ModelState
        data object Downloading : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private val _downloadState = MutableStateFlow(defaultStates())
    val downloadState: StateFlow<Map<String, ModelState>> = _downloadState
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    private fun defaultStates(): Map<String, ModelState> =
        runCatching {
            Model.entries.associate { m -> m.id to (if (modelReady(m)) ModelState.Ready else ModelState.Idle) }
        }.getOrDefault(emptyMap())

    fun refreshState() {
        if (_downloadState.value.values.any { it is ModelState.Downloading }) return
        _downloadState.value = defaultStates()
        _downloadProgress.value = emptyMap()
    }

    fun stateOf(m: Model): ModelState =
        _downloadState.value[m.id] ?: if (modelReady(m)) ModelState.Ready else ModelState.Idle

    fun progressOf(m: Model): Float = _downloadProgress.value[m.id] ?: 0f

    suspend fun download(m: Model) {
        if (modelReady(m)) {
            _downloadState.value = _downloadState.value + (m.id to ModelState.Ready)
            _downloadProgress.value = _downloadProgress.value + (m.id to 1f)
            return
        }
        if (_downloadState.value[m.id] is ModelState.Downloading) return
        _downloadState.value = _downloadState.value + (m.id to ModelState.Downloading)
        _downloadProgress.value = _downloadProgress.value + (m.id to 0f)
        var last: Exception? = null
        for (u in m.urls) {
            try {
                withContext(Dispatchers.IO) { fetchModel(m, u) }
                if (modelReady(m)) {
                    _downloadState.value = _downloadState.value + (m.id to ModelState.Ready)
                    _downloadProgress.value = _downloadProgress.value + (m.id to 1f)
                    return
                }
            } catch (e: Throwable) {
                // Throwable 而不是 Exception：模型目录写不了之类的 Error 也要落到失败状态，
                // 不能让协程外层直接把进程带崩
                last = Exception(e.message ?: (e.javaClass.simpleName + "（无详细信息）"))
                logError("download(${m.id}) ← $u 失败：" + android.util.Log.getStackTraceString(e))
            }
        }
        _downloadState.value = _downloadState.value +
            (m.id to ModelState.Failed(last?.message ?: "模型下载失败：两个源都没成功"))
    }

    /**
     * 带断点续传的下载：.part 已有的部分用 Range 接着下，网络抖一下不用从头再来。
     * 单个源最多跑 4 轮，还没下完就抛给外层换下一个源。
     */
    private fun fetchModel(m: Model, url: String) {
        val target = modelFile(m)
        val tmp = File(target.path + ".part")
        if (tmp.exists() && tmp.length() > m.bytes) tmp.delete()

        var round = 0
        while (round < 4) {
            round++
            val have = if (tmp.exists()) tmp.length() else 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            try {
                if (have in 1 until m.bytes) {
                    conn.setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code != 200 && code != 206) throw Exception("下载源返回 $code")
                val resume = code == 206 && have > 0
                val base = if (resume) have else 0L
                val total = base + conn.contentLengthLong
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp, resume).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var read = base
                        var lastReport = base
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read - lastReport >= (1 shl 20)) {
                                lastReport = read
                                if (total > 0) {
                                    _downloadProgress.value = _downloadProgress.value +
                                        (m.id to (read.toFloat() / total).coerceAtMost(0.99f))
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() == m.bytes) {
                logError("模型下载完成：${m.id} ← $url（${tmp.length()} 字节，$round 轮）")
                target.delete()
                if (!tmp.renameTo(target)) throw Exception("模型保存失败")
                // 旧版模型文件清理（换过模型后老用户目录里会留孤儿文件）
                if (m == Model.ANIME) {
                    File(File(appContext.filesDir, "models"), "manga-colorize-fp16.onnx").delete()
                }
                return
            }
            // 不完整 → 循环回去从 .part 接着下
        }
        throw Exception("下载不完整（${tmp.length()}/${m.bytes} 字节），重试 4 轮仍失败")
    }

    // ---- 推理 -----------------------------------------------------------

    /** 上色一页。模型没就绪 / 推理失败都返回 null（调用方回退原图）。串行执行防内存峰值。 */
    suspend fun colorize(input: Bitmap, model: Model): Bitmap? = withContext(Dispatchers.Default) {
        // 模型没下载就别碰 ORT：省掉每页一次的无效 createSession（还会狂写日志）
        if (!modelReady(model)) return@withContext null
        runMutex.withLock {
            try {
                when (model) {
                    Model.ANIME -> runAnime(input)
                    Model.REALISTIC -> runRealistic(input)
                }
            } catch (e: Throwable) {
                logError("colorize(${model.id}) failed: " + android.util.Log.getStackTraceString(e))
                null
            }
        }
    }

    private fun acquireSession(m: Model): OrtSession {
        sessions[m.id]?.let { return it }
        // 加载失败过就别再试：createSession 对坏文件每页重复跑又慢又耗内存
        if (m.id in brokenModels) throw Exception("模型加载之前失败，跳过重复尝试")
        if (!modelReady(m)) throw Exception("模型未下载或大小不对（filesDir/models/${m.file}）")
        try {
            val s = env.createSession(modelFile(m).path)
            sessions[m.id] = s
            return s
        } catch (e: Throwable) {
            brokenModels.add(m.id)
            throw e
        }
    }

    private val brokenModels: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * ANIME（manga-colorization-v2）：输入 [1,5,H,W]，输出 [1,3,H,W] RGB。
     * 长边压到 512 并对齐 32。
     */
    private fun runAnime(input: Bitmap): Bitmap? {
        val w0 = input.width
        val h0 = input.height
        if (w0 <= 0 || h0 <= 0) return null
        val session = acquireSession(Model.ANIME)

        val inName = session.inputNames.firstOrNull() ?: return null
        val outName = session.outputNames.firstOrNull() ?: return null

        // 推理尺寸：长边 ≤ 768，双向对齐 32。清晰度与推理尺寸无关（线稿来自原图），
        // 这里只影响色度的精细程度
        var tw = w0
        var th = h0
        val long = maxOf(w0, h0)
        if (long > 768) {
            val k = 768f / long
            tw = (w0 * k).toInt()
            th = (h0 * k).toInt()
        }
        tw = maxOf(32, (tw / 32) * 32)
        th = maxOf(32, (th / 32) * 32)

        // 原图全分辨率灰度 Y（最后合成用）—— 线稿清晰度全靠它
        val plane0 = w0 * h0
        val px0 = IntArray(plane0)
        input.getPixels(px0, 0, w0, 0, 0, w0, h0)
        val grayY = ByteArray(plane0)
        for (i in 0 until plane0) {
            val p = px0[i]
            grayY[i] = ((0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) + 0.5f)
                .toInt().coerceIn(0, 255).toByte()
        }
        px0.fill(0)

        val small = Bitmap.createScaledBitmap(input, tw, th, true)
        val grayBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        Canvas(grayBmp).drawBitmap(
            small, 0f, 0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isFilterBitmap = true },
        )
        if (small !== input) small.recycle()

        val plane = tw * th
        val pixels = IntArray(plane)
        grayBmp.getPixels(pixels, 0, tw, 0, 0, tw, th)
        grayBmp.recycle()
        val inp = FloatArray(5 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val l = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
            inp[i] = l / 255f
        }

        val shape = longArrayOf(1, 5, th.toLong(), tw.toLong())
        val tensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(inp), shape)
        tensor.use {
            val result = session.run(mapOf(inName to it))
            result.use {
                val out = result.get(outName).orElse(null) as? OnnxTensor ?: return null
                val buf = out.floatBuffer ?: return null
                if (buf.remaining() != 3 * plane) {
                    logError("anime 模型输出通道数异常: " + buf.remaining())
                    return null
                }
                // line-mix 合成（桌面三方案对比选定）：
                //   ① 暗部（原图线稿，Y<0.45）→ 亮度用原图（锐利黑线），色度向中性收 55%（避免彩线稿）
                //   ② 其余全部用模型原生输出（亮度+色度都来自模型 → 颜色天然协调）
                // 1.9.51 的「只取色度+原图亮度」会把模型色度配到网点纸/灰调背景上，
                // 灰底 × 饱和色度 = 浑浊的脏色 —— 这就是「颜色怪」的根源。
                val outPx = IntArray(plane0)
                for (y in 0 until h0) {
                    val fy = y * th.toFloat() / h0
                    val yi0 = fy.toInt().coerceAtMost(th - 1)
                    val yi1 = minOf(yi0 + 1, th - 1)
                    val wy = fy - yi0
                    val row = y * w0
                    for (x in 0 until w0) {
                        val fx = x * tw.toFloat() / w0
                        val xi0 = fx.toInt().coerceAtMost(tw - 1)
                        val xi1 = minOf(xi0 + 1, tw - 1)
                        val wx = fx - xi0
                        val i = row + x
                        // 模型 RGB（反归一化 [0,1]）按需双线性采样
                        val mr = (bilerp(buf, 0, tw, xi0, xi1, yi0, yi1, wx, wy) * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val mg = (bilerp(buf, plane, tw, xi0, xi1, yi0, yi1, wx, wy) * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val mb = (bilerp(buf, 2 * plane, tw, xi0, xi1, yi0, yi1, wx, wy) * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val ys = (grayY[i].toInt() and 0xFF) / 255f
                        val ym = 0.299f * mr + 0.587f * mg + 0.114f * mb
                        val mask = ((0.45f - ys) / 0.22f).coerceIn(0f, 1f)
                        val yf = ys * mask + ym * (1 - mask)
                        val chroma = 1f - 0.55f * mask
                        val cb = 0.5f + (-0.168736f * mr - 0.331264f * mg + 0.5f * mb) * chroma
                        val cr = 0.5f + (0.5f * mr - 0.418688f * mg - 0.081312f * mb) * chroma
                        val r = (yf + 1.402f * (cr - 0.5f)).coerceIn(0f, 1f)
                        val g = (yf - 0.344136f * (cb - 0.5f) - 0.714136f * (cr - 0.5f)).coerceIn(0f, 1f)
                        val b = (yf + 1.772f * (cb - 0.5f)).coerceIn(0f, 1f)
                        outPx[i] = (0xFF shl 24) or ((r * 255f + 0.5f).toInt() shl 16) or
                            ((g * 255f + 0.5f).toInt() shl 8) or (b * 255f + 0.5f).toInt()
                    }
                }
                return Bitmap.createBitmap(outPx, w0, h0, Bitmap.Config.ARGB_8888)
            }
        }
    }

    /**
     * REALISTIC（DDColor）：输入 [1,3,256,256]，输出 [1,2,256,256] 的 Lab ab 色度。
     * ab 上采样回原尺寸，与原灰度 L 合成后 Lab→RGB。
     */
    private fun runRealistic(input: Bitmap): Bitmap? {
        val w0 = input.width
        val h0 = input.height
        if (w0 <= 0 || h0 <= 0) return null
        val session = acquireSession(Model.REALISTIC)

        val inName = session.inputNames.firstOrNull() ?: return null
        val outName = session.outputNames.firstOrNull() ?: return null
        val side = try {
            val info = session.getInputInfo()[inName] ?: return null
            val ti = info.info as ai.onnxruntime.TensorInfo
            ti.shape.getOrNull(2)?.toInt()?.coerceAtLeast(32) ?: 256
        } catch (e: Throwable) {
            logError("读模型输入信息失败，按 256 兜底: " + e.message)
            256
        }

        // 原尺寸灰度（保留，最后合成用）。ByteArray 存 0..255，比 FloatArray 省 4 倍内存
        val plane0 = w0 * h0
        val px0 = IntArray(plane0)
        input.getPixels(px0, 0, w0, 0, 0, w0, h0)
        val gray0 = ByteArray(plane0)
        for (i in 0 until plane0) {
            val p = px0[i]
            gray0[i] = ((0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) + 0.5f)
                .toInt().coerceIn(0, 255).toByte()
        }
        px0.fill(0) // 用完立刻释放引用（数组还要复用的话 GC 也好回收）

        val small = Bitmap.createScaledBitmap(input, side, side, true)
        val grayBmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        Canvas(grayBmp).drawBitmap(
            small, 0f, 0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isFilterBitmap = true },
        )
        if (small !== input) small.recycle()

        val plane = side * side
        val pixels = IntArray(plane)
        grayBmp.getPixels(pixels, 0, side, 0, 0, side, side)
        grayBmp.recycle()
        val inp = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val l = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
            val v = l / 255f
            inp[i] = v
            inp[plane + i] = v
            inp[2 * plane + i] = v
        }

        val shape = longArrayOf(1, 3, side.toLong(), side.toLong())
        val tensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(inp), shape)
        tensor.use {
            val result = session.run(mapOf(inName to it))
            result.use {
                val out = result.get(outName).orElse(null) as? OnnxTensor ?: return null
                val buf = out.floatBuffer ?: return null

                if (buf.remaining() == 2 * side * side) {
                    // ab 双线性采样（按需取，不物化 16MB 的中间数组）→ 与灰度 L 合成 → Lab→RGB
                    val outPx = IntArray(plane0)
                    for (y in 0 until h0) {
                        val fy = y * side.toFloat() / h0
                        val y0i = fy.toInt().coerceAtMost(side - 1)
                        val y1i = minOf(y0i + 1, side - 1)
                        val wy = fy - y0i
                        val row = y * w0
                        for (x in 0 until w0) {
                            val fx = x * side.toFloat() / w0
                            val x0i = fx.toInt().coerceAtMost(side - 1)
                            val x1i = minOf(x0i + 1, side - 1)
                            val wx = fx - x0i
                            // 无增益：DDColor 原生色度就是正确状态（1.9.45/46 的效果用户认可过）。
                            // 实测 ×1.5 会过饱和色偏（「乱上色」），×1.35 也偏浓——增益路线废弃
                            val a = bilerp(buf, 0, side, x0i, x1i, y0i, y1i, wx, wy)
                            val b = bilerp(buf, side * side, side, x0i, x1i, y0i, y1i, wx, wy)
                            val i = row + x
                            outPx[i] = labToRgb(
                                (gray0[i].toInt() and 0xFF) * 100.0 / 255.0,
                                a.toDouble(), b.toDouble(),
                            )
                        }
                    }
                    return Bitmap.createBitmap(outPx, w0, h0, Bitmap.Config.ARGB_8888)
                }
                if (buf.remaining() == 3 * side * side) {
                    // 某些导出直接给 RGB
                    val outPx = IntArray(plane0)
                    for (y in 0 until h0) {
                        val fy = y * side.toFloat() / h0
                        val y0i = fy.toInt().coerceAtMost(side - 1)
                        val y1i = minOf(y0i + 1, side - 1)
                        val wy = fy - y0i
                        val row = y * w0
                        for (x in 0 until w0) {
                            val fx = x * side.toFloat() / w0
                            val x0i = fx.toInt().coerceAtMost(side - 1)
                            val x1i = minOf(x0i + 1, side - 1)
                            val wx = fx - x0i
                            val i = row + x
                            val r = clamp255(
                                bilerp(buf, 0, side, x0i, x1i, y0i, y1i, wx, wy)
                            ).coerceIn(0, 255)
                            val g = clamp255(
                                bilerp(buf, side * side, side, x0i, x1i, y0i, y1i, wx, wy)
                            ).coerceIn(0, 255)
                            val b = clamp255(
                                bilerp(buf, 2 * side * side, side, x0i, x1i, y0i, y1i, wx, wy)
                            ).coerceIn(0, 255)
                            outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    return Bitmap.createBitmap(outPx, w0, h0, Bitmap.Config.ARGB_8888)
                }
                logError("realistic 模型输出通道数异常: " + buf.remaining())
                return null
            }
        }
    }

    // ---- 工具 -----------------------------------------------------------

    private fun clamp255(f: Float): Int = (f * 255f).toInt().coerceIn(0, 255)

    /** Lab(sRGB, D65) → ARGB int。L 0..100，a/b ±128。 */
    private fun labToRgb(L: Double, a: Double, b: Double): Int {
        val fy = (L + 16.0) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - b / 200.0
        val eps = 0.008856
        val k = 903.3
        val xr = if (Math.pow(fx, 3.0) > eps) Math.pow(fx, 3.0) else (116 * fx - 16) / k
        val yr = if (L > k * eps) Math.pow((L + 16) / 116.0, 3.0) else L / k
        val zr = if (Math.pow(fz, 3.0) > eps) Math.pow(fz, 3.0) else (116 * fz - 16) / k
        val X = xr * 0.95047
        val Y = yr
        val Z = zr * 1.08883
        val r = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z
        val g = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z
        val b2 = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z
        fun gamma(c: Double): Int {
            val v = c.coerceIn(0.0, 1.0)
            val lin = if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
            return (lin * 255).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (gamma(r) shl 16) or (gamma(g) shl 8) or gamma(b2)
    }

    /** FloatArray 里 (x,y) 的双线性采样（源宽 sw）。 */
    private fun bilerpArr(
        src: FloatArray, sw: Int,
        x0: Int, x1: Int, y0: Int, y1: Int, wx: Float, wy: Float,
    ): Float {
        val v00 = src[y0 * sw + x0]
        val v10 = src[y0 * sw + x1]
        val v01 = src[y1 * sw + x0]
        val v11 = src[y1 * sw + x1]
        return v00 * (1 - wx) * (1 - wy) + v10 * wx * (1 - wy) +
            v01 * (1 - wx) * wy + v11 * wx * wy
    }

    /** FloatBuffer 里某通道的 (x,y) 双线性采样（通道偏移 chBase，源宽 sw）。 */
    private fun bilerp(
        buf: java.nio.FloatBuffer, chBase: Int, sw: Int,
        x0: Int, x1: Int, y0: Int, y1: Int, wx: Float, wy: Float,
    ): Float {
        val v00 = buf.get(chBase + y0 * sw + x0)
        val v10 = buf.get(chBase + y0 * sw + x1)
        val v01 = buf.get(chBase + y1 * sw + x0)
        val v11 = buf.get(chBase + y1 * sw + x1)
        return v00 * (1 - wx) * (1 - wy) + v10 * wx * (1 - wy) +
            v01 * (1 - wx) * wy + v11 * wx * wy
    }

    /** 灰世界白平衡去黄：三通道均值拉向其中间值（strength 0~1） */
    private fun grayWorldBalance(px: IntArray, strength: Float): IntArray {
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var n = 0.0
        var i = 0
        while (i < px.size) {
            sr += Color.red(px[i])
            sg += Color.green(px[i])
            sb += Color.blue(px[i])
            n++
            i += 7
        }
        if (n <= 0.0) return px
        val mr = sr / n
        val mg = sg / n
        val mb = sb / n
        val target = (mr + mg + mb) / 3.0
        val gr = 1.0 + strength * (target / max(mr, 1e-3) - 1.0)
        val gg = 1.0 + strength * (target / max(mg, 1e-3) - 1.0)
        val gb = 1.0 + strength * (target / max(mb, 1e-3) - 1.0)
        val out = IntArray(px.size)
        for (j in px.indices) {
            val p = px[j]
            out[j] = Color.rgb(
                (Color.red(p) * gr).toInt().coerceIn(0, 255),
                (Color.green(p) * gg).toInt().coerceIn(0, 255),
                (Color.blue(p) * gb).toInt().coerceIn(0, 255),
            )
        }
        return out
    }

    /** 推理/下载的失败落一份日志（Android/data/com.enhance.comic/files/colorize_error.log） */
    private fun logError(msg: String) {
        try {
            val dir = appContext.getExternalFilesDir(null) ?: return
            File(dir, "colorize_error.log").appendText(
                "\n===== " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " =====\n" + msg + "\n"
            )
        } catch (_: Throwable) { }
    }
}

// ==================================================================== 画质增强（超分）

/**
 * realesr-animevideov3 超分（ONNX fp32 2.4MB **随包内置** assets/models/，
 * 选自 Tomoali/realesr-animevideov3-onnx，BSD-3-Clause，上游 xinntao/Real-ESRGAN）。
 *
 * 固定输入 [1,3,256,256]（RGB 0..1 NCHW）→ 输出 [1,3,1024,1024]（4x）。
 * 策略：处理尺寸长边 ≤1024、pad 到 256 倍数、分块推理（重叠 8px）、
 * 拼接后裁 pad、缩回目标显示宽度，最后 line-mix（暗部用原图，防文字/网点被 GAN 重画）。
 * 任何失败返回 null，调用方回退原图，绝不挡阅读。
 */
object SuperRes {
    internal lateinit var appContext: Context
    internal fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val mutex = Mutex()

    private const val SR_ASSET = "models/animevideov3_256.onnx"
    private const val SR_BYTES = 2_494_775L
    private const val TILE = 256
    private const val OVERLAP = 8
    private const val MAX_SIDE = 1024f

    private var session: OrtSession? = null

    private fun srFile(): File = File(File(appContext.filesDir, "models").apply { mkdirs() }, "animevideov3_256.onnx")

    private fun srReady(): Boolean = ::appContext.isInitialized && srFile().length() == SR_BYTES

    private fun acquire(): OrtSession {
        session?.let { return it }
        if (!srReady()) {
            val f = srFile()
            appContext.assets.open(SR_ASSET).use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        val s = env.createSession(srFile().path)
        session = s
        return s
    }

    /** 增强（srMode 2=全页；1=自动——调用方判定低清后调用）。返回 null 表示失败回退。 */
    suspend fun enhance(input: Bitmap, targetWidth: Int): Bitmap? = withContext(Dispatchers.Default) {
        try {
            if (input.width <= 64 || input.height <= 64) return@withContext null
            val s = acquire()
            mutex.withLock { runCore(input, s, targetWidth) }
        } catch (e: Throwable) {
            android.util.Log.e("SuperRes", "superResolve failed: " + android.util.Log.getStackTraceString(e))
            null
        }
    }

    private fun runCore(input: Bitmap, s: OrtSession, targetWidth: Int): Bitmap? {
        val w0 = input.width
        val h0 = input.height
        var pw = w0
        var ph = h0
        val long = maxOf(w0, h0)
        if (long > MAX_SIDE) {
            val k = MAX_SIDE / long
            pw = (w0 * k).roundToInt().coerceAtLeast(1)
            ph = (h0 * k).roundToInt().coerceAtLeast(1)
        }
        val tw = ((pw + TILE - 1) / TILE) * TILE
        val th = ((ph + TILE - 1) / TILE) * TILE

        // 处理尺寸画布（pad 区黑，最后裁掉）
        val full = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val fc = Canvas(full)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        fc.drawBitmap(Bitmap.createScaledBitmap(input, pw, ph, true), 0f, 0f, paint)

        val cols = tw / TILE
        val rows = th / TILE
        val outTile = TILE * 4
        val outOverlap = OVERLAP * 4
        val big = Bitmap.createBitmap(tw * 4, th * 4, Bitmap.Config.ARGB_8888)
        val bc = Canvas(big)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tileBmp = Bitmap.createBitmap(full, c * TILE, r * TILE, TILE, TILE)
                val out = runTile(tileBmp, s) ?: continue
                val cutX = if (c == 0) 0 else outOverlap
                val cutY = if (r == 0) 0 else outOverlap
                val srcW = outTile - cutX - if (c == cols - 1) 0 else outOverlap
                val srcH = outTile - cutY - if (r == rows - 1) 0 else outOverlap
                val piece = Bitmap.createBitmap(out, cutX, cutY, srcW, srcH)
                bc.drawBitmap(piece, (c * TILE * 4 + cutX).toFloat(), (r * TILE * 4 + cutY).toFloat(), paint)
            }
        }

        // 裁掉 pad（内容区在左上），缩回目标显示宽度
        val outW = (pw * 4f).roundToInt()
        val outH = (ph * 4f).roundToInt()
        val croppedB = Bitmap.createBitmap(big, 0, 0, outW, outH)
        val targetH = max(1, (outH.toFloat() / outW * targetWidth).roundToInt())
        val sized = Bitmap.createScaledBitmap(croppedB, targetWidth, targetH, true)

        // line-mix：暗部（原线稿 Y<0.45）用原图放大（保文字/网点），其余用模型
        val origScaled = Bitmap.createScaledBitmap(input, sized.width, sized.height, true)
        val result = Bitmap.createBitmap(sized.width, sized.height, Bitmap.Config.ARGB_8888)
        val ePx = IntArray(sized.width * sized.height)
        val oPx = IntArray(ePx.size)
        sized.getPixels(ePx, 0, sized.width, 0, 0, sized.width, sized.height)
        origScaled.getPixels(oPx, 0, sized.width, 0, 0, sized.width, sized.height)
        // 先整块铺模型输出，再只重画暗部（暗部占比小，避免逐像素 setPixel 的 JNI 开销）
        result.setPixels(ePx, 0, sized.width, 0, 0, sized.width, sized.height)
        val w = sized.width
        for (i in ePx.indices) {
            val r0 = (oPx[i] shr 16) and 0xFF
            val g0 = (oPx[i] shr 8) and 0xFF
            val b0 = oPx[i] and 0xFF
            val ys = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
            val mask = ((0.45f - ys) / 0.22f).coerceIn(0f, 1f)
            if (mask > 0f) {
                val er = (ePx[i] shr 16) and 0xFF
                val eg = (ePx[i] shr 8) and 0xFF
                val eb = ePx[i] and 0xFF
                val rr = (oPx[i] and 0xFF000000.toInt()) or
                    ((r0 * mask + er * (1 - mask)).roundToInt() shl 16) or
                    ((g0 * mask + eg * (1 - mask)).roundToInt() shl 8) or
                    (b0 * mask + eb * (1 - mask)).roundToInt()
                result.setPixel(i % w, i / w, rr)
            }
        }
        return result
    }

    private fun o0r(y: Int, e: Int, mask: Float): Int = (y * mask + e * (1 - mask)).roundToInt().coerceIn(0, 255)
    private fun o0g(y: Int, e: Int, mask: Float): Int = (y * mask + e * (1 - mask)).roundToInt().coerceIn(0, 255)
    private fun o0b(y: Int, e: Int, mask: Float): Int = (y * mask + e * (1 - mask)).roundToInt().coerceIn(0, 255)

    private fun runTile(tileBmp: Bitmap, s: OrtSession): Bitmap? {
        val plane = TILE * TILE
        val inp = FloatArray(3 * plane)
        val px = IntArray(plane)
        tileBmp.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
        for (i in 0 until plane) {
            inp[i] = ((px[i] shr 16) and 0xFF) / 255f
            inp[plane + i] = ((px[i] shr 8) and 0xFF) / 255f
            inp[2 * plane + i] = (px[i] and 0xFF) / 255f
        }
        val inName = s.inputNames.firstOrNull() ?: return null
        val outName = s.outputNames.firstOrNull() ?: return null
        OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(inp), longArrayOf(1, 3, TILE.toLong(), TILE.toLong())).use { t ->
            s.run(mapOf(inName to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val tensor = out[outName] as? OnnxTensor ?: return null
                val fb = tensor.floatBuffer
                val outT = TILE * 4
                val outPlane = outT * outT
                if (fb.remaining() < outPlane * 3) return null
                val bmp = Bitmap.createBitmap(outT, outT, Bitmap.Config.ARGB_8888)
                val opx = IntArray(outPlane)
                for (i in 0 until outPlane) {
                    val r = (fb[i] * 255f).coerceIn(0f, 255f).toInt()
                    val g = (fb[outPlane + i] * 255f).coerceIn(0f, 255f).toInt()
                    val b = (fb[2 * outPlane + i] * 255f).coerceIn(0f, 255f).toInt()
                    opx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                bmp.setPixels(opx, 0, outT, 0, 0, outT, outT)
                return bmp
            }
        }
    }
}
