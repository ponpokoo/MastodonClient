package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun TimelineGapRow(
    loading: Boolean,
    unavailable: Boolean,
    error: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.primary.copy(alpha = 0.08f))
            .then(if (!unavailable || loading) Modifier.clickable(
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            ) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("読み込み中…", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            unavailable -> Text(
                "この区間の投稿を取得できません",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            else -> {
                if (error != null) Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (error != null) "再試行" else "間の投稿を読み込む",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
