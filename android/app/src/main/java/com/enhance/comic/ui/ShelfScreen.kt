package com.enhance.comic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhance.comic.model.Book
import com.enhance.comic.model.BookType

private fun BookType.label(): String = when (this) {
    BookType.ZIP -> "ZIP"
    BookType.RAR -> "RAR"
    BookType.PDF -> "PDF"
    BookType.FOLDER -> "文件夹"
}

/** 7z / tar 解包成文件夹入库，书架上仍标原容器名，免得用户以为格式变了。 */
private fun Book.badgeLabel(): String = sourceLabel.ifEmpty { type.label() }

/** 书架：本地漫画 + 继续阅读。原有的导入 / 长按删除都保留。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    books: List<Book>,
    importing: Boolean,
    version: String,
    onOpen: (Book) -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onDelete: (Book) -> Unit,
    themeLabel: String,
    onThemeToggle: () -> Unit,
) {
    val c = LocalColors.current
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var filter by remember { mutableIntStateOf(0) }

    val reading = remember(books) {
        books.filter { it.lastPage > 0 }
            .maxByOrNull { if (it.lastReadAt > 0) it.lastReadAt else it.addedAt }
    }
    val shown = when (filter) {
        1 -> books.filter { it.lastPage > 0 }
        2 -> books.filter { it.lastPage == 0 }
        else -> books
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (c.dark) 3 else 2),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(if (c.dark) GridGapV else 14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "书架",
                            color = c.fg,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            buildString {
                                append(if (books.isEmpty()) "本地书本" else "本地书库 · ${books.size} 本")
                                if (version.isNotEmpty()) append(" · ").append(version)
                            },
                            color = c.fg3,
                            fontSize = 10.5.sp,
                        )
                    }
                    Text(
                        themeLabel,
                        color = c.fg2,
                        fontSize = 11.5.sp,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                            .background(c.chip)
                            .combinedClickable(onClick = onThemeToggle, onLongClick = onThemeToggle)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    MiniButton("＋ 导入", onImportFiles, accent = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip("全部 ${books.size}", filter == 0) { filter = 0 }
                    Chip("在读 ${books.count { it.lastPage > 0 }}", filter == 1) { filter = 1 }
                    Chip("未读 ${books.count { it.lastPage == 0 }}", filter == 2) { filter = 2 }
                    Spacer(Modifier.weight(1f))
                    MiniButton("导入文件夹", onImportFolder)
                }
            }
        }

        if (importing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text("正在导入，请稍候…", color = c.fg2, fontSize = 11.5.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(
                            androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                        ),
                        color = c.accent,
                        trackColor = c.chip,
                    )
                }
            }
        }

        if (reading != null && filter != 2) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("继续阅读", inset = false)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueCard(
                    seed = reading.title.hashCode(),
                    title = reading.title,
                    meta = "本地 · 第 ${reading.lastPage + 1} / ${reading.pageCount} 页",
                    action = "继续",
                    onClick = { onOpen(reading) },
                    coverBitmap = rememberFileCover(reading.coverPath),
                    inset = false,
                )
            }
        }

        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyHint(
                    title = if (books.isEmpty()) "书架还是空的" else "这一类里没有书",
                    message = if (books.isEmpty()) {
                        "支持 ZIP / CBZ / RAR / CBR / 7Z / TAR / PDF，\n也可以直接导入一个装满图片的文件夹。"
                    } else {
                        "换个筛选看看。"
                    },
                    icon = Glyph.SHELF,
                )
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    if (filter == 0) "本地漫画" else "筛选结果",
                    more = "${shown.size} 本",
                    inset = false,
                )
            }
            items(shown, key = { it.id }) { book ->
                BookGridItem(
                    book = book,
                    small = c.dark,
                    onClick = { onOpen(book) },
                    onLongClick = { pendingDelete = book },
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    book: Book,
    small: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalColors.current
    val cover = rememberFileCover(book.coverPath)
    val radius = if (small) 8.dp else 11.dp
    val progress = if (book.pageCount > 0) {
        (book.lastPage.toFloat() / book.pageCount).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))
                .background(coverBrush(book.title.hashCode(), c.dark)),
        ) {
            if (cover != null) {
                androidx.compose.foundation.Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    book.title.take(2),
                    color = placeholderInk(c.dark),
                    fontSize = (if (small) 18f else 21f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Text(
                book.badgeLabel(),
                color = c.fg,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(c.bg.copy(alpha = 0.72f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress)
                        .height(2.5.dp)
                        .background(c.accent),
                )
            }
        }
        Spacer(Modifier.height(if (small) 6.dp else 7.dp))
        Text(
            book.title,
            color = c.fg,
            fontSize = (if (small) 11f else 12.5f).sp,
            lineHeight = (if (small) 14.5f else 16.5f).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(if (small) 29.dp else 33.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (book.lastPage > 0) {
                "${book.pageCount} 页 · 读到 ${book.lastPage + 1}"
            } else {
                "${book.badgeLabel()} · ${book.pageCount} 页"
            },
            color = c.fg3,
            fontSize = (if (small) 10f else 10.5f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
