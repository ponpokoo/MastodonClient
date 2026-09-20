package io.github.ponpokoo.mastodonclient.feature.compose

import android.net.Uri
import android.text.format.Formatter
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.core.preferences.ComposerAction
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.AccountSwitchDialog
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistoryLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistorySaver
import io.github.ponpokoo.mastodonclient.feature.timeline.ReactionPickerSheet
import io.github.ponpokoo.mastodonclient.domain.model.DraftAttachment
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var accountDialogOpen by remember { mutableStateOf(false) }
    var visibilityMenuOpen by remember { mutableStateOf(false) }
    var emojiSheetOpen by remember { mutableStateOf(false) }
    var focusAfterEmoji by remember { mutableStateOf(false) }
    var draftSheetOpen by remember { mutableStateOf(false) }
    var mentionSheetOpen by remember { mutableStateOf(false) }
    var altEditingUri by remember { mutableStateOf<String?>(null) }
    var altEditorText by remember { mutableStateOf("") }
    var draftToDelete by remember { mutableStateOf<io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft?>(null) }
    var cwEnabled by remember(state.spoilerText) { mutableStateOf(state.spoilerText.isNotBlank()) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val contentScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    var screenHeightPx by remember { mutableStateOf(0) }
    val dismissThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }
    val hasContent = state.text.isNotBlank() || state.spoilerText.isNotBlank() ||
        state.attachments.isNotEmpty() || state.pollOptions.any(String::isNotBlank)

    fun requestClose() {
        keyboardController?.hide()
        viewModel.retainInputThen(onClose)
    }
    fun settleDismissGesture() {
        scope.launch {
            if (dismissOffset.value >= dismissThresholdPx) {
                keyboardController?.hide()
                dismissOffset.animateTo(
                    screenHeightPx.toFloat().coerceAtLeast(dismissOffset.value),
                    animationSpec = tween(180),
                )
                viewModel.retainInputThen(onClose)
            } else {
                dismissOffset.animateTo(0f, animationSpec = tween(140))
            }
        }
    }
    val dismissDragState = rememberDraggableState { delta ->
        if (delta > 0f || dismissOffset.value > 0f) {
            scope.launch { dismissOffset.snapTo((dismissOffset.value + delta).coerceAtLeast(0f)) }
        }
    }
    BackHandler(onBack = ::requestClose)
    LaunchedEffect(state.posted) { if (state.posted) onPosted() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    LaunchedEffect(emojiSheetOpen, focusAfterEmoji) {
        if (!emojiSheetOpen && focusAfterEmoji) {
            delay(16)
            focusRequester.requestFocus()
            keyboardController?.show()
            focusAfterEmoji = false
        }
    }
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            val showJob = launch { snackbarHostState.showSnackbar(it) }
            delay(1_500)
            snackbarHostState.currentSnackbarData?.dismiss()
            showJob.join()
            viewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(state.text) {
        if (textFieldValue.text != state.text) {
            textFieldValue = TextFieldValue(state.text, selection = TextRange(state.text.length))
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        viewModel.importMedia(uris.map(Uri::toString))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().onSizeChanged { screenHeightPx = it.height }
            .graphicsLayer { translationY = dismissOffset.value },
        topBar = {
            TopAppBar(
                modifier = Modifier.draggable(
                    state = dismissDragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { settleDismissGesture() },
                ),
                title = { Text(if (isEditing) "投稿を編集" else if (isReply) "返信" else "新規投稿") },
                navigationIcon = {
                    IconButton(onClick = ::requestClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                    }
                },
                actions = {
                    if (!isEditing) {
                        TextButton(
                            onClick = {
                                keyboardController?.hide()
                                draftSheetOpen = true
                            },
                            modifier = Modifier.testTag("compose_drafts"),
                        ) { Text("下書き") }
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.imePadding().draggable(
                    state = dismissDragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { settleDismissGesture() },
                ),
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                            .testTag("compose_action_bar"),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.preferences.composerActionOrder
                            .filterNot { it in state.preferences.hiddenComposerActions }
                            .forEach { action ->
                            when (action) {
                                ComposerAction.Media -> ComposerToolbarButton(
                                    icon = Icons.Outlined.AddPhotoAlternate,
                                    contentDescription = "画像または動画",
                                    enabled = !state.isImportingMedia && !state.isPosting && !state.isLoading && state.pollOptions.isEmpty() &&
                                        !state.quotingNative && state.attachments.size < state.configuration.maxMediaAttachments,
                                ) { mediaPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                ) }
                                ComposerAction.Poll -> ComposerToolbarButton(
                                    icon = Icons.Outlined.Poll,
                                    contentDescription = if (state.pollOptions.isEmpty()) "投票を追加" else "投票を解除",
                                    enabled = !state.quotingNative && state.attachments.isEmpty() && !state.isImportingMedia,
                                ) {
                                    if (state.pollOptions.isEmpty()) viewModel.enablePoll() else viewModel.disablePoll()
                                }
                                ComposerAction.Emoji -> ComposerToolbarButton(
                                    Icons.Outlined.SentimentSatisfiedAlt,
                                    "絵文字",
                                ) { emojiSheetOpen = true }
                                ComposerAction.ContentWarning -> ComposerToolbarButton(
                                    Icons.Outlined.WarningAmber,
                                    if (cwEnabled) "内容警告を解除" else "内容警告を追加",
                                ) {
                                    cwEnabled = !cwEnabled
                                    if (!cwEnabled) viewModel.onSpoilerChanged("")
                                }
                                ComposerAction.Mention -> ComposerToolbarButton(
                                    Icons.Outlined.AlternateEmail,
                                    "メンション",
                                ) {
                                    viewModel.loadMentionCandidates()
                                    keyboardController?.hide()
                                    mentionSheetOpen = true
                                }
                                ComposerAction.SaveDraft -> ComposerToolbarButton(
                                    Icons.Outlined.Drafts,
                                    "下書きに保存",
                                    enabled = hasContent && !isEditing && !state.isImportingMedia && !state.isLoading,
                                ) { viewModel.saveDraft() }
                                ComposerAction.DeleteDraft -> ComposerToolbarButton(
                                    Icons.Outlined.DeleteOutline,
                                    "本文をクリア",
                                    enabled = state.text.isNotEmpty() && !state.isPosting && !state.isImportingMedia,
                                ) { viewModel.onTextChanged("") }
                            }
                        }
                    }
                    Button(
                        onClick = viewModel::post,
                        enabled = (state.text.isNotBlank() || state.attachments.isNotEmpty()) &&
                            !state.isPosting && !state.isImportingMedia && !state.isLoading,
                        modifier = Modifier.height(44.dp).padding(start = 4.dp).testTag("compose_submit"),
                    ) {
                        if (state.isPosting || state.isImportingMedia) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isEditing) "更新" else if (isReply) "返信" else if (state.quoteStatusId != null) "引用" else "投稿")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(contentScrollState)
                .padding(horizontal = 16.dp).testTag("compose_post"),
        ) {
            state.replyToStatus?.let { reply ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        AsyncImage(
                            model = reply.author.avatarUrl,
                            contentDescription = "${reply.author.displayName}のアイコン",
                            modifier = Modifier.size(38.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${reply.author.displayName}  @${reply.author.accountName} への返信",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                HtmlCompat.fromHtml(reply.contentHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                                    .toString().trim(),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            state.quoteToStatus?.let { quote ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        AsyncImage(
                            model = quote.author.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.quotingNative) "${quote.author.displayName} の投稿を引用"
                                else "${quote.author.displayName} の投稿URLを引用",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                HtmlCompat.fromHtml(quote.contentHtml, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim(),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    keyboardController?.hide()
                    accountDialogOpen = true
                }
                    .padding(vertical = 10.dp).testTag("compose_account_switcher"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = state.selectedSession?.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
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
            if (state.attachments.isNotEmpty()) {
                Text(
                    "添付メディア ${state.attachments.size}件",
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.attachments.chunked(2).forEach { rowAttachments ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowAttachments.forEach { attachment ->
                        ComposerAttachmentTile(
                            attachment = attachment,
                            modifier = Modifier.weight(1f),
                            onRemove = { viewModel.removeAttachment(attachment.uri) },
                            onEditAlt = {
                                altEditingUri = attachment.uri
                                altEditorText = attachment.description
                            },
                        )
                    }
                    if (rowAttachments.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            if (isReply) "返信を入力" else "いまどうしてる？",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { value ->
                            if (value.text.length <= state.configuration.maxCharacters) {
                                textFieldValue = value
                                viewModel.onTextChanged(value.text)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                            .focusRequester(focusRequester).testTag("compose_text"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    text = "${state.configuration.maxCharacters - state.text.length}文字",
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            state.errorMessage?.let {
                Text(it, modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (accountDialogOpen) {
        AccountSwitchDialog(
            title = "投稿元を切り替える",
            sessions = state.sessions,
            selectedSessionId = state.selectedSession?.sessionId,
            onSelected = { sessionId ->
                accountDialogOpen = false
                viewModel.switchPostingAccount(sessionId)
            },
            onDismiss = { accountDialogOpen = false },
        )
    }

    if (draftSheetOpen) {
        Dialog(onDismissRequest = { draftSheetOpen = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("下書き", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { draftSheetOpen = false }) {
                            Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                        }
                    }
                    Text(
                        "タップで呼び出し、長押しで削除",
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.drafts.isEmpty()) {
                        Text("保存された下書きはありません", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                        items(state.drafts, key = { it.key }) { draft ->
                        Column(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    draftSheetOpen = false
                                    cwEnabled = draft.spoilerText.isNotBlank()
                                    viewModel.restoreDraft(draft)
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                                onLongClick = { draftToDelete = draft },
                            ).padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(
                                draft.text.ifBlank { draft.spoilerText.ifBlank { "メディアまたはアンケートの下書き" } },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(draft.visibility.label())
                                    if (draft.replyToId != null) append(" · 返信")
                                    if (draft.attachmentUris.isNotEmpty()) append(" · メディア${draft.attachmentUris.size}件")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    }

    if (mentionSheetOpen) {
        Dialog(onDismissRequest = { mentionSheetOpen = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("メンションするアカウント", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { mentionSheetOpen = false }) {
                            Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                        }
                    }
                    when {
                        state.isLoadingMentions -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.mentionCandidates.isEmpty() -> Text(
                            "フォロー中のアカウントはありません",
                            Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                            items(state.mentionCandidates, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.insertMention(account)
                                mentionSheetOpen = false
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                account.avatarUrl,
                                null,
                                Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                CustomEmojiText(account.displayName, account.customEmojis, fontWeight = FontWeight.SemiBold)
                                Text("@${account.accountName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }

    if (emojiSheetOpen) {
        CompositionLocalProvider(
            LocalReactionHistoryLoader provides { viewModel.loadEmojiHistory() },
            LocalReactionHistorySaver provides { viewModel.saveEmojiHistory(it) },
        ) {
            ReactionPickerSheet(
                canUndo = false,
                title = "絵文字",
                customEmojisOverride = state.customEmojis,
                onDismiss = { emojiSheetOpen = false },
                onSelected = { selected ->
                    emojiSheetOpen = false
                    if (selected != null) {
                        val insertion = if (state.customEmojis.any { it.shortcode == selected }) ":$selected:" else selected
                        val selection = textFieldValue.selection
                        val newText = textFieldValue.text.replaceRange(selection.min, selection.max, insertion)
                        if (newText.length <= state.configuration.maxCharacters) {
                            textFieldValue = TextFieldValue(newText, TextRange(selection.min + insertion.length))
                            viewModel.onTextChanged(newText)
                            viewModel.recordUsedEmoji(selected)
                        }
                        focusAfterEmoji = true
                    }
                }
            )
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

    draftToDelete?.let { draft ->
        AlertDialog(
            onDismissRequest = { draftToDelete = null },
            title = { Text("下書きを削除") },
            text = { Text("この下書きを削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDraft(draft)
                    draftToDelete = null
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { draftToDelete = null }) { Text("キャンセル") } },
        )
    }

    altEditingUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { altEditingUri = null },
            title = { Text("代替テキスト（ALT）") },
            text = {
                OutlinedTextField(
                    value = altEditorText,
                    onValueChange = { altEditorText = it.take(state.configuration.mediaDescriptionLimit) },
                    label = { Text("画像や動画の内容") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setAttachmentDescription(uri, altEditorText)
                    altEditingUri = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { altEditingUri = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ComposerAttachmentTile(
    attachment: DraftAttachment,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    onEditAlt: () -> Unit,
) {
    val context = LocalContext.current
    val bytes = remember(attachment.uri) { Uri.parse(attachment.uri).path?.let(::File)?.length() ?: 0L }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(6.dp)) {
            Box {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.description.ifBlank { "添付メディア" },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "添付を削除", tint = Color.White)
                }
            }
            Text(attachment.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            if (bytes > 0L) Text(
                Formatter.formatShortFileSize(context, bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (attachment.description.isNotBlank()) Text(
                attachment.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onEditAlt, modifier = Modifier.fillMaxWidth()) {
                Text(if (attachment.description.isBlank()) "ALTを追加" else "ALTを編集", maxLines = 1)
            }
        }
    }
}

@Composable
private fun ComposerToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
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
