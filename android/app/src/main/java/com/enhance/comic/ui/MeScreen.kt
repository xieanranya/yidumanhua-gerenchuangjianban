package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.enhance.comic.data.OnlineRecord
import com.enhance.comic.data.SourceEngine
import com.enhance.comic.model.Book

/** 「我的」：源管理、阅读记录、外观、本地漫画、阅读设置、关于。 */
@Composable
fun MeScreen(
    books: List<Book>,
    engine: SourceEngine,
    version: String,
    themeMode: ThemeMode,
    recordCount: Int,
    onTheme: (ThemeMode) -> Unit,
    onSourceManager: () -> Unit,
    onHistory: () -> Unit,
    onShelf: () -> Unit,
    onReaderSettings: () -> Unit,
) {
    val c = LocalColors.current
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var pickTheme by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { rows = buildRows(engine.sources()) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { BigTitle("我的") }
        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.card2)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(coverBrush(7, c.dark)),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphView(Glyph.USER, c.fg, 22.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "画质漫画",
                        color = c.fg,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append("本地 ${books.size} 本")
                            if (rows.isNotEmpty()) append(" · ").append(sourceSummary(rows))
                        },
                        color = c.fg3,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(10.dp)) }

        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                SettingRow(
                    glyph = Glyph.DISCOVER,
                    title = "漫画源管理",
                    desc = sourceSummary(rows),
                    value = null,
                    onClick = onSourceManager,
                )
                HLine()
                SettingRow(
                    glyph = Glyph.LIST,
                    title = "阅读记录",
                    desc = if (recordCount == 0) "还没有在线记录" else "在线 · 最近 $recordCount 条",
                    value = null,
                    onClick = onHistory,
                )
                HLine()
                SettingRow(
                    glyph = Glyph.THEME,
                    title = "外观",
                    desc = "夜间纯黑 / 白天纯白",
                    value = themeMode.label,
                    onClick = { pickTheme = true },
                )
                HLine()
                SettingRow(
                    glyph = Glyph.SHELF,
                    title = "本地漫画",
                    desc = "导入、扫描文件夹",
                    value = "${books.size} 本",
                    onClick = onShelf,
                )
                HLine()
                SettingRow(
                    glyph = Glyph.GEAR,
                    title = "阅读设置",
                    desc = "裁白边、锐化、蓝光、翻页方式",
                    value = null,
                    onClick = onReaderSettings,
                )
                HLine()
                SettingRow(
                    glyph = Glyph.SPARK,
                    title = "关于",
                    desc = if (version.isBlank()) "双端自用" else "版本 $version",
                    value = null,
                    onClick = { about = true },
                )
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            Text(
                "纯自用版本：源、书库、进度都只存在这台设备上；换设备要重新登录。",
                color = c.fg3,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }

    if (pickTheme) {
        AlertDialog(
            onDismissRequest = { pickTheme = false },
            title = { Text("外观") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onTheme(mode)
                                    pickTheme = false
                                }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                mode.label,
                                color = if (mode == themeMode) c.accent else c.fg,
                                fontSize = 14.sp,
                                fontWeight = if (mode == themeMode) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (mode == themeMode) GlyphView(Glyph.CHECK, c.accent, 16.dp, 2.dp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickTheme = false }) { Text("完成") }
            },
        )
    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("画质漫画") },
            text = {
                Column {
                    Text("版本 $version", color = c.fg, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "双端自用漫画阅读器。本地支持 ZIP / CBZ / RAR / CBR / 7Z / TAR / PDF；" +
                            "在线走 Venera 兼容源，7 个固定源 + 6 个可选源。",
                        color = c.fg2,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { about = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun SettingRow(
    glyph: Glyph,
    title: String,
    desc: String,
    value: String?,
    onClick: () -> Unit,
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(c.chip),
            contentAlignment = Alignment.Center,
        ) { GlyphView(glyph, c.fg2, 17.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.fg, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    color = c.fg3,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(value, color = c.fg3, fontSize = 11.5.sp)
            Spacer(Modifier.width(4.dp))
        }
        GlyphView(Glyph.RIGHT, c.fg3, 13.dp, 1.6.dp)
    }
}

/** 阅读记录：在线打开过的章节，点一下直接回详情页。 */
@Composable
fun HistoryScreen(
    records: List<OnlineRecord>,
    onBack: () -> Unit,
    onOpen: (OnlineRecord) -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubTopBar("阅读记录", onBack, action = {
            if (records.isNotEmpty()) MiniButton("清空", onClear)
        })
        if (records.isEmpty()) {
            EmptyHint(
                title = "还没有在线记录",
                message = "在发现页打开一话之后，这里会记住你读到哪。",
                icon = Glyph.LIST,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                items(records, key = { it.source + "|" + it.comicId }) { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(r) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Box(
                            Modifier
                                .width(38.dp)
                                .height(50.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(coverBrush(r.title.hashCode(), c.dark)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (r.cover.isNotBlank()) {
                                RemoteImage(
                                    url = r.cover,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    r.title.take(2),
                                    color = placeholderInk(c.dark),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.title,
                                color = c.fg,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                buildString {
                                    append(r.epTitle)
                                    if (r.epTotal > 0) append(" · ${r.epIndex + 1}/${r.epTotal}")
                                },
                                color = c.fg3,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(r.sourceName, color = c.fg3, fontSize = 10.sp)
                    }
                    HLine(Modifier.padding(start = 67.dp, end = 18.dp))
                }
            }
        }
    }
}
