package com.enhance.comic.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.enhance.comic.VolumeKeyBridge
import com.enhance.comic.data.BookSession
import com.enhance.comic.data.BookStore
import com.enhance.comic.data.Chapter
import com.enhance.comic.model.Book
import com.enhance.comic.model.PageRef
import com.enhance.comic.model.ReaderPrefs
import com.enhance.comic.model.TapAction
import com.enhance.comic.model.buildPageRefs
import com.enhance.comic.model.refIndexOfSource
import com.enhance.comic.render.PageCanvasView
import com.enhance.comic.render.PageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reader screen. The layout follows the reference reader exactly: title row with an
 * overflow menu, a strip of thin line icons that each open a panel in place, and a bottom
 * scrubber. All three tap zones are configurable from ⋮ → 页面按钮设置.
 */
@Composable
fun ReaderScreen(
    book: Book,
    store: BookStore,
    onBack: (sourcePage: Int, bookmarks: List<Int>, bookmarkNames: Map<Int, String>) -> Unit,
) {
    val context = LocalContext.current as? Activity
    val appContext = LocalContext.current

    var prefs by remember { mutableStateOf(store.readerPrefs) }
    var enhance by remember { mutableStateOf(store.enhance) }

    var session by remember { mutableStateOf<BookSession?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var aspects by remember { mutableStateOf(FloatArray(0)) }
    var openFailed by remember { mutableStateOf(false) }

    var current by remember { mutableIntStateOf(0) }
    var showChrome by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(ReaderPanel.NONE) }
    var showMenu by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showBookmarkList by remember { mutableStateOf(false) }
    var showAddBookmark by remember { mutableStateOf(false) }
    var showOrientation by remember { mutableStateOf(false) }
    var showHardwareKeys by remember { mutableStateOf(false) }
    var showTapZones by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }

    var bookmarks by remember { mutableStateOf(book.bookmarks) }
    var bookmarkNames by remember { mutableStateOf(book.bookmarkNames) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var orientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }

    val skin = skinFor(prefs.chromeLight)

    val pageRefs: List<PageRef> = remember(aspects, prefs.splitWidePages) {
        buildPageRefs(aspects, prefs.splitWidePages)
    }
    val provider: PageProvider? = remember(session) {
        val s = session
        if (s == null) {
            null
        } else {
            object : PageProvider {
                override val pageCount: Int get() = s.pageCount
                override fun aspectOf(pageIndex: Int): Float = s.aspectOf(pageIndex)
                override fun displayAspect(pageIndex: Int): Float = s.displayAspectOf(pageIndex)
                override fun cropOf(pageIndex: Int): RectF? = s.cropOf(pageIndex)
                override fun decode(pageIndex: Int, targetWidth: Int): Bitmap? =
                    s.decode(pageIndex, targetWidth)
            }
        }
    }

    val viewRef = remember { mutableStateOf<PageCanvasView?>(null) }
    val refsHolder = remember { mutableStateOf<List<PageRef>>(emptyList()) }
    val currentHolder = remember { mutableStateOf(0) }
    androidx.compose.runtime.SideEffect { currentHolder.value = current }

    val goTo: (Int) -> Unit = { target ->
        val refs = refsHolder.value
        if (refs.isNotEmpty()) {
            val next = target.coerceIn(0, refs.lastIndex)
            if (next != current) {
                current = next
                viewRef.value?.seekTo(next)
            }
        }
    }

    val step: (Boolean) -> Unit = { forward ->
        val v = viewRef.value
        if (v != null) v.stepPage(forward) else goTo(current + if (forward) 1 else -1)
    }

    val sourcePageNow = refsHolder.value.getOrNull(current)?.index ?: 0

    /** 上一话 / 下一话：没有子文件夹章节时退化成翻一页。 */
    val jumpChapter: (Int) -> Unit = { delta ->
        val chapters = session?.chapters.orEmpty()
        val here = session?.chapterAt(sourcePageNow)
        if (chapters.isEmpty() || here == null) {
            step(delta > 0)
        } else {
            val index = chapters.indexOfFirst { it.start == here.start }
            val target = chapters.getOrNull(index + delta)
            if (target == null) {
                Toast.makeText(
                    appContext,
                    if (delta > 0) "已经是最后一话了" else "已经是第一话了",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                goTo(refIndexOfSource(refsHolder.value, target.start))
            }
        }
    }

    val runTap: (Int) -> Unit = { zone ->
        val action = when (zone) {
            0 -> prefs.tapLeftAction
            2 -> prefs.tapRightAction
            else -> prefs.tapCenterAction
        }
        when (action) {
            TapAction.PREV_PAGE -> step(false)
            TapAction.NEXT_PAGE -> step(true)
            TapAction.PREV_CHAPTER -> jumpChapter(-1)
            TapAction.NEXT_CHAPTER -> jumpChapter(1)
            TapAction.TOGGLE_CHROME -> {
                showChrome = !showChrome
                if (!showChrome) panel = ReaderPanel.NONE
            }
            TapAction.OPEN_MENU -> {
                showChrome = true
                showMenu = true
            }
            TapAction.NONE -> Unit
        }
    }

    val stepState = rememberUpdatedState(step)
    DisposableEffect(prefs.volumeKeyPaging, prefs.volumeKeyInvert, prefs.volumeKeyRepeat) {
        var lastRepeatAt = 0L
        VolumeKeyBridge.repeatEnabled = prefs.volumeKeyRepeat
        VolumeKeyBridge.onStep = if (prefs.volumeKeyPaging) {
            { down, repeat ->
                if (repeat) {
                    val now = System.currentTimeMillis()
                    // 长按的系统重复大约每 50ms 一次，这里压到 8 次/秒，免得飙页。
                    if (prefs.volumeKeyRepeat && now - lastRepeatAt > 125) {
                        lastRepeatAt = now
                        stepState.value(if (prefs.volumeKeyInvert) !down else down)
                    }
                } else {
                    stepState.value(if (prefs.volumeKeyInvert) !down else down)
                }
            }
        } else {
            null
        }
        onDispose { VolumeKeyBridge.onStep = null }
    }

    LaunchedEffect(book.id) {
        val opened = withContext(Dispatchers.IO) { BookSession.open(appContext, book) }
        if (opened == null || opened.pageCount <= 0) {
            opened?.close()
            openFailed = true
            return@LaunchedEffect
        }
        session = opened
        pageCount = opened.pageCount
        opened.setAutoCrop(prefs.autoCropWhiteBorders)
        // 比例要用「裁过之后」的：布局和绘制必须用同一个框，否则图会被拉伸。
        aspects = FloatArray(opened.pageCount) { opened.displayAspectOf(it) }
        current = refIndexOfSource(buildPageRefs(aspects, prefs.splitWidePages), book.lastPage)

        withContext(Dispatchers.IO) {
            opened.scanAspects()
            if (opened.autoCrop) opened.scanCrops()
        }
        aspects = FloatArray(opened.pageCount) { opened.displayAspectOf(it) }
    }

    // 裁白边开关一变，页面的宽高比就变了。这里重新扫一遍再刷新比例，不然就是
    // 「开了裁边有的地方变形」—— 图被裁窄了却还塞在原来的框里。
    var cropPrimed by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(prefs.autoCropWhiteBorders, session) {
        val s = session ?: return@LaunchedEffect
        if (!cropPrimed) {
            cropPrimed = true
            return@LaunchedEffect
        }
        s.setAutoCrop(prefs.autoCropWhiteBorders)
        if (prefs.autoCropWhiteBorders) {
            withContext(Dispatchers.IO) { s.scanCrops() }
        }
        aspects = FloatArray(s.pageCount) { s.displayAspectOf(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            VolumeKeyBridge.onStep = null
            session?.close()
        }
    }

    LaunchedEffect(prefs) { store.readerPrefs = prefs }
    LaunchedEffect(enhance) { store.enhance = enhance }
    LaunchedEffect(current) { sliderValue = current.toFloat() }

    // 书签即时落盘。原来只在退出阅读器时回写，切后台被系统杀掉就全丢了。
    LaunchedEffect(bookmarks, bookmarkNames) {
        if (bookmarks == book.bookmarks && bookmarkNames == book.bookmarkNames) {
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching {
                store.saveBooks(
                    store.loadBooks().map {
                        if (it.id == book.id) {
                            it.copy(bookmarks = bookmarks, bookmarkNames = bookmarkNames)
                        } else {
                            it
                        }
                    },
                )
            }
        }
    }

    // 进度防抖 1.5s 落盘，避免每翻一页写一次文件。
    LaunchedEffect(current) {
        delay(1500)
        val source = refsHolder.value.getOrNull(current)?.index ?: return@LaunchedEffect
        if (source == book.lastPage) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                store.saveBooks(
                    store.loadBooks().map { if (it.id == book.id) it.copy(lastPage = source) else it },
                )
            }
        }
    }

    LaunchedEffect(prefs.autoBrightness, prefs.brightness) {
        val window = context?.window ?: return@LaunchedEffect
        val lp = window.attributes
        lp.screenBrightness = if (prefs.autoBrightness) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            (prefs.brightness / 100f).coerceIn(0.05f, 1f)
        }
        window.attributes = lp
    }

    LaunchedEffect(orientation) { context?.requestedOrientation = orientation }

    // 栏体自动隐藏：面板或菜单开着时不收。
    LaunchedEffect(showChrome, current, panel, showMenu) {
        if (showChrome && panel == ReaderPanel.NONE && !showMenu) {
            delay(4000)
            showChrome = false
        }
    }

    LaunchedEffect(pageRefs) {
        val v = viewRef.value
        val previous = refsHolder.value
        val source = previous.getOrNull(current)?.index
        val next = if (source != null) refIndexOfSource(pageRefs, source) else current
        refsHolder.value = pageRefs
        current = next.coerceIn(0, maxOf(0, pageRefs.lastIndex))
        if (v != null && pageRefs.isNotEmpty()) {
            v.setPageRefs(pageRefs)
            v.seekTo(current)
        }
    }

    LaunchedEffect(prefs.readingMode) {
        val v = viewRef.value ?: return@LaunchedEffect
        delay(40)
        v.setPageRefs(refsHolder.value)
        v.seekTo(currentHolder.value)
    }

    fun leave() {
        val source = refsHolder.value.getOrNull(current)?.index ?: 0
        onBack(source, bookmarks, bookmarkNames)
    }

    BackHandler {
        when {
            showTapZones -> showTapZones = false
            showHardwareKeys -> showHardwareKeys = false
            showOrientation -> showOrientation = false
            showAddBookmark -> showAddBookmark = false
            showBookmarkList -> showBookmarkList = false
            showChapters -> showChapters = false
            showMenu -> showMenu = false
            panel != ReaderPanel.NONE -> panel = ReaderPanel.NONE
            showChrome -> leave()
            else -> showChrome = true
        }
    }

    val sourcePage = refsHolder.value.getOrNull(current)?.index ?: 0
    val marked = sourcePage in bookmarks
    val chapter = if (pageCount > 0 && session?.hasChapters == true) {
        session?.chapterAt(sourcePage)
    } else {
        null
    }
    val pageLabel = buildString {
        chapter?.title?.takeIf { it.isNotBlank() }?.let {
            append(it)
            append("  ·  ")
        }
        append(sourcePage + 1)
        append(" / ")
        append(maxOf(pageCount, 1))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (provider != null && pageRefs.isNotEmpty()) {
            AndroidView(
                factory = { ctx ->
                    PageCanvasView(ctx).apply {
                        onPageChanged = { idx ->
                            if (idx in refsHolder.value.indices) current = idx
                        }
                        onTapZone = { zone -> runTap(zone) }
                        viewRef.value = this
                        post {
                            setPageRefs(refsHolder.value)
                            seekTo(currentHolder.value)
                        }
                    }
                },
                update = { view ->
                    view.setProvider(provider)
                    view.setPageRefs(pageRefs)
                    view.applySettings(prefs, enhance)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (openFailed) {
            OpenFailed(skin, onBack = { leave() })
        } else if (provider == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        AnimatedVisibility(
            visible = showChrome && !openFailed,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopChrome(
                title = book.title,
                pageLabel = pageLabel,
                prefs = prefs,
                enhance = enhance,
                panel = panel,
                marked = marked,
                hasChapters = session?.hasChapters == true,
                onPanel = { panel = it },
                onBack = { leave() },
                onMenu = { showMenu = true },
                onOpenChapters = { showChapters = true },
                onAddBookmark = {
                    addName = ""
                    showAddBookmark = true
                },
                onShowBookmarks = { showBookmarkList = true },
                onPrefs = { prefs = it },
                onEnhance = { enhance = it },
            )
        }

        AnimatedVisibility(
            visible = showChrome && !openFailed,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) {
            ReaderBottomChrome(
                pageLabel = pageLabel,
                sliderValue = sliderValue,
                total = pageRefs.size,
                light = prefs.chromeLight,
                onChange = { sliderValue = it },
                onSeek = { goTo(it) },
                onPrev = { step(false) },
                onNext = { step(true) },
            )
        }

        if (showMenu) {
            val entries = mutableListOf<MenuEntry>()
            fun item(label: String, checked: Boolean? = null, action: () -> Unit) {
                entries += MenuEntry(label, checked, action)
            }
            item("屏幕方向") { showOrientation = true }
            item("手机硬件按钮") { showHardwareKeys = true }
            item("页面按钮设置") { showTapZones = true }
            item("双击缩放", prefs.doubleTapZoom) {
                prefs = prefs.copy(doubleTapZoom = !prefs.doubleTapZoom)
            }
            item("滤镜") {
                showChrome = true
                panel = ReaderPanel.FILTER
            }
            item("添加书签") {
                addName = ""
                showAddBookmark = true
            }
            item("书签列表") { showBookmarkList = true }
            if (session?.hasChapters == true) {
                item("目录") { showChapters = true }
            }
            item("设置漫画封面") {
                val updated = store.setCoverFromPage(book, sourcePage)
                Toast.makeText(
                    appContext,
                    if (updated != null) "封面已更新" else "这张页面无法用作封面",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            ReaderMenuPanel(entries = entries, light = prefs.chromeLight) { showMenu = false }
        }
    }

    // ------------------------------------------------------------------------ dialogs

    if (showOrientation) {
        val options = listOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED to "跟随系统",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "竖屏",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "横屏",
        )
        SkinDialog("屏幕方向", prefs.chromeLight, onDismiss = { showOrientation = false }) {
            options.forEach { (value, label) ->
                SkinRadioRow(label, orientation == value, skin) {
                    orientation = value
                    showOrientation = false
                }
            }
        }
    }

    if (showHardwareKeys) {
        SkinDialog("手机硬件按钮", prefs.chromeLight, onDismiss = { showHardwareKeys = false }) {
            KeySwitchRow("音量键翻页", prefs.volumeKeyPaging, skin) {
                prefs = prefs.copy(volumeKeyPaging = it)
            }
            KeySwitchRow(
                "音量键反向（上 = 下一页）",
                prefs.volumeKeyInvert,
                skin,
                enabled = prefs.volumeKeyPaging,
            ) { prefs = prefs.copy(volumeKeyInvert = it) }
            KeySwitchRow(
                "长按连续翻页",
                prefs.volumeKeyRepeat,
                skin,
                enabled = prefs.volumeKeyPaging,
            ) { prefs = prefs.copy(volumeKeyRepeat = it) }
            Text(
                "关闭「音量键翻页」后，音量键恢复调节音量。",
                color = skin.dim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (showTapZones) {
        SkinDialog("页面按钮设置", prefs.chromeLight, onDismiss = { showTapZones = false }) {
            TapZoneTable(prefs, skin) { prefs = it }
        }
    }

    if (showAddBookmark) {
        SkinDialog("添加书签", prefs.chromeLight, onDismiss = { showAddBookmark = false }) {
            Text(
                "第 ${sourcePage + 1} 页 / 共 ${maxOf(pageCount, 1)} 页",
                color = skin.dim,
                fontSize = 10.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            SkinTextField(addName, skin, "书签名称（可留空）") { addName = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showAddBookmark = false }) {
                    Text("取消", color = skin.dim, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    if (sourcePage !in bookmarks) bookmarks = bookmarks + sourcePage
                    bookmarkNames = bookmarkNames + (
                        sourcePage to addName.ifBlank { "第 ${sourcePage + 1} 页" }
                        )
                    showAddBookmark = false
                }) { Text("保存", color = skin.accent, fontSize = 12.sp) }
            }
        }
    }

    if (showBookmarkList) {
        SkinDialog("书签列表", prefs.chromeLight, onDismiss = { showBookmarkList = false }) {
            if (bookmarks.isEmpty()) {
                Text("还没有书签。", color = skin.dim, fontSize = 12.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(bookmarks.sorted()) { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showBookmarkList = false
                                    goTo(refIndexOfSource(refsHolder.value, page))
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = bookmarkNames[page] ?: "第 ${page + 1} 页",
                                    color = skin.fg,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${page + 1} / ${maxOf(pageCount, 1)}",
                                    color = skin.dim,
                                    fontSize = 10.sp,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clickable {
                                        bookmarks = bookmarks - page
                                        bookmarkNames = bookmarkNames - page
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text("删除", color = skin.dim, fontSize = 10.5.sp) }
                        }
                    }
                }
            }
        }
    }

    if (showChapters) {
        SkinDialog("目录", prefs.chromeLight, onDismiss = { showChapters = false }) {
            val chapters = session?.chapters.orEmpty()
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(chapters) { item: Chapter ->
                    val active = item.contains(sourcePage)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showChapters = false
                                goTo(refIndexOfSource(refsHolder.value, item.start))
                            }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title.ifBlank { "第 ${item.start + 1} 页起" },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (active) skin.accent else skin.fg,
                            fontSize = 12.5.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text("${item.count} 页", color = skin.dim, fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ helpers

@Composable
private fun OpenFailed(skin: Skin, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("这本书打不开", color = skin.fg, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("文件可能已损坏，或压缩包里没有可识别的图片。", color = skin.dim, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onBack) { Text("返回书架", color = skin.accent) }
        }
    }
}

@Composable
private fun KeySwitchRow(
    label: String,
    checked: Boolean,
    skin: Skin,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) skin.fg else skin.dim.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        SkinSwitch(checked && enabled, skin) { if (enabled) onChange(it) }
    }
}

/** 左侧 / 中间 / 右侧三列的动作表格，和原版的「页面按钮设置」一一对应。 */
@Composable
private fun TapZoneTable(prefs: ReaderPrefs, skin: Skin, onPrefs: (ReaderPrefs) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("点击区域", color = skin.dim, fontSize = 10.sp, modifier = Modifier.weight(1f))
        for (name in listOf("左", "中", "右")) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                Text(name, color = skin.dim, fontSize = 10.5.sp)
            }
        }
    }
    TapAction.entries.forEach { action ->
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.label,
                color = skin.fg,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            for (zone in 0..2) {
                val current = when (zone) {
                    0 -> prefs.tapLeftAction
                    2 -> prefs.tapRightAction
                    else -> prefs.tapCenterAction
                }
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(34.dp)
                        .clickable {
                            onPrefs(
                                when (zone) {
                                    0 -> prefs.copy(tapLeftAction = action)
                                    2 -> prefs.copy(tapRightAction = action)
                                    else -> prefs.copy(tapCenterAction = action)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (current == action) {
                        GlyphView(Glyph.CHECK, skin.accent, 15.dp, 2.dp)
                    }
                }
            }
        }
    }
    Text(
        "竖向阅读时中间区域建议保留「显示 / 隐藏栏」。",
        color = skin.dim,
        fontSize = 9.5.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
