package com.enhance.comic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.data.DownloadManager
import com.enhance.comic.data.ImageUtil
import com.enhance.comic.model.Book
import com.enhance.comic.model.BookType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/** 下载中心（「我的」页入口）：集中管理全部下载任务；已完成的书支持导出。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCenterScreen(
    books: List<Book>,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val c = LocalColors.current
    val tasks = DownloadManager.tasks.collectAsState().value
    var exportTask by remember { mutableStateOf<DownloadManager.DlTask?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadManager.DlTask?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar(
            "下载管理",
            onBack,
            action = {
                if (tasks.isNotEmpty()) MiniButton("${tasks.size} 个任务", {})
            },
        )
        if (tasks.isEmpty()) {
            EmptyHint(
                title = "还没有下载任务",
                message = "在在线漫画的详情页点「下载全本」即可离线阅读。",
                icon = Glyph.DOWN,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    val path = DownloadManager.bookPath(task.id)
                    val book = books.firstOrNull { it.type == BookType.FOLDER && it.path == path }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(c.card2)
                            .combinedClickable(onClick = { book?.let(onOpenBook) })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(44.dp)
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(c.chip),
                                contentAlignment = Alignment.Center,
                            ) {
                                val coverBitmap = book?.coverPath?.let { rememberFileCover(it) }
                                if (coverBitmap != null) {
                                    Image(
                                        bitmap = coverBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().height(60.dp),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else if (task.cover.isNotBlank()) {
                                    RemoteImage(
                                        url = task.cover,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Text(
                                        task.title.take(1),
                                        color = c.fg3,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.title,
                                    color = c.fg,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${task.sourceName} · ${statusLine(task)}",
                                    color = c.fg3,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (task.status == 0 || task.status == 1 || task.status == 2) {
                                    Spacer(Modifier.height(5.dp))
                                    LinearProgressIndicator(
                                        progress = { task.progress.toFloat() },
                                        modifier = Modifier.fillMaxWidth().height(3.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = c.accent,
                                        trackColor = c.chip,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            if (task.status == 3) {
                                MiniButton("导出", { exportTask = task }, accent = true)
                            }
                            if (book != null) MiniButton("阅读", { onOpenBook(book) })
                            when (task.status) {
                                0, 1 -> MiniButton("暂停", { DownloadManager.toggle(task.id) })
                                2, 4 -> MiniButton("继续", { DownloadManager.toggle(task.id) })
                            }
                            MiniButton("删除", { pendingDelete = task })
                        }
                    }
                }
            }
        }
    }

    exportTask?.let { t ->
        ExportSheet(task = t, onDismiss = { exportTask = null })
    }

    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载任务？") },
            text = { Text("《${t.title}》的全部离线文件会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.remove(t.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun statusLine(t: DownloadManager.DlTask): String {
    val ch = t.chapters.firstOrNull { it.status == 1 }
    val tail = ch?.let { " · ${it.title}" } ?: ""
    return when (t.status) {
        0 -> "排队中 · ${t.doneChapters}/${t.chapters.size} 话$tail"
        1 -> "下载中 ${(t.progress * 100).toInt()}% · ${t.donePages}/${t.totalPages} 页$tail"
        2 -> "已暂停 · ${t.doneChapters}/${t.chapters.size} 话"
        3 -> "已下载 · ${t.chapters.size} 话 · ${t.totalPages} 页"
        else -> "部分章节失败 · 点「继续」重试"
    }
}

// ================================================================================ 导出面板

/** 单本导出：整本 CBZ 打包 / 单张页图（JPG / PNG / WebP），SAF 选保存位置。 */
@Composable
private fun ExportSheet(task: DownloadManager.DlTask, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val total = max(task.totalPages, 1)
    var page by remember { mutableIntStateOf(1) }
    var fmt by remember { mutableStateOf("jpg") }
    var busy by remember { mutableStateOf(false) }
    val safeName = task.title
        .replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "export" }
        .take(50)

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            busy = true
            ExportWorker.exportCbz(context, task, uri) { ok ->
                busy = false
                toast(context, if (ok) "CBZ 导出完成" else "导出失败")
            }
        }
    }
    val imgMime = when (fmt) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val imgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(imgMime),
    ) { uri ->
        if (uri != null) {
            val p = page
            val f = fmt
            busy = true
            ExportWorker.exportPage(context, task, p - 1, f, uri) { ok ->
                busy = false
                toast(context, if (ok) "已导出第 $p 页（${f.uppercase()}）" else "导出失败")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                "导出 · ${task.title}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                SectionHeader("整本打包", inset = false)
                MiniButton(
                    "导出 CBZ（${task.chapters.size} 话 · ${task.donePages} 页）",
                    {
                        if (!busy) zipLauncher.launch("$safeName.cbz")
                    },
                    accent = true,
                )
                Spacer(Modifier.height(12.dp))
                SectionHeader("单张导出", inset = false)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    MiniButton("−", { if (page > 1) page -= 1 })
                    Text(
                        "第 $page / $total 页",
                        color = c.fg,
                        fontSize = 12.sp,
                    )
                    MiniButton("＋", { if (page < total) page += 1 })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("jpg" to "JPG", "png" to "PNG", "webp" to "WebP").forEach { (k, label) ->
                        Chip(label, fmt == k) { fmt = k }
                    }
                }
                Spacer(Modifier.height(8.dp))
                MiniButton(
                    "导出第 $page 页（${fmt.uppercase()}）",
                    {
                        if (!busy) imgLauncher.launch("${safeName}_p${"%05d".format(page)}.$fmt")
                    },
                    accent = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "选择保存位置后写入；JPG / PNG / WebP 均按原图质量重编码。",
                    color = c.fg3,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun toast(context: Context, msg: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

// ================================================================================ 导出执行

private object ExportWorker {

    private val io = CoroutineScope(Dispatchers.IO)

    /** 整本 CBZ：按章节目录顺序 zip（STORE 流式写，不占内存）。 */
    fun exportCbz(context: Context, task: DownloadManager.DlTask, uri: Uri, onDone: (Boolean) -> Unit) {
        val root = DownloadManager.bookDir(task.id)
        if (root == null) {
            onDone(false)
            return
        }
        io.launch {
            val ok = runCatching {
                var wrote = false
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                        task.chapters.forEach { ch ->
                            val chDir = File(root, ch.dir)
                            val files = chDir.listFiles()
                                ?.filter { ImageUtil.isImageName(it.name) }
                                ?.sortedBy { it.name }
                                ?: emptyList()
                            files.forEach { f ->
                                zos.putNextEntry(ZipEntry("${ch.dir}/${f.name}"))
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                                wrote = true
                            }
                        }
                    }
                }
                wrote
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** 单张页图：全局页序号 → 章节页文件 → 重编码写入。 */
    fun exportPage(
        context: Context,
        task: DownloadManager.DlTask,
        index0: Int,
        fmt: String,
        uri: Uri,
        onDone: (Boolean) -> Unit,
    ) {
        val root = DownloadManager.bookDir(task.id)
        if (root == null) {
            onDone(false)
            return
        }
        io.launch {
            val ok = runCatching {
                val f = pageFile(root, task, index0) ?: return@runCatching false
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@runCatching false
                val format = when (fmt) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                val quality = if (fmt == "png") 100 else 95
                val written = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        bmp.compress(format, quality, os)
                    } == true
                }.getOrDefault(false)
                bmp.recycle()
                written
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** 全局页序号（0 起）→ 已下载页文件；越界或文件缺失返回 null。 */
    private fun pageFile(root: File, task: DownloadManager.DlTask, index0: Int): File? {
        var i = index0
        for (ch in task.chapters) {
            val tot = max(ch.total, 0)
            if (tot > 0 && i < tot) {
                val f = File(File(root, ch.dir), DownloadManager.pageFileName(i))
                return if (f.exists()) f else null
            }
            i -= tot
            if (i < 0) return null
        }
        return null
    }
}
