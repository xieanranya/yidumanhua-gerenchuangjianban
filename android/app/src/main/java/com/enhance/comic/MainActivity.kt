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
import com.enhance.comic.ui.HistoryScreen
import com.enhance.comic.ui.HomeScreen
import com.enhance.comic.ui.LocalColors
import com.enhance.comic.ui.MeScreen
import com.enhance.comic.ui.OnlineSourceScreen
import com.enhance.comic.ui.WorkEntry
import com.enhance.comic.ui.ReaderScreen
import com.enhance.comic.ui.ReaderSettingsSheet
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
        enableEdgeToEdge()
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
    var showReaderSheet by remember { mutableStateOf(false) }
    var prefs by remember { mutableStateOf(store.readerPrefs) }
    var enhance by remember { mutableStateOf(store.enhance) }
    var discoverQuery by remember { mutableStateOf("") }
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
                                sourceRev = sourceRev,
                                onOpenBook = { openBook = it },
                                onDiscover = { switchTab(AppTab.DISCOVER) },
                                onSourceManager = { overlay = Overlay.Online(null, null, "") },
                                onImport = { pickFiles.launch(arrayOf("*/*")) },
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
                                onReaderSettings = { showReaderSheet = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showReaderSheet) {
        ReaderSettingsSheet(
            prefs = prefs,
            enhance = enhance,
            onPrefsChange = {
                prefs = it
                store.readerPrefs = it
            },
            onEnhanceChange = {
                enhance = it
                store.enhance = it
            },
            onDismiss = { showReaderSheet = false },
        )
    }
}
