package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

@Composable
fun AccountSwitcherRow(session: AccountSession?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = accountAvatarModel(session), contentDescription = null,
            modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(session?.displayName?.ifBlank { session.username }.orEmpty(), fontWeight = FontWeight.SemiBold)
            Text(session?.let { "@${it.username} · ${it.instanceUrl.removePrefix("https://")}" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("切り替え", color = MaterialTheme.colorScheme.primary)
    }
}
