package io.github.ponpokoo.mastodonclient.feature.status

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus



@Composable
internal fun SocialStatus(
    status: TimelineStatus,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    actions: StatusActions,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    preferences: StatusDisplayPreferences,
    onMoreClick: (TimelineStatus) -> Unit,
    isPinned: Boolean = false,
) {
    if (isPinned) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 64.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text("固定された投稿", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    StatusCard(
        status = status,
        onStatusClick = onStatusClick,
        onAuthorClick = onAccountClick,
        onMediaClick = onMediaClick,
        onOpenLink = onOpenLink,
        actions = actions,
        onMoreClick = onMoreClick,
        onUnavailableAction = {},
        preferences = preferences,
        useTimelineImagePreviews = true,
    )
    HorizontalDivider()
}
