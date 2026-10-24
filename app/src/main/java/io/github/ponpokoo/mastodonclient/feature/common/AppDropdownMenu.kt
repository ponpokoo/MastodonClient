package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object AppMenuDefaults {
    val shape = RoundedCornerShape(16.dp)
    val shadowElevation = 8.dp
    val containerColor
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
internal fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(max = 320.dp),
        shape = AppMenuDefaults.shape,
        containerColor = AppMenuDefaults.containerColor,
        shadowElevation = AppMenuDefaults.shadowElevation,
        content = content,
    )
}

@Composable
internal fun AppMenuDivider(startsGroup: Boolean = false) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = if (startsGroup) 2.dp else 0.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun AppDropdownMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    isSelected: Boolean? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = if (destructive) colors.error else colors.onSurface
    DropdownMenuItem(
        text = {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp))
        },
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).then(
            if (isSelected == null) Modifier else Modifier.semantics { selected = isSelected },
        ),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = MenuDefaults.itemColors(textColor = foreground, leadingIconColor = foreground),
    )
}
