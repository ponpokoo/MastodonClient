package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel

@Composable
internal fun RowScope.RegisteredAccountRow(account: AccountSession, active: Boolean) {
    Spacer(Modifier.width(8.dp))
    AsyncImage(model = accountAvatarModel(account), contentDescription = null,
        modifier = Modifier.size(46.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(account.displayName.ifBlank { account.username }, style = MaterialTheme.typography.titleSmall)
        Text("@${account.username} · ${account.instanceUrl.removePrefix("https://")}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (active) Text("現在のアカウント", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}
