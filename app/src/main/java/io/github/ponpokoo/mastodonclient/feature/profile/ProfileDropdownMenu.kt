package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.runtime.Composable
import io.github.ponpokoo.mastodonclient.feature.common.AppDropdownMenu
import io.github.ponpokoo.mastodonclient.feature.common.AppDropdownMenuItem
import io.github.ponpokoo.mastodonclient.feature.common.AppMenuDivider

internal data class ProfileMenuAction(
    val label: String,
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
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                AppMenuDivider(startsGroup = action.startsGroup)
            }
            AppDropdownMenuItem(
                label = action.label,
                onClick = action.onClick,
                enabled = action.enabled,
                destructive = action.destructive,
            )
        }
    }
}
