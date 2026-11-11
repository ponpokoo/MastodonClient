package io.github.ponpokoo.mastodonclient.feature.timeline

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import io.github.ponpokoo.mastodonclient.core.preferences.BottomTabBarStyle
import io.github.ponpokoo.mastodonclient.ui.theme.glassSurface

internal enum class MainDestination(val label: String, val icon: ImageVector) {
    Home("ホーム", Icons.Outlined.Home),
    Explore("探索", Icons.Outlined.Search),
    Notifications("通知", Icons.Outlined.NotificationsNone),
    Profile("プロフィール", Icons.Outlined.PersonOutline),
}

@Composable
internal fun rememberBottomTabBarHazeState(style: BottomTabBarStyle, hidden: Boolean = false): HazeState? =
    if (style == BottomTabBarStyle.LiquidGlass && !hidden && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberHazeState()
    } else {
        null
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MainTabBar(
    style: BottomTabBarStyle,
    selected: MainDestination,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    hidden: Boolean = false,
    unreadNotifications: Int = 0,
    hazeState: HazeState? = null,
    onTabSelected: ((MainDestination) -> Unit)? = null,
) {
    val items: @Composable () -> Unit = {
        MainDestination.entries.forEach { item ->
            val isSelected = selected == item
            val colors = ShortNavigationBarItemDefaults.colors()
            ShortNavigationBarItem(
                // The item already has 6 dp above its indicator; add 6 dp for a 12 dp top gap.
                modifier = Modifier.padding(top = 6.dp).testTag("main_tab_${item.name.lowercase()}"),
                selected = isSelected,
                enabled = !hidden && onTabSelected != null,
                onClick = { onTabSelected?.invoke(item) },
                colors = if (onTabSelected != null) colors else colors.copy(
                    disabledIconColor = if (isSelected) colors.selectedIconColor else colors.unselectedIconColor,
                    disabledTextColor = if (isSelected) colors.selectedTextColor else colors.unselectedTextColor,
                ),
                icon = {
                    if (item == MainDestination.Notifications && unreadNotifications > 0) {
                        BadgedBox(badge = { Badge { Text(unreadNotifications.coerceAtMost(99).toString()) } }) {
                            Icon(item.icon, contentDescription = null)
                        }
                    } else {
                        Icon(item.icon, contentDescription = null)
                    }
                },
                label = {
                    Text(item.label, modifier = Modifier.padding(horizontal = 4.dp), textAlign = TextAlign.Center)
                },
            )
        }
    }
    // Keep the layout when the IME hides the bar from touch and accessibility.
    val visibilityModifier = Modifier.fillMaxWidth().graphicsLayer {
        alpha = if (hidden) 0f else 1f
    }.then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
    if (style == BottomTabBarStyle.FullWidth) {
        Box(visibilityModifier) {
            ShortNavigationBar(
                // Equal-weight items need a loose minimum width. The bar itself fills the maximum.
                modifier = Modifier.testTag("main_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f),
                windowInsets = windowInsets,
                content = items,
            )
        }
    } else {
        val wideWindow = LocalWindowInfo.current.containerDpSize.width >= 600.dp
        val sideMargin = 16.dp
        BoxWithConstraints(
            visibilityModifier.windowInsetsPadding(windowInsets)
                .padding(start = sideMargin, end = sideMargin, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Measure the proportion against the safe width before subtracting the minimum margins.
            val maxBarWidth = if (wideWindow) (maxWidth + sideMargin * 2) * 0.9f else 352.dp
            val shape = RoundedCornerShape(28.dp)
            val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
            val glass = hazeState.takeIf { style == BottomTabBarStyle.LiquidGlass && !hidden }
            Surface(
                modifier = Modifier.widthIn(max = maxBarWidth).testTag("main_navigation_bar"),
                shape = shape,
                color = if (glass == null) surfaceColor.copy(alpha = 0.94f) else Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                ShortNavigationBar(
                    // Keep the end labels inside the rounded surface, including wrapped labels.
                    modifier = Modifier.glassSurface(glass, shape, surfaceColor)
                        .padding(horizontal = 8.dp),
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    content = items,
                )
            }
        }
    }
}
