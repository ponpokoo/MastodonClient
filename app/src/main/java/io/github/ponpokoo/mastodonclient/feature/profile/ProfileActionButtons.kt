package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship

internal fun followActionLabel(relationship: AccountRelationship?, locked: Boolean): String = when {
    relationship == null -> "確認中"
    relationship.requested -> "リクエスト中"
    relationship.following -> "フォロー中"
    locked -> "リクエスト"
    else -> "フォロー"
}

@Composable
internal fun profileActionBorderColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.surface.luminance() > 0.5f) colors.onSurface
        else colors.outline
}

@Composable
internal fun FollowActionButton(
    relationship: AccountRelationship?,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val label = followActionLabel(relationship, locked)
    val icon = when {
        relationship == null || relationship.requested -> Icons.Outlined.Schedule
        relationship.following -> Icons.Outlined.HowToReg
        else -> Icons.Outlined.PersonAdd
    }
    val canClick = enabled && relationship != null && !relationship.requested
    val buttonModifier = modifier.testTag("follow_action")
    if (relationship?.following == true || relationship?.requested == true) {
        OutlinedButton(onClick, buttonModifier, enabled = canClick,
            border = BorderStroke(1.dp, profileActionBorderColor()),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { ActionButtonLabel(icon, label) }
    } else {
        Button(onClick, buttonModifier, enabled = canClick) { ActionButtonLabel(icon, label) }
    }
}

@Composable
internal fun EditProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, modifier.testTag("edit_profile_action"),
        border = BorderStroke(1.dp, profileActionBorderColor()),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Text("プロフィールを編集")
    }
}

@Composable
private fun RowScope.ActionButtonLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(label)
}
