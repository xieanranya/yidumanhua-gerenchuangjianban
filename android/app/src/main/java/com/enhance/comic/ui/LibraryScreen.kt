package com.enhance.comic.ui

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.model.Book
import com.enhance.comic.model.BookType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val ACCENTS = listOf(
    Color(0xFF6C5CE7) to Color(0xFF9C88FF),
    Color(0xFF0F766E) to Color(0xFF2DD4BF),
    Color(0xFFB45309) to Color(0xFFFBBF24),
    Color(0xFF9D174D) to Color(0xFFF472B6),
    Color(0xFF1D4ED8) to Color(0xFF60A5FA),
    Color(0xFF7C2D12) to Color(0xFFFB923C),
)

/** Decodes the cached cover off the main thread; null means "draw the gradient placeholder". */
@Composable
private fun rememberCover(path: String): ImageBitmap? = produceState<ImageBitmap?>(
    initialValue = null,
    key1 = path,
) {
    value = if (path.isEmpty()) {
        null
    } else {
        withContext(Dispatchers.IO) {
            runCatching {
                if (File(path).isFile) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
            }.getOrNull()
        }
    }
}.value

private fun BookType.label(): String = when (this) {
    BookType.ZIP -> "ZIP"
    BookType.RAR -> "RAR"
    BookType.PDF -> "PDF"
    BookType.FOLDER -> "文件夹"
}

/** 7z / tar 解包成文件夹入库，书架上仍标原容器名，免得用户以为格式变了。 */
private fun Book.badgeLabel(): String = sourceLabel.ifEmpty { type.label() }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    importing: Boolean,
    onOpen: (Book) -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onDelete: (Book) -> Unit,
    onOnline: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    val reading = remember(books) { books.filter { it.lastPage > 0 }.maxByOrNull { it.addedAt } }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(148.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(count = books.size, version = rememberAppVersion())
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onImportFiles,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入漫画")
                    }
                    OutlinedButton(
                        onClick = onImportFolder,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("导入文件夹")
                    }
                    OutlinedButton(
                        onClick = onOnline,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("在线源")
                    }
                }
            }

            if (importing) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                "正在导入，请稍候…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (reading != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ContinueCard(book = reading, onClick = { onOpen(reading) })
                }
            }

            if (books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState()
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "全部漫画",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onOpen(book) },
                        onLongClick = { pendingDelete = book },
                    )
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("会同时删除导入时复制到应用内的文件，你手机里的原始文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(book)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Header(count: Int, version: String) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(
            text = "画质漫画",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(if (count == 0) "本地书本" else "本地书库 · 共 $count 本")
                append(" · 画质增强")
                if (version.isNotEmpty()) append(" · $version")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 版本号直接读自安装包，装完一眼就能确认是哪个版本。 */
@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "v${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueCard(book: Book, onClick: () -> Unit) {
    val progress = if (book.pageCount > 0) {
        (book.lastPage.toFloat() / book.pageCount).coerceIn(0f, 1f)
    } else {
        0f
    }
    val accent = remember(book.id) { ACCENTS[book.id.hashCode().mod(ACCENTS.size)] }
    val cover = rememberCover(book.coverPath)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(accent.first, accent.second))),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        book.title.take(1),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "继续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "第 ${book.lastPage + 1} / ${book.pageCount} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 ZIP / CBZ / RAR / CBR / 7Z / CB7 / TAR / PDF，\n也可以直接导入一个装满图片的文件夹。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    val accent = remember(book.id) { ACCENTS[book.id.hashCode().mod(ACCENTS.size)] }
    val cover = rememberCover(book.coverPath)
    val progress = if (book.pageCount > 0) {
        (book.lastPage.toFloat() / book.pageCount).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.first, accent.second))),
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = book.title.take(1),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.28f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Text(
                    text = book.badgeLabel(),
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (book.lastPage > 0) {
                "${book.pageCount} 页 · 读到 ${book.lastPage + 1}"
            } else {
                "${book.pageCount} 页"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
