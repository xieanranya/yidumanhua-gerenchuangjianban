package com.enhance.comic

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.enhance.comic.data.BookStore
import com.enhance.comic.data.OnlineRecord
import com.enhance.comic.data.SourceEngine
import com.enhance.comic.data.UiStore
import com.enhance.comic.model.Book
import com.enhance.comic.ui.AppFrame
import com.enhance.comic.ui.AppTab
import com.enhance.comic.ui.DiscoverScreen
import com.enhance.comic.ui.DiscoverState
import com.enhance.comic.ui.ExploreCache
import com.enhance.comic.ui.HistoryScreen
import com.enhance.comic.ui.HomeScreen
import com.enhance.comic.ui.HomeState
import com.enhance.comic.ui.LocalColors
import com.enhance.comic.ui.MeScreen
import com.enhance.comic.ui.OnlineSourceScreen
import com.enhance.comic.ui.WorkEntry
import com.enhance.comic.ui.ReaderScreen
import com.enhance.comic.ui.ShelfScreen
import com.enhance.comic.ui.ThemeMode
import com.enhance.comic.ui.colorsFor
import com.enhance.comic.ui.rememberAppVersion
import com.enhance.comic.ui.schemeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 闪退日志落到外部存储（Android/data/com.enhance.comic/files/crash.log），
        // 自己用的 App 没法远程看 logcat，出问题让用户把这一个文件发来就能定位。
        // 每条都带版本号 —— 不然没法区分用户装的是哪一版（同一段报错可能早修掉了）。
        // 版本号从 PackageManager 读（反映实际装的包，不依赖编译期常量）
        val ver = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "v" + pi.versionName + " (" + code + ")"
        }.getOrDefault("v?")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(getExternalFilesDir(null), "crash.log").appendText(
                    "\n===== " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date()) + " $ver thread=" + t.name + " =====\n" +
                        android.util.Log.getStackTraceString(e) + "\n"
                )
            }
            previous?.uncaughtException(t, e)
        }
        // 启动记录也写一份到上色日志：用户发来一个文件就能同时看到「装的哪版 + 何时启动 + 上色/下载的失败原因」
        runCatching {
            java.io.File(getExternalFilesDir(null), "colorize_error.log").appendText(
                "\n===== 启动 $ver " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " =====\n"
            )
        }
        enableEdgeToEdge()
        // 上次的发现页结果读回内存：一进「发现」直接有内容，不用先看一轮菊花
        ExploreCache.attach(applicationContext)
        // 黑白上色引擎的文件路径初始化（模型按需下载到 filesDir/models）
        com.enhance.comic.data.ColorizeEngine.init(applicationContext)
        setContent {
            Root()
        }
    }

    /** Volume-key paging: consumed here because keys never reach the composable tree. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = VolumeKeyBridge.onStep
        if (handler != null &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    handler(down, false)
                } else if (VolumeKeyBridge.repeatEnabled) {
                    handler(down, true)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** 盖在四 Tab 之上的二级页。 */
private sealed class Overlay {
    /** key 为 null 时进源列表；带 comicId 时直接落到那本的详情。 */
    data class Online(
        val key: String?,
        val comicId: String?,
        val title: String,
        /** 同作品多源聚合进来的其它源入口，详情页靠它切源 */
        val alts: List<WorkEntry> = emptyList(),
    ) : Overlay()
    object History : Overlay()
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val store = remember { BookStore(context.applicationContext) }
    val ui = remember { UiStore(context.applicationContext) }
    val engine = remember { SourceEngine.get(context) }
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()

