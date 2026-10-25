package io.github.ponpokoo.mastodonclient.feature.profile


import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmation
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmationAction
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.status.ConfirmStatusActionDialog
import io.github.ponpokoo.mastodonclient.feature.status.ListPickerDialog
import io.github.ponpokoo.mastodonclient.feature.status.StatusMenuDialog
import io.github.ponpokoo.mastodonclient.feature.status.StatusReportDialog
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.feature.timeline.animateToTimelineTop

private enum class ProfileAccountAction { Unfollow, Mute, Unmute, Block, Unblock }

@Composable
internal fun ProfileScreen(
    viewModel: ProfileViewModel,
    preferences: AppPreferences,
    padding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onStatusClick: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onFollowers: (String) -> Unit,
    onFollowing: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenFavourites: () -> Unit,
    onEditStatus: (String) -> Unit,
    openEditor: Boolean = false,
    profileListStates: List<androidx.compose.foundation.lazy.LazyListState>? = null,
    profileHeaderListState: androidx.compose.foundation.lazy.LazyListState? = null,
    onOpenReactions: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.loadProfile() }
    key(state.imageSessionKey) {
        val listStates = profileListStates ?: List(ProfileStatusTab.entries.size) { rememberLazyListState() }
        val headerListState = profileHeaderListState ?: rememberLazyListState()
        val moderationMenu by viewModel.moderationMenuState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var confirmAction by remember { mutableStateOf<ProfileAccountAction?>(null) }
        var reportOpen by remember { mutableStateOf(false) }
        val imageEdit by viewModel.profileImageEditState.collectAsStateWithLifecycle()
        DisposableEffect(viewModel) { onDispose { viewModel.dismissProfileEdit() } }
        var initialEditorHandled by rememberSaveable { mutableStateOf(false) }
        var statusMenu by remember { mutableStateOf<TimelineStatus?>(null) }
        var statusConfirmation by remember { mutableStateOf<StatusConfirmation?>(null) }
        var statusReport by remember { mutableStateOf<TimelineStatus?>(null) }
        var listStatus by remember { mutableStateOf<TimelineStatus?>(null) }
        var profileListOpen by remember { mutableStateOf(false) }
        val profile = state.profile
        val profileListState = listStates[state.profileSelectedTab.ordinal]
        var scrollAfterRefresh by remember { mutableStateOf(false) }
        var refreshStarted by remember { mutableStateOf(false) }

        LaunchedEffect(openEditor, profile?.author?.id) {
            if (openEditor && profile != null && !initialEditorHandled) {
                viewModel.beginProfileEdit()
                initialEditorHandled = true
            }
        }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearMessage(it)
            }
        }
        LaunchedEffect(state.isRefreshingProfile, state.profileSelectedTab) {
            if (state.isRefreshingProfile) {
                refreshStarted = true
            } else if (refreshStarted) {
                val returnToTop = scrollAfterRefresh
                refreshStarted = false
                scrollAfterRefresh = false
                if (returnToTop) profileListState.animateToTimelineTop(headerListState)
            }
        }

        ProfileContent(
            instanceUrl = state.instanceUrl,
            state = state,
            padding = padding,
            onRetry = viewModel::retryProfile,
            onRetryPosts = viewModel::retryProfile,
            onSelectHashtag = viewModel::selectHashtag,
            onRetryFeaturedTags = viewModel::retryFeaturedTags,
            onRefresh = {
                scrollAfterRefresh = !preferences.keepPositionOnPullRefresh
                refreshStarted = false
                viewModel.refreshProfile()
            },
            onStatusClick = onStatusClick, onOpenLink = onOpenLink,
            onReply = onReply, onBoost = viewModel::toggleReblog, onQuote = onQuote,
            onFavourite = viewModel::toggleFavourite,
            onVotePoll = viewModel::votePoll,
            onBookmark = viewModel::toggleBookmark, onReact = viewModel::setReaction, onAccountClick = onAccountClick,
            onMediaClick = onMediaClick, relationship = state.relationship, selectedTab = state.profileSelectedTab,
            preferences = preferences,
            isLoadingMore = state.isLoadingMoreProfile, onSelectTab = viewModel::selectProfileTab, onLoadMore = viewModel::loadMoreProfile,
            onAutoLoadMore = viewModel::loadMoreProfileAutomatically,
            onFollowers = { profile?.author?.id?.let(onFollowers) },
            onFollowing = { profile?.author?.id?.let(onFollowing) },
            onHeaderClick = { profile?.headerUrl?.takeIf(String::isNotBlank)?.let { onMediaClick(listOf(MediaAttachment("profile-header", "image", it, it, "ヘッダー画像", cacheRevision = state.headerRefreshRevision)), 0) } },
            onAvatarClick = { profile?.author?.avatarUrl?.takeIf(String::isNotBlank)?.let { onMediaClick(listOf(MediaAttachment("profile-avatar", "image", it, it, "プロフィール画像", isAccountAvatar = true)), 0) } },
            onEditProfile = { viewModel.beginProfileEdit() }, onToggleFollow = {
                if (state.relationship?.following == true) confirmAction = ProfileAccountAction.Unfollow
                else viewModel.toggleFollow()
            },
            onOpenLists = onOpenLists,
            onOpenBookmarks = onOpenBookmarks,
            onOpenFavourites = onOpenFavourites,
            onOpenReactions = onOpenReactions,
            onShareProfile = { profile?.url?.let {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it),
                    "プロフィールを共有",
                ))
            } },
            onAddProfileToList = { profileListOpen = true; viewModel.loadLists() },
            onProfileMenuOpen = {
                if (profile?.isOwnProfile == true) viewModel.loadReactionHistorySupport()
                else if (profile != null) viewModel.loadModerationMenu()
            },
            moderationReady = moderationMenu.accountId == profile?.author?.id && moderationMenu.relationship != null && !moderationMenu.loading && !moderationMenu.busy,
            moderationError = moderationMenu.error,
            onRetryModeration = { viewModel.loadModerationMenu() },
            onMuteProfile = { confirmAction = if (moderationMenu.relationship?.muting == true) ProfileAccountAction.Unmute else ProfileAccountAction.Mute },
            onBlockProfile = { confirmAction = if (moderationMenu.relationship?.blocking == true) ProfileAccountAction.Unblock else ProfileAccountAction.Block },
            onReportProfile = { reportOpen = true },
            isProfileMuted = moderationMenu.relationship?.muting == true,
            isProfileBlocked = moderationMenu.relationship?.blocking == true,
            onMoreClick = {
                if (it.author.id != state.currentAccountId) viewModel.loadModerationMenu(it.author.id)
                statusMenu = it
            },
            listStates = listStates,
            headerListState = headerListState,
        )

        statusMenu?.let { status ->
            StatusMenuDialog(
                status = status,
                moderation = moderationMenu.takeIf { it.accountId == status.author.id },
                onRetryRelationship = { viewModel.loadModerationMenu(status.author.id) },
                isOwnStatus = status.author.id == state.currentAccountId,
                onDismiss = { statusMenu = null },
                onOpenBrowser = {
                    statusMenu = null
                    status.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
                },
                onPin = { statusMenu = null; viewModel.setPinned(status) },
                onEdit = { statusMenu = null; onEditStatus(status.statusId) },
                onDelete = { statusMenu = null; statusConfirmation = StatusConfirmation(StatusConfirmationAction.Delete, status) },
                onAddToList = { statusMenu = null; listStatus = status; viewModel.loadLists() },
                onFollow = { statusMenu = null; viewModel.followStatus(status) },
                onUnfollow = { statusMenu = null; statusConfirmation = StatusConfirmation(StatusConfirmationAction.Unfollow, status) },
                onMute = { statusMenu = null; statusConfirmation = StatusConfirmation((if (moderationMenu.relationship?.muting == true) StatusConfirmationAction.Unmute else StatusConfirmationAction.Mute), status) },
                onBlock = { statusMenu = null; statusConfirmation = StatusConfirmation((if (moderationMenu.relationship?.blocking == true) StatusConfirmationAction.Unblock else StatusConfirmationAction.Block), status) },
                onReport = { statusMenu = null; statusReport = status },
            )
        }
        statusConfirmation?.let { (action, status) ->
            ConfirmStatusActionDialog(action, status, { statusConfirmation = null }) {
                when (action) {
                    StatusConfirmationAction.Delete -> viewModel.deleteStatus(status)
                    StatusConfirmationAction.Unfollow -> viewModel.unfollowStatus(status)
                    StatusConfirmationAction.Mute -> viewModel.muteStatus(status)
                    StatusConfirmationAction.Unmute -> viewModel.muteStatus(status, false)
                    StatusConfirmationAction.Block -> viewModel.blockStatus(status)
                    StatusConfirmationAction.Unblock -> viewModel.blockStatus(status, false)
                }
            }
        }
        statusReport?.let { status ->
            StatusReportDialog(status, { statusReport = null }) { comment ->
                viewModel.reportStatus(status, comment)
                statusReport = null
            }
        }
        listStatus?.let { status ->
            ListPickerDialog(state.lists, state.isLoadingLists, { listStatus = null }) { listId ->
                viewModel.addToList(status, listId)
                listStatus = null
            }
        }
        if (profileListOpen) {
            ListPickerDialog(state.lists, state.isLoadingLists, { profileListOpen = false }) { listId ->
                viewModel.addProfileToList(listId)
                profileListOpen = false
            }
        }

        confirmAction?.let { action -> AlertDialog(
            onDismissRequest = { confirmAction = null }, title = { Text(when (action) {
                ProfileAccountAction.Mute -> "ミュート"
                ProfileAccountAction.Unmute -> "ミュート解除"
                ProfileAccountAction.Unblock -> "ブロック解除"
                ProfileAccountAction.Unfollow -> "フォローを解除しますか？"
                ProfileAccountAction.Block -> "ブロック"
            }) },
            text = { Text("${profile?.author?.displayName.orEmpty()}さん" + when (action) {
                ProfileAccountAction.Unfollow -> "のフォローを解除しますか？"
                ProfileAccountAction.Mute -> "をミュートしますか？"
                ProfileAccountAction.Unmute -> "のミュートを解除しますか？"
                ProfileAccountAction.Unblock -> "のブロックを解除しますか？"
                ProfileAccountAction.Block -> "をブロックしますか？"
            }) },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    ProfileAccountAction.Mute -> viewModel.setProfileMuted(true)
                    ProfileAccountAction.Unmute -> viewModel.setProfileMuted(false)
                    ProfileAccountAction.Unblock -> viewModel.setProfileBlocked(false)
                    ProfileAccountAction.Unfollow -> viewModel.toggleFollow()
                    ProfileAccountAction.Block -> viewModel.setProfileBlocked(true)
                }
                confirmAction = null
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(when (action) {
                ProfileAccountAction.Unfollow -> "フォロー解除"
                ProfileAccountAction.Mute -> "ミュートする"
                ProfileAccountAction.Unmute -> "ミュートを解除"
                ProfileAccountAction.Block -> "ブロックする"
                ProfileAccountAction.Unblock -> "ブロックを解除"
            }) } },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("キャンセル") } },
        ) }
        if (reportOpen) ReportDialog(onDismiss = { reportOpen = false }, onSubmit = { text, forward -> viewModel.report(text, forward); reportOpen = false })
        if (imageEdit.isOpen && profile != null) EditProfileDialog(profile, imageEdit,
            viewModel::selectProfileImage, viewModel::dismissProfileEdit, viewModel::updateProfile)
    }
}

