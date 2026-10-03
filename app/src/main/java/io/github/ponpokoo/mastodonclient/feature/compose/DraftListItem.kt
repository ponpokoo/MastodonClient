package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun DraftListItem(
    draft: ComposeDraft,
    session: AccountSession?,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(draft.key) { mutableStateOf(false) }
    val accountId = session?.let {
        "@${it.username}@${java.net.URI(it.instanceUrl).host}"
    } ?: "アカウント情報を取得できません"
    val attachments = draft.attachmentUris.groupingBy { uri ->
        when (draft.attachmentMimeTypes[uri]?.substringBefore('/')) {
            "image" -> "画像"
            "video" -> "動画"
            "audio" -> "音声"
            else -> "ファイル"
        }
    }.eachCount().map { (type, count) -> "$type ${count}${if (type == "画像") "枚" else "件"}" }
    val details = attachments + if (draft.pollOptions.isNotEmpty()) listOf("投票") else emptyList()
    val savedAt = Instant.ofEpochMilli(draft.updatedAtEpochMillis).atZone(ZoneId.systemDefault())
    val pattern = if (savedAt.year == java.time.Year.now().value) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
    Column(
        Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onRestore, onLongClick = onDelete)
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Person, contentDescription = null)
                AsyncImage(session?.avatarUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(10.dp))
            Text(accountId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "下書きのメニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("削除") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        Text(
            draft.text.ifBlank { draft.spoilerText.ifBlank {
                details.joinToString("・").ifBlank { "本文なし" } + "の下書き"
            } },
            Modifier.padding(top = 4.dp, bottom = 10.dp, end = 8.dp),
            style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        FlowRow(Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(savedAt.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPAN)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (details.isNotEmpty()) Text(details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
