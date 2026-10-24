package com.enhance.comic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enhance.comic.model.EnhanceSettings
import com.enhance.comic.model.ReaderPrefs
import com.enhance.comic.model.ReadingMode
import com.enhance.comic.model.ScaleFilter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    prefs: ReaderPrefs,
    enhance: EnhanceSettings,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onEnhanceChange: (EnhanceSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle("阅读模式") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingMode.entries.forEach { item ->
                        FilterChip(
                            selected = prefs.readingMode == item,
                            onClick = { onPrefsChange(prefs.copy(readingMode = item)) },
                            label = {
                                Text(
                                    when (item) {
                                        ReadingMode.PAGED -> "翻页"
                                        ReadingMode.VERTICAL -> "纵向滚动"
                                        ReadingMode.HORIZONTAL -> "横向滚动"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            item {
                ToggleRow("从右向左（日漫）", prefs.rightToLeft) {
                    onPrefsChange(prefs.copy(rightToLeft = it))
                }
            }
            item {
                ToggleRow("宽页自动拆分双页", prefs.splitWidePages) {
                    onPrefsChange(prefs.copy(splitWidePages = it))
                }
            }
            item {
                ToggleRow("双页并排（横屏）", prefs.doublePage) {
                    onPrefsChange(prefs.copy(doublePage = it))
                }
            }
            item {
                ToggleRow("自动裁掉白边", prefs.autoCropWhiteBorders) {
                    onPrefsChange(prefs.copy(autoCropWhiteBorders = it))
                }
            }
            item {
                ToggleRow("音量键翻页", prefs.volumeKeyPaging) {
                    onPrefsChange(prefs.copy(volumeKeyPaging = it))
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            item { SectionTitle("缩放算法") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScaleFilter.entries.forEach { item ->
                        FilterChip(
                            selected = enhance.scaleFilter == item,
                            onClick = { onEnhanceChange(enhance.copy(scaleFilter = item)) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            item { SectionTitle("画质增强") }
            item {
                ToggleRow("锐化（边缘自适应）", enhance.sharpen) {
                    onEnhanceChange(enhance.copy(sharpen = it))
                }
            }
            if (enhance.sharpen) {
                item {
                    IntSlider("锐化强度", enhance.sharpenAmount, 5..100) {
                        onEnhanceChange(enhance.copy(sharpenAmount = it))
                    }
                }
            }
            item {
                ToggleRow("降噪平滑（Kuwahara）", enhance.denoise) {
                    onEnhanceChange(enhance.copy(denoise = it))
                }
            }
            if (enhance.denoise) {
                item {
                    IntSlider("降噪半径", enhance.denoiseRadius, 1..3) {
                        onEnhanceChange(enhance.copy(denoiseRadius = it))
                    }
                }
                item {
                    IntSlider("降噪强度", enhance.denoiseBlend, 5..100) {
                        onEnhanceChange(enhance.copy(denoiseBlend = it))
                    }
                }
            }
            item {
                ToggleRow("对比度增强", enhance.contrastPass) {
                    onEnhanceChange(enhance.copy(contrastPass = it))
                }
            }
            if (enhance.contrastPass) {
                item {
                    IntSlider("增强强度", enhance.contrastBlend, 5..100) {
                        onEnhanceChange(enhance.copy(contrastBlend = it))
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            item { SectionTitle("画面校正") }
            item {
                IntSlider("亮度", enhance.brightness, 30..180) {
                    onEnhanceChange(enhance.copy(brightness = it))
                }
            }
            item {
                IntSlider("对比度", enhance.contrastLevel, 30..180) {
                    onEnhanceChange(enhance.copy(contrastLevel = it))
                }
            }
            item {
                IntSlider("伽马", enhance.gamma, 40..220) {
                    onEnhanceChange(enhance.copy(gamma = it))
                }
            }
            item {
                TextButton(onClick = { onEnhanceChange(EnhanceSettings()) }) {
                    Text("重置全部增强参数")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value", style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
