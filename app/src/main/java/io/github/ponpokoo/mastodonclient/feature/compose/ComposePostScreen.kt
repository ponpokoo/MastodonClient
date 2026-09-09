package io.github.ponpokoo.mastodonclient.feature.compose

import android.provider.OpenableColumns
import android.net.Uri
import java.io.File
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposePostScreen(
    viewModel: ComposePostViewModel,
    isReply: Boolean,
    isEditing: Boolean = false,
    onClose: () -> Unit,
    onPosted: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var accountSheetOpen by remember { mutableStateOf(false) }
    var visibilityMenuOpen by remember { mutableStateOf(false) }
    var emojiSheetOpen by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var cwEnabled by remember(state.spoilerText) { mutableStateOf(state.spoilerText.isNotBlank()) }
    var exitDialogOpen by remember { mutableStateOf(false) }
    val hasContent = state.text.isNotBlank() || state.spoilerText.isNotBlank() ||
        state.attachments.isNotEmpty() || state.pollOptions.any(String::isNotBlank)

    fun requestClose() {
        if (hasContent) viewModel.saveDraftThen(onClose) else onClose()
    }
    BackHandler(onBack = ::requestClose)
    LaunchedEffect(state.posted) { if (state.posted) onPosted() }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val items = uris.mapNotNull { uri ->
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment ?: "attachment"
            val directory = File(context.filesDir, "draft_media").apply { mkdirs() }
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            val target = File(directory, UUID.randomUUID().toString() + extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty())
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: return@mapNotNull null
            DraftAttachment(Uri.fromFile(target).toString(), fileName, mimeType)
        }
        viewModel.addAttachments(items)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "投稿を編集" else if (isReply) "返信" else "新規投稿") },
                navigationIcon = {
                    IconButton(onClick = ::requestClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::saveDraft, enabled = hasContent) {
                        Icon(Icons.Outlined.Drafts, contentDescription = "下書きに保存")
                    }
                    IconButton(onClick = { viewModel.clearComposer(); cwEnabled = false }, enabled = hasContent) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "投稿内容を削除")
                    }
                    Button(
                        onClick = { viewModel.post() },
                        enabled = (state.text.isNotBlank() || state.attachments.isNotEmpty()) && !state.isPosting,
                    ) {
                        if (state.isPosting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isEditing) "更新" else if (isReply) "返信" else "投稿")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).testTag("compose_post"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { accountSheetOpen = true }
                    .padding(vertical = 10.dp).testTag("compose_account_switcher"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = state.selectedSession?.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.selectedSession?.displayName?.ifBlank { state.selectedSession?.username.orEmpty() }.orEmpty(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        state.selectedSession?.let { "@${it.username} · ${it.instanceUrl.removePrefix("https://")}" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("切り替え", color = MaterialTheme.colorScheme.primary)
            }
            Box {
                AssistChip(
                    onClick = { visibilityMenuOpen = true },
                    label = { Text(state.visibility.label()) },
                    leadingIcon = { Icon(state.visibility.icon(), contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("compose_visibility"),
                )
                DropdownMenu(expanded = visibilityMenuOpen, onDismissRequest = { visibilityMenuOpen = false }) {
                    PostVisibility.entries.forEach { visibility ->
                        DropdownMenuItem(
                            text = { Text(visibility.label()) },
                            leadingIcon = { Icon(visibility.icon(), contentDescription = null) },
                            onClick = { visibilityMenuOpen = false; viewModel.setVisibility(visibility) },
                        )
                    }
                }
            }
            if (cwEnabled) {
                OutlinedTextField(
                    value = state.spoilerText,
                    onValueChange = viewModel::onSpoilerChanged,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("内容警告") },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
                placeholder = { Text(if (isReply) "返信を入力" else "いまどうしてる？") },
                supportingText = {
                    Text("${state.configuration.maxCharacters - state.text.length}")
                },
            )

            state.attachments.forEach { attachment ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp,
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = attachment.uri,
                                contentDescription = attachment.description.ifBlank { null },
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(attachment.fileName, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { viewModel.removeAttachment(attachment.uri) }) { Text("削除") }
                        }
                        OutlinedTextField(
                            value = attachment.description,
                            onValueChange = { viewModel.setAttachmentDescription(attachment.uri, it) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            label = { Text("代替テキスト（ALT）") },
                            supportingText = {
                                if (attachment.description.isBlank()) Text("未入力", color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }

            if (state.pollOptions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    state.pollOptions.forEachIndexed { index, option ->
                        OutlinedTextField(
                            value = option,
                            onValueChange = { viewModel.setPollOption(index, it) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            label = { Text("選択肢 ${index + 1}") },
                            singleLine = true,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = viewModel::disablePoll) { Text("投票を削除") }
                        TextButton(onClick = viewModel::addPollOption, enabled = state.pollOptions.size < 4) {
                            Text("選択肢を追加")
                        }
                        Spacer(Modifier.weight(1f))
                        Text("複数選択", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = state.pollMultiple, onCheckedChange = viewModel::setPollMultiple)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) },
                    enabled = state.pollOptions.isEmpty() && state.attachments.size < state.configuration.maxMediaAttachments,
                ) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "画像または動画") }
                IconButton(onClick = { if (state.pollOptions.isEmpty()) viewModel.enablePoll() else viewModel.disablePoll() }, enabled = state.attachments.isEmpty()) {
                    Icon(Icons.Outlined.Poll, contentDescription = "投票")
                }
                IconButton(onClick = { emojiSheetOpen = true }) {
                    Icon(Icons.Outlined.SentimentSatisfiedAlt, contentDescription = "絵文字")
                }
                IconButton(onClick = {
                    cwEnabled = !cwEnabled
                    if (!cwEnabled) viewModel.onSpoilerChanged("")
                }) { Icon(Icons.Outlined.WarningAmber, contentDescription = "内容警告") }
                Box {
                    IconButton(onClick = { languageMenuOpen = true }) {
                        Icon(Icons.Outlined.Language, contentDescription = "投稿言語")
                    }
                    DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                        listOf("ja" to "日本語", "en" to "English", "de" to "Deutsch", "fr" to "Français").forEach { (code, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                languageMenuOpen = false
                                viewModel.setLanguage(code)
                            })
                        }
                    }
                }
            }
            Text(
                "投稿言語: ${state.language}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.errorMessage?.let {
                Text(it, modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (accountSheetOpen) {
        ModalBottomSheet(onDismissRequest = { accountSheetOpen = false }) {
            Text("投稿元を切り替える", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            state.sessions.forEach { session ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        accountSheetOpen = false
                        viewModel.switchPostingAccount(session.sessionId)
                    }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(session.avatarUrl, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.displayName.ifBlank { session.username })
                        Text("@${session.username} · ${session.instanceUrl.removePrefix("https://")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (session.sessionId == state.selectedSession?.sessionId) Text("選択中")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (emojiSheetOpen) {
        ModalBottomSheet(onDismissRequest = { emojiSheetOpen = false }) {
            Text("絵文字", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.customEmojis.take(80).forEach { emoji ->
                    IconButton(onClick = { viewModel.insertEmoji(emoji.shortcode); emojiSheetOpen = false }) {
                        AsyncImage(emoji.url, emoji.shortcode, Modifier.size(30.dp))
                    }
                }
            }
            if (state.customEmojis.isEmpty()) Text("このサーバーのカスタム絵文字を取得できませんでした", Modifier.padding(20.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.altReminderVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAltReminder,
            title = { Text("代替テキストが未入力です") },
            text = { Text("説明のないメディアがあります。このまま投稿しますか？") },
            confirmButton = { TextButton(onClick = viewModel::postIgnoringMissingAlt) { Text("このまま投稿") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAltReminder) { Text("編集に戻る") } },
        )
    }

    if (exitDialogOpen) {
        AlertDialog(
            onDismissRequest = { exitDialogOpen = false },
            title = { Text("投稿を閉じますか？") },
            text = { Text("下書きの自動保存はオフです。") },
            confirmButton = {
                TextButton(onClick = {
                    exitDialogOpen = false
                    viewModel.saveDraftThen(onClose)
                }) { Text("下書きを保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        exitDialogOpen = false
                        viewModel.discardDraftThen(onClose)
                    }) { Text("破棄") }
                    TextButton(onClick = { exitDialogOpen = false }) { Text("編集を続ける") }
                }
            },
        )
    }
}

private fun PostVisibility.label() = when (this) {
    PostVisibility.Public -> "公開"
    PostVisibility.Unlisted -> "ひかえめな公開"
    PostVisibility.FollowersOnly -> "フォロワー限定"
    PostVisibility.Direct -> "指定した相手のみ"
}

private fun PostVisibility.icon(): ImageVector = when (this) {
    PostVisibility.Public -> Icons.Outlined.Public
    PostVisibility.Unlisted -> Icons.Outlined.Group
    PostVisibility.FollowersOnly -> Icons.Outlined.Lock
    PostVisibility.Direct -> Icons.Outlined.AlternateEmail
}
