package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val LocalSelectedTabTextWidth = staticCompositionLocalOf<((Float) -> Unit)?> { null }

/** Shares selection styling while each screen keeps its tab sizing and label layout. */
@Composable
internal fun AppTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    divider: @Composable () -> Unit = { HorizontalDivider() },
    tabs: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var textWidth by remember { mutableStateOf(24.dp) }
    val animatedWidth by animateDpAsState(textWidth, label = "app_tab_text_width")
    SecondaryTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedTabIndex).wrapContentSize(Alignment.BottomCenter),
                width = animatedWidth,
                height = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
            )
        },
        divider = divider,
        tabs = {
            // Material's content-width estimate assumes 16 dp padding. Explore uses 4 dp,
            // so measure the rendered label instead of changing the screen's existing padding.
            CompositionLocalProvider(LocalSelectedTabTextWidth provides { width ->
                textWidth = with(density) { width.toDp().coerceAtLeast(24.dp) }
            }) { tabs() }
        },
    )
}

/** Keeps Material's tab color transition and the caller's text or content layout. */
@Composable
internal fun AppTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (text != null) {
        Tab(selected = selected, onClick = onClick, modifier = modifier, text = text,
            selectedContentColor = selectedColor, unselectedContentColor = unselectedColor)
    } else {
        Tab(selected = selected, onClick = onClick, modifier = modifier, content = content,
            selectedContentColor = selectedColor, unselectedContentColor = unselectedColor)
    }
}

@Composable
internal fun AppTabText(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    val reportSelectedWidth = LocalSelectedTabTextWidth.current
    Text(
        text = text,
        modifier = modifier,
        style = style,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = maxLines,
        textAlign = textAlign,
        onTextLayout = { layout ->
            if (selected) {
                val width = (0 until layout.lineCount).maxOfOrNull {
                    layout.getLineRight(it) - layout.getLineLeft(it)
                } ?: 0f
                reportSelectedWidth?.invoke(width.coerceAtMost(layout.size.width.toFloat()))
            }
        },
    )
}