@Composable private fun ReportDialog(onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }; var forward by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("アカウントを報告") }, text = { Column {
        OutlinedTextField(text, { text = it.take(1000) }, label = { Text("理由・補足") }, minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(forward, { forward = it }); Text("相手側のサーバーにも転送") }
    } }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSubmit(text, forward) }) { Text("送信") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

@Composable internal fun EditProfileDialog(profile: UserProfile, images: ProfileImageEditState,
    onSelectImage: (ProfileImageSlot, String) -> Unit, onDismiss: () -> Unit, onSave: (ProfileEditRequest) -> Unit) {
    var name by remember { mutableStateOf(profile.author.displayName) }; var note by remember { mutableStateOf(androidx.core.text.HtmlCompat.fromHtml(profile.noteHtml, 0).toString()) }; var locked by remember { mutableStateOf(profile.locked) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { uri -> onSelectImage(ProfileImageSlot.Avatar, uri.toString()) }
    }
    val headerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { uri -> onSelectImage(ProfileImageSlot.Header, uri.toString()) }
    }
    val fields = remember { mutableStateListOf<Pair<String, String>>().also { list -> list.addAll(profile.fields.take(4).map { it.name to androidx.core.text.HtmlCompat.fromHtml(it.valueHtml, 0).toString() }) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("プロフィールを編集") }, text = { Column {
        OutlinedTextField(name, { name = it }, enabled = !images.isSaving, label = { Text("表示名") }); Spacer(Modifier.height(8.dp))
        OutlinedTextField(note, { note = it }, enabled = !images.isSaving, label = { Text("自己紹介") }, minLines = 4)
        Row { TextButton(enabled = !images.isSaving, onClick = { avatarPicker.launch("image/*") }) { Text(if (images.avatarPath == null) "アイコンを変更" else "アイコン選択済み") }; TextButton(enabled = !images.isSaving, onClick = { headerPicker.launch("image/*") }) { Text(if (images.headerPath == null) "ヘッダーを変更" else "ヘッダー選択済み") } }
        if (images.importing.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("画像を取り込み中…")
        }
        images.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(locked, { locked = it }, enabled = !images.isSaving); Spacer(Modifier.width(8.dp)); Text("フォローを承認制にする") }
        fields.forEachIndexed { index, field -> Row { OutlinedTextField(field.first, { fields[index] = it to field.second }, Modifier.weight(0.4f), enabled = !images.isSaving, label = { Text("項目") }); OutlinedTextField(field.second, { fields[index] = field.first to it }, Modifier.weight(0.6f), enabled = !images.isSaving, label = { Text("内容") }) } }
        if (fields.size < 4) TextButton(enabled = !images.isSaving, onClick = { fields.add("" to "") }) { Text("プロフィール項目を追加") }
    } }, confirmButton = {
        TextButton(enabled = images.importing.isEmpty() && !images.isSaving,
            onClick = { onSave(ProfileEditRequest(name, note, locked, true, fields.toList())) }) {
            if (images.isSaving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (images.isSaving) "保存中…" else "保存")
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}
