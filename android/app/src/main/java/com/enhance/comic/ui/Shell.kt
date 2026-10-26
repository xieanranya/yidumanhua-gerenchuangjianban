package com.enhance.comic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 四个 Tab 的定义。顺序就是底部栏顺序。 */
internal enum class AppTab(val label: String, val glyph: Glyph) {
    HOME("首页", Glyph.HOME),
    DISCOVER("发现", Glyph.DISCOVER),
    SHELF("书架", Glyph.SHELF),
    ME("我的", Glyph.USER),
}

private val BarHeight = 54.dp

@Composable
internal fun AppBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Column(modifier.fillMaxWidth().background(c.bg)) {
        HLine()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .navigationBarsPadding(),
        ) {
            AppTab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlyphView(
                        glyph = tab.glyph,
                        tint = if (on) c.fg else c.fg3,
                        size = 21.dp,
                        stroke = if (on) 1.9.dp else 1.5.dp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        color = if (on) c.fg else c.fg3,
                        fontSize = 9.5.sp,
                    )
                }
            }
        }
    }
}

/**
 * 四 Tab 的壳：内容区高度已经扣掉底部栏（含手势条），
 * 所以每个页面自己滚动到底时不会被栏体压住。
 */
@Composable
internal fun AppFrame(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalColors.current
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().padding(bottom = BarHeight + inset)) { content() }
        AppBottomBar(selected, onSelect, Modifier.align(Alignment.BottomCenter))
    }
}

/** 二级页面（源管理、详情…）用的顶栏：返回 + 标题 + 右侧动作，不带阴影和圆角。 */
@Composable
internal fun SubTopBar(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(c.bg)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.BACK, c.fg, 20.dp) }
        Text(
            text = title,
            color = c.fg,
            fontSize = 17.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
        action?.invoke()
        Spacer(Modifier.size(6.dp))
    }
}
