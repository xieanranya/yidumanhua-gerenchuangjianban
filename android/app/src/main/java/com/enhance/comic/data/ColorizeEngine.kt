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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 黑白漫画自动上色（安卓方案）：DDColor（阿里达摩院 ICCV 2023，写实风）。
 *
 * 模型：ddcolor-int8.onnx（int8 量化，59.1MB，HuggingFace Faridzar/ddcolor-mirror，Apache-2.0）。
 * 不进 APK（太大）：首次打开开关时从 hf-mirror（国内可达）/ huggingface 双源下载，
 * 存 filesDir/models/ 并校验字节数。
 *
 * 输入 [1,3,512,512] float32：灰度(L)复制成 3 通道，值域 0..1 —— ImageNet normalize
 * 在模型图内（官方 forward 自带），我们只喂 0..1 灰度。
 * 输出 [1,3,512,512]：RGB 0..1。固定 512 正方形（官方导出即如此）：
 * 原图 resize 进 512 推理，输出再放大回原尺寸。
 *
 * 任何失败都返回原图，绝不挡阅读。
 */
object ColorizeEngine {

    const val MODEL_FILE = "ddcolor-int8.onnx"
    const val MODEL_BYTES = 61_926_813
    private val DOWNLOAD_URLS = listOf(
        "https://hf-mirror.com/Faridzar/ddcolor-mirror/resolve/main/ddcolor-int8.onnx",
        "https://huggingface.co/Faridzar/ddcolor-mirror/resolve/main/ddcolor-int8.onnx",
    )

    private lateinit var appContext: Context
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null
    private val runMutex = Mutex()

    /** Application/MainActivity 一进来调一次（拿 filesDir 路径用） */
    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun modelFile(): File = File(File(appContext.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

    fun modelReady(): Boolean = ::appContext.isInitialized &&
        modelFile().let { it.length() == MODEL_BYTES.toLong() }

    // ---- 下载状态（设置页观察） ----------------------------------------

    sealed interface ModelState {
        data object Idle : ModelState
        data object Downloading : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private val _downloadState = MutableStateFlow(
        if (modelReady()) ModelState.Ready as ModelState else ModelState.Idle,
    )
    val downloadState: StateFlow<ModelState> = _downloadState
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    fun refreshState() {
        if (_downloadState.value !is ModelState.Downloading) {
            _downloadState.value = if (modelReady()) ModelState.Ready else ModelState.Idle
            _downloadProgress.value = 0f
        }
    }

    suspend fun download() {
        if (_downloadState.value is ModelState.Downloading) return
        _downloadState.value = ModelState.Downloading
        _downloadProgress.value = 0f
        var last: Exception? = null
        for (u in DOWNLOAD_URLS) {
            try {
                withContext(Dispatchers.IO) { fetchModel(u) }
                if (modelReady()) {
                    _downloadState.value = ModelState.Ready
                    _downloadProgress.value = 1f
                    return
                }
            } catch (e: Exception) {
                last = e
            }
        }
        _downloadState.value = ModelState.Failed(last?.message ?: "模型下载失败：两个源都没成功")
    }

    private fun fetchModel(url: String) {
        val tmp = File(modelFile().path + ".part")
        tmp.delete()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code != 200) throw Exception("下载源返回 $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        // 每下载约 1MB 报一次进度
                        if (read - lastReport >= (1 shl 20)) {
                            lastReport = read
                            if (total > 0) {
                                _downloadProgress.value = (read.toFloat() / total).coerceAtMost(0.99f)
                            }
                        }
                    }
                }
            }
            if (tmp.length() != MODEL_BYTES.toLong()) {
                throw Exception("模型大小校验失败（拿到 ${tmp.length()} 字节）")
            }
            modelFile().delete()
            if (!tmp.renameTo(modelFile())) throw Exception("模型保存失败")
            _downloadProgress.value = 1f
        } finally {
            conn.disconnect()
        }
    }

    // ---- 推理 -----------------------------------------------------------

    /**
     * 上色一页。模型没就绪 / 推理失败都返回 null（调用方回退原图）。
     * 串行执行：同时跑两页会把内存峰值翻倍（512×512×通道 ×中间层）。
     */
    suspend fun colorize(input: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        runMutex.withLock {
            try {
                runColorize(input)
            } catch (e: Throwable) {
                logError("colorize failed: " + android.util.Log.getStackTraceString(e))
                null
            }
        }
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

    private fun acquireSession(): OrtSession {
        session?.let { return it }
        if (!modelReady()) logError("acquireSession: 模型未下载（filesDir/models/${'$'}MODEL_FILE 不存在或大小不对）")
        val s = env.createSession(modelFile().path)
        session = s
        return s
    }

    private fun runColorize(input: Bitmap): Bitmap? {
        val w0 = input.width
        val h0 = input.height
        if (w0 <= 0 || h0 <= 0) return null
        val session = acquireSession()

        // 输入名/输出名动态取（不硬编码）；推理边长固定 512（官方导出默认尺寸）
        val inName = session.inputNames.firstOrNull() ?: return null
        val outName = session.outputNames.firstOrNull() ?: return null
        val side = 512

        // ---- 灰度化到 side×side（正方形，官方管线即如此）--------------------
        val small = Bitmap.createScaledBitmap(input, side, side, true)
        val grayBmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        Canvas(grayBmp).drawBitmap(
            small, 0f, 0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isFilterBitmap = true },
        )
        if (small !== input) small.recycle()

        // ---- 组输入 [1,3,H,W]：灰度复制 3 通道，0..1 ------------------------
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
                val out = (result.get(outName) as? OnnxTensor) ?: return null
                val buf = out.floatBuffer ?: return null
                if (buf.remaining() != plane * 3) return null

                // ---- 输出 RGB → Bitmap → 放大回原尺寸 ------------------------
                val rgba = IntArray(plane)
                for (i in 0 until plane) {
                    rgba[i] = Color.rgb(
                        clamp255(buf.get(i)),
                        clamp255(buf.get(plane + i)),
                        clamp255(buf.get(2 * plane + i)),
                    )
                }
                val bmp = Bitmap.createBitmap(rgba, side, side, Bitmap.Config.ARGB_8888)
                val up = Bitmap.createScaledBitmap(bmp, w0, h0, true)
                if (up !== bmp) bmp.recycle()
                return up
            }
        }
    }

    private fun clamp255(f: Float): Int = (f * 255f).toInt().coerceIn(0, 255)
}