    var themeMode by remember { mutableStateOf(ui.themeMode) }
    val dark = when (themeMode) {
        ThemeMode.AUTO -> systemDark
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    val colors = colorsFor(dark)
    val version = rememberAppVersion()

    var books by remember { mutableStateOf(store.loadBooks()) }
    var tab by remember { mutableStateOf(AppTab.entries.getOrElse(ui.lastTab) { AppTab.HOME }) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var openBook by remember { mutableStateOf<Book?>(null) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf(ui.records()) }
    var prefs by remember { mutableStateOf(store.readerPrefs) }
    var enhance by remember { mutableStateOf(store.enhance) }
    var discoverQuery by remember { mutableStateOf("") }
    // 发现页状态提到这里：Tab 是 when 渲染的，放在页面里的 remember 会随切 Tab 一起丢，
    // 一回来就变成「重新读源 + 重新拉 8 个源」。
    val discoverState = remember { DiscoverState() }
    val homeState = remember { HomeState() }
    // 源开关/顺序改一次就 +1，让首页、发现、我的知道该重新读源列表
    var sourceRev by remember { mutableStateOf(0) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        message = null
    }

    // Books imported before covers existed get one rendered on first launch.
    LaunchedEffect(Unit) {
        val current = books
        if (current.isEmpty() || current.all { it.coverPath.isNotEmpty() }) return@LaunchedEffect
        val fixed = withContext(Dispatchers.IO) { store.backfillCovers(current) }
        if (fixed.any { it.coverPath.isNotEmpty() }) {
            books = fixed
            store.saveBooks(fixed)
        }
    }

    fun switchTab(next: AppTab) {
        tab = next
        ui.lastTab = next.ordinal
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> store.importDocument(uri, null) }
            }
            if (added.isNotEmpty()) {
                val merged = books + added
                books = merged
                store.saveBooks(merged)
            }
            importing = false
            if (added.size < uris.size) {
                message = "有 ${uris.size - added.size} 个文件无法识别（支持 ZIP / CBZ / RAR / CBR / 7Z / TAR / PDF）"
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        importing = true
        scope.launch {
            val book = withContext(Dispatchers.IO) { store.importFolder(uri) }
            if (book != null) {
                val merged = books + book
                books = merged
                store.saveBooks(merged)
            } else {
                message = "这个文件夹里没有可用的图片"
            }
            importing = false
        }
    }

    BackHandler(
        enabled = overlay != null || openBook != null || tab != AppTab.HOME,
    ) {
        when {
            openBook != null -> openBook = null
            overlay != null -> overlay = null
            else -> switchTab(AppTab.HOME)
        }
    }

    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = schemeFor(dark)) {
            Surface(modifier = Modifier.fillMaxSize().background(colors.bg), color = colors.bg) {
                val reader = openBook
                val over = overlay
                when {
                    reader != null -> ReaderScreen(
                        book = reader,
                        store = store,
                        onBack = { sourcePage, marks, names ->
                            val updated = reader.copy(
                                lastPage = sourcePage,
                                bookmarks = marks,
                                bookmarkNames = names,
                                lastReadAt = System.currentTimeMillis(),
                            )
                            store.saveBooks(
                                store.loadBooks().map { if (it.id == updated.id) updated else it },
                            )
                            books = store.loadBooks()
                            openBook = null
                        },
                    )

                    over is Overlay.History -> HistoryScreen(
                        records = records,
                        onBack = { overlay = null },
                        onOpen = { r ->
                            overlay = Overlay.Online(r.source, r.comicId, r.title)
                        },
                        onClear = {
                            ui.clearRecords()
                            records = emptyList()
                        },
                    )

                    over is Overlay.Online -> OnlineSourceScreen(
                        startKey = over.key,
                        startComicId = over.comicId,
                        startTitle = over.title,
                        startAlts = over.alts,
                        uiStore = ui,
                        // 阅读设置和本地阅读器共用同一份：在线看图也能调间距/滤镜/亮度
                        prefs = prefs,
                        enhance = enhance,
                        onPrefs = { prefs = it; store.readerPrefs = it },
                        onEnhance = { enhance = it; store.enhance = it },
                        onRecordsChanged = { records = ui.records() },
                        onSourcesChanged = { sourceRev++ },
                        onBack = { overlay = null },
                    )

                    else -> AppFrame(selected = tab, onSelect = { switchTab(it) }) {
                        when (tab) {
                            AppTab.HOME -> HomeScreen(
                                books = books,
                                engine = engine,
                                ui = ui,
                                state = homeState,
                                sourceRev = sourceRev,
                                onOpenBook = { openBook = it },
                                onDiscover = { switchTab(AppTab.DISCOVER) },
                                onOpenWork = { w ->
                                    overlay = Overlay.Online(
                                        w.primary.sourceKey,
                                        w.primary.comicId,
                                        w.primary.title,
                                        w.entries,
                                    )
                                },
                            )

                            AppTab.DISCOVER -> DiscoverScreen(
                                engine = engine,
                                ui = ui,
                                state = discoverState,
                                sourceRev = sourceRev,
                                initialQuery = discoverQuery,
                                onOpenWork = { w ->
                                    overlay = Overlay.Online(
                                        w.primary.sourceKey,
                                        w.primary.comicId,
                                        w.primary.title,
                                        w.entries,
                                    )
                                },
                                onSourceManager = { overlay = Overlay.Online(null, null, "") },
                            )

                            AppTab.SHELF -> ShelfScreen(
                                books = books,
                                importing = importing,
                                version = version,
                                onOpen = { openBook = it },
                                onImportFiles = { pickFiles.launch(arrayOf("*/*")) },
                                onImportFolder = { runCatching { pickFolder.launch(null) } },
                                onDelete = { book ->
                                    store.deleteBook(book)
                                    books = store.loadBooks()
                                },
                                themeLabel = themeMode.label,
                                onThemeToggle = {
                                    val next = when (themeMode) {
                                        ThemeMode.AUTO -> ThemeMode.DAY
                                        ThemeMode.DAY -> ThemeMode.NIGHT
                                        ThemeMode.NIGHT -> ThemeMode.AUTO
                                    }
                                    themeMode = next
                                    ui.themeMode = next
                                },
                            )

                            AppTab.ME -> MeScreen(
                                books = books,
                                engine = engine,
                                ui = ui,
                                sourceRev = sourceRev,
                                version = version,
                                themeMode = themeMode,
                                recordCount = records.size,
                                onTheme = {
                                    themeMode = it
                                    ui.themeMode = it
                                },
                                onSourceManager = { overlay = Overlay.Online(null, null, "") },
                                onHistory = { overlay = Overlay.History },
                                onShelf = { switchTab(AppTab.SHELF) },
                            )
                        }
                    }
                }
            }
        }
    }

}
