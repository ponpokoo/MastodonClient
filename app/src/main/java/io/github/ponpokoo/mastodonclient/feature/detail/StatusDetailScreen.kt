package io.github.ponpokoo.mastodonclient.feature.detail

import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmation
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmationAction
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.feature.status.StatusMenuDialog
import io.github.ponpokoo.mastodonclient.feature.status.ConfirmStatusActionDialog
import io.github.ponpokoo.mastodonclient.feature.status.StatusReportDialog
import io.github.ponpokoo.mastodonclient.feature.status.ListPickerSheet
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusDetailScreen(
    viewModel: StatusDetailViewModel,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onReply: (String) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onEditStatus: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onStatusClick: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val moderationMenu by viewModel.moderationMenuState.collectAsStateWithLifecycle()
    val status = state.detail?.status
    val conversationListState = rememberConversationListState(
        status?.statusId,
        state.detail?.ancestors?.size ?: 0,
        !state.isLoading && state.errorMessage == null,
    )
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var menuStatus by remember { mutableStateOf<TimelineStatus?>(null) }
    var confirmation by remember { mutableStateOf<StatusConfirmation?>(null) }
    var reportStatus by remember { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember { mutableStateOf<TimelineStatus?>(null) }

    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onBack() }
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (status == null) Text("投稿") else CustomEmojiText(
                        text = "${status.author.displayName}さんの投稿",
                        emojis = status.author.customEmojis,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && status == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            status == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.errorMessage ?: "投稿を表示できませんでした")
                TextButton(onClick = viewModel::retry) { Text("再試行") }
            }
            else -> LazyColumn(
                state = conversationListState,
                modifier = Modifier.fillMaxSize().padding(padding).testTag("status_detail"),
            ) {
                state.detail?.ancestors?.let { ancestors ->
                    if (ancestors.isNotEmpty()) {
                        item { Text("この会話の前の投稿", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                        items(ancestors, key = { "ancestor-${it.timelineId}" }) { ancestor ->
                            StatusCard(
                                status = ancestor,
                                onStatusClick = onStatusClick,
                                onAuthorClick = onAccountClick,
                                onMediaClick = onMediaClick,
                                onOpenLink = onOpenLink,
                                onReply = { onReply(ancestor.statusId) },
                                onUnavailableAction = {},
                                displayPreferences = preferences.timelineDisplay,
                                gifAutoplay = preferences.gifAutoplay,
                                videoAutoplay = preferences.videoAutoplay,
                            )
                            HorizontalDivider()
                        }
                    }
                }
                item(key = "selected-${status.statusId}") {
                    StatusCard(
                        status = status,
                        onStatusClick = null,
                        onAuthorClick = onAccountClick,
                        onMediaClick = onMediaClick,
                        onOpenLink = onOpenLink,
                        onReply = { onReply(status.statusId) },
                        onBoost = viewModel::toggleReblog,
                        onQuote = { mode -> onQuote(status, mode) },
                        onMoreClick = { menuStatus = it },
                        onFavourite = viewModel::toggleFavourite,
                        onVotePoll = viewModel::votePoll,
                        onReact = viewModel::setReaction,
                        onReactionLongPress = viewModel::showReaction,
                        onUnavailableAction = {},
                        displayPreferences = preferences.timelineDisplay,
                        gifAutoplay = preferences.gifAutoplay,
                        videoAutoplay = preferences.videoAutoplay,
                        fullWidthContent = true,
                        afterActions = {
                            StatusDetailMetadata(
                                status = status,
                                onBoosters = viewModel::showBoosters,
                                onFavourites = viewModel::showFavourites,
                            )
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                state.detail?.descendants?.let { replies ->
                    if (replies.isNotEmpty()) {
                        item { Text("返信", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium) }
                        items(replies, key = { it.timelineId }) { reply ->
                            StatusCard(
                                reply,
                                onStatusClick = onStatusClick,
                                onAuthorClick = onAccountClick,
                                onMediaClick = onMediaClick,
                                onOpenLink = onOpenLink,
                                onReply = { onReply(reply.statusId) },
                                onUnavailableAction = {},
                                displayPreferences = preferences.timelineDisplay,
                                gifAutoplay = preferences.gifAutoplay,
                                videoAutoplay = preferences.videoAutoplay,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(menuStatus?.author?.id) {
        menuStatus?.author?.id?.let { viewModel.loadModerationMenu(it) }
    }
    menuStatus?.let { selected ->
        StatusMenuDialog(
            status = selected,
            moderation = moderationMenu.takeIf { it.accountId == selected.author.id },
            onRetryRelationship = { viewModel.loadModerationMenu(selected.author.id) },
            isOwnStatus = selected.author.id == state.currentAccountId,
            onDismiss = { menuStatus = null },
            onOpenBrowser = {
                menuStatus = null
                selected.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
            },
            onPin = { menuStatus = null; viewModel.setPinned(selected) },
            onEdit = { menuStatus = null; onEditStatus(selected.statusId) },
            onDelete = { menuStatus = null; confirmation = StatusConfirmation(StatusConfirmationAction.Delete, selected) },
            onAddToList = { menuStatus = null; listStatus = selected; viewModel.loadLists() },
            onUnfollow = { menuStatus = null; confirmation = StatusConfirmation(StatusConfirmationAction.Unfollow, selected) },
            onMute = { menuStatus = null; confirmation = StatusConfirmation((if (moderationMenu.relationship?.muting == true) StatusConfirmationAction.Unmute else StatusConfirmationAction.Mute), selected) },
            onBlock = { menuStatus = null; confirmation = StatusConfirmation((if (moderationMenu.relationship?.blocking == true) StatusConfirmationAction.Unblock else StatusConfirmationAction.Block), selected) },
            onReport = { menuStatus = null; reportStatus = selected },
        )
    }
    confirmation?.let { (action, selected) ->
        ConfirmStatusActionDialog(
            action = action,
            status = selected,
            onDismiss = { confirmation = null },
            onConfirm = {
                when (action) {
                    StatusConfirmationAction.Delete -> viewModel.deleteStatus(selected)
                    StatusConfirmationAction.Unfollow -> viewModel.unfollow(selected)
                    StatusConfirmationAction.Mute -> viewModel.mute(selected)
                    StatusConfirmationAction.Unmute -> viewModel.mute(selected, false)
                    StatusConfirmationAction.Block -> viewModel.block(selected)
                    StatusConfirmationAction.Unblock -> viewModel.block(selected, false)
                }
            },
        )
    }
    reportStatus?.let { selected ->
        StatusReportDialog(
            status = selected,
            onDismiss = { reportStatus = null },
            onSubmit = { comment -> viewModel.report(selected, comment); reportStatus = null },
        )
    }
    listStatus?.let { selected ->
        ListPickerSheet(
            lists = state.lists,
            loading = state.isLoadingLists,
            onDismiss = { listStatus = null },
            onSelected = { listId -> viewModel.addToList(selected, listId); listStatus = null },
        )
    }

    state.accountListTitle?.let { accountListTitle ->
        StatusAccountsDialog(
            title = accountListTitle,
            accounts = state.accounts,
            isLoading = state.isLoadingAccounts,
            errorMessage = state.accountListError,
            onDismiss = viewModel::dismissAccounts,
            onAccountClick = { accountId ->
                viewModel.dismissAccounts()
                onAccountClick(accountId)
            },
        )
    }
}

/** Position once after the complete conversation arrives; preserve later browsing position. */
@Composable
internal fun rememberConversationListState(statusId: String?, ancestorCount: Int, ready: Boolean): LazyListState {
    val listState = rememberLazyListState()
    var positionedStatusId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(listState, statusId) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) positionedStatusId = statusId
        }
    }
    LaunchedEffect(statusId, ready, positionedStatusId) {
        if (statusId == null || !ready || positionedStatusId == statusId) return@LaunchedEffect
        val index = ancestorCount + if (ancestorCount > 0) 1 else 0
        val key = "selected-$statusId"
        // First measure the selected row, including its media and metadata.
        listState.scrollToItem(index)
        val (item, layout) = snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.firstOrNull { it.key == key }?.let { it to layout }
        }.first { it != null }!!
        val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
        // A row taller than the viewport starts at the top so its beginning stays readable.
        val desiredTop = layout.viewportStartOffset + ((viewportHeight - item.size) / 2).coerceAtLeast(0)
        listState.scrollBy((item.offset - desiredTop).toFloat())
        positionedStatusId = statusId
    }
    return listState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusDetailMetadata(
    status: io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus,
    onBoosters: () -> Unit,
    onFavourites: () -> Unit,
) {
    Text(
        buildString {
            append(exactDate(status.createdAt))
            status.applicationName?.let { append("  ·  ").append(it) }
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBoosters, enabled = status.boostsCount > 0, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("${status.boostsCount} ブースト", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onFavourites, enabled = status.favouritesCount > 0, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("${status.favouritesCount} お気に入り", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun StatusAccountsDialog(
    title: String,
    accounts: List<StatusAuthor>,
    isLoading: Boolean,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onAccountClick: (String) -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
    val minHeight = minOf(129.dp, maxHeight)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight, max = maxHeight).testTag("status_accounts_dialog"),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "一覧を閉じる")
                    }
                }
                HorizontalDivider()
                when {
                    isLoading -> Spacer(Modifier.height(64.dp))
                    errorMessage != null -> Text(errorMessage, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    accounts.isEmpty() -> Text("表示できるアカウントはありません", Modifier.padding(20.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(accounts, key = StatusAuthor::id) { account ->
                            AccountRow(account) { onAccountClick(account.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: StatusAuthor, onClick: () -> Unit) {
    var loadAvatar by remember(account.id, account.avatarUrl) { mutableStateOf(false) }
    var avatarFailed by remember(account.id, account.avatarUrl) { mutableStateOf(false) }
    LaunchedEffect(account.id, account.avatarUrl) {
        // Draw the account name first, then start the avatar request on a later frame.
        withFrameNanos { }
        withFrameNanos { }
        loadAvatar = true
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (account.avatarUrl.isBlank() || avatarFailed) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loadAvatar && account.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = account.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onLoading = { avatarFailed = false },
                    onSuccess = { avatarFailed = false },
                    onError = { avatarFailed = true },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            CustomEmojiText(
                account.displayName,
                account.customEmojis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text("@${account.accountName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun exactDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("H:mm  ·  yyyy年M月d日", Locale.JAPAN)
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)
