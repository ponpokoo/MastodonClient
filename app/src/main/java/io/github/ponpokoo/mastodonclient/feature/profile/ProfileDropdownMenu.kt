package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.feature.common.AppDropdownMenu
import io.github.ponpokoo.mastodonclient.feature.common.AppDropdownMenuItem
import io.github.ponpokoo.mastodonclient.feature.common.AppMenuDivider

internal data class ProfileMenuAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val startsGroup: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun ProfileDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<ProfileMenuAction>,
) {
    AppDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 240.dp, max = 320.dp),
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                AppMenuDivider(startsGroup = action.startsGroup)
            }
            AppDropdownMenuItem(
                label = action.label,
                leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                onClick = action.onClick,
                enabled = action.enabled,
                destructive = action.destructive,
            )
        }
    }
}
