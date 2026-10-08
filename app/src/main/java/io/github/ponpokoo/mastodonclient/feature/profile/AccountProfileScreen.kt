package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmation
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmationAction
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.status.ConfirmStatusActionDialog
import io.github.ponpokoo.mastodonclient.feature.status.ListPickerSheet
import io.github.ponpokoo.mastodonclient.feature.status.StatusMenuDialog
import io.github.ponpokoo.mastodonclient.feature.status.StatusReportDialog
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.feature.timeline.animateToTimelineTop

private enum class ProfileAccountAction { Unfollow, Mute, Unmute, Block, Unblock }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountProfileScreen(
    viewModel: AccountProfileViewModel,
    openEditor: Boolean,
    preferences: AppPreferences,
    onBack: () -> Unit,
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val moderationMenu by viewModel.moderationMenuState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmAction by remember { mutableStateOf<ProfileAccountAction?>(null) }
    var reportOpen by remember { mutableStateOf(false) }
    val imageEdit by viewModel.profileImageEditState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.dismissProfileEdit() } }
    var initialEditorHandled by rememberSaveable { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf<TimelineStatus?>(null) }
    var statusConfirmation by remember { mutableStateOf<StatusConfirmation?>(null) }
    var statusReport by remember { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember { mutableStateOf<TimelineStatus?>(null) }
    var profileListOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val profile = state.profile
    val profileListStates = List(ProfileStatusTab.entries.size) { rememberLazyListState() }
    val profileHeaderListState = rememberLazyListState()
    val profileListState = profileListStates[state.selectedTab.ordinal]
    var scrollAfterRefresh by remember { mutableStateOf(false) }
    var refreshStarted by remember { mutableStateOf(false) }

    LaunchedEffect(openEditor, profile?.author?.id) {
        if (openEditor && profile != null && !initialEditorHandled) {
            viewModel.beginProfileEdit()
            initialEditorHandled = true
        }
    }
    LaunchedEffect(state.message, state.errorMessage) {
        (state.message ?: state.errorMessage)?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.isRefreshing, state.selectedTab) {
        if (state.isRefreshing) {
            refreshStarted = true
        } else if (refreshStarted) {
            val returnToTop = scrollAfterRefresh
            refreshStarted = false
            scrollAfterRefresh = false
            if (returnToTop) profileListState.animateToTimelineTop(profileHeaderListState)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                val author = profile?.author
                if (author == null) Text("プロフィール") else CustomEmojiText(
                    text = author.displayName,
                    emojis = author.customEmojis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
        )
    }, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        ProfileContent(
            instanceUrl = state.instanceUrl,
            state = ProfileUiState(
                profile = profile,
                imageSessionKey = state.imageSessionKey,
                imageRefreshRevision = state.imageRefreshRevision,
                isLoadingProfile = state.isLoading,
                isRefreshingProfile = state.isRefreshing,
                profileError = state.errorMessage,
                profileTabs = state.profileTabs,
            ),
            padding = padding,
            onRetry = viewModel::retry,
            onRefresh = {
                scrollAfterRefresh = !preferences.keepPositionOnPullRefresh
                refreshStarted = false
                viewModel.refresh()
            },
            onStatusClick = onStatusClick, onOpenLink = onOpenLink,
            onReply = onReply, onBoost = viewModel::toggleReblog, onQuote = onQuote,
            onFavourite = viewModel::toggleFavourite,
            onBookmark = viewModel::toggleBookmark, onReact = viewModel::setReaction, onAccountClick = onAccountClick,
            onMediaClick = onMediaClick, relationship = state.relationship, selectedTab = state.selectedTab,
            preferences = preferences,
            isLoadingMore = state.isLoadingMore, onSelectTab = viewModel::selectTab, onLoadMore = viewModel::loadMore,
            onFollowers = { profile?.author?.id?.let(onFollowers) },
            onFollowing = { profile?.author?.id?.let(onFollowing) },
            onHeaderClick = { profile?.headerUrl?.takeIf(String::isNotBlank)?.let { onMediaClick(listOf(MediaAttachment("header", "image", it, it, "ヘッダー画像", cacheRevision = state.imageRefreshRevision)), 0) } },
            onAvatarClick = { profile?.author?.avatarUrl?.takeIf(String::isNotBlank)?.let { onMediaClick(listOf(MediaAttachment("avatar", "image", it, it, "プロフィール画像", cacheRevision = if (profile.isOwnProfile) state.imageRefreshRevision else profile.author.avatarRevision)), 0) } },
            onEditProfile = { viewModel.beginProfileEdit() }, onToggleFollow = {
                if (state.relationship?.following == true) confirmAction = ProfileAccountAction.Unfollow
                else viewModel.toggleFollow()
            },
            onOpenLists = onOpenLists,
            onOpenBookmarks = onOpenBookmarks,
            onOpenFavourites = onOpenFavourites,
            onShareProfile = { profile?.url?.let {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it),
                    "プロフィールを共有",
                ))
            } },
            onCopyProfileUrl = { profile?.url?.let {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("profile", it))
            } },
            onShowProfileQr = { qrOpen = true },
            onAddProfileToList = { profileListOpen = true; viewModel.loadLists() },
            onOpenFollowedTags = { profile?.url?.let { onOpenLink("$it/followed_tags") } },
            onProfileMenuOpen = { if (profile?.isOwnProfile == false) viewModel.loadModerationMenu() },
            moderationReady = moderationMenu.accountId == profile?.author?.id && moderationMenu.relationship != null && !moderationMenu.loading && !moderationMenu.busy,
            moderationError = moderationMenu.error,
            onRetryModeration = { viewModel.loadModerationMenu() },
            onMuteProfile = { confirmAction = if (moderationMenu.relationship?.muting == true) ProfileAccountAction.Unmute else ProfileAccountAction.Mute },
            onBlockProfile = { confirmAction = if (moderationMenu.relationship?.blocking == true) ProfileAccountAction.Unblock else ProfileAccountAction.Block },
            onReportProfile = { reportOpen = true },
            isProfileMuted = moderationMenu.relationship?.muting == true,
            isProfileBlocked = moderationMenu.relationship?.blocking == true,
            onMoreClick = { statusMenu = it },
            listStates = profileListStates,
            headerListState = profileHeaderListState,
        )
    }

    LaunchedEffect(statusMenu?.author?.id) {
        statusMenu?.author?.id?.let { viewModel.loadModerationMenu(it) }
    }
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
        ListPickerSheet(state.lists, state.isLoadingLists, { listStatus = null }) { listId ->
            viewModel.addToList(status, listId)
            listStatus = null
        }
    }
    if (profileListOpen) {
        ListPickerSheet(state.lists, state.isLoadingLists, { profileListOpen = false }) { listId ->
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
        }) { Text(if (action == ProfileAccountAction.Unfollow) "フォロー解除" else "実行") } },
        dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("キャンセル") } },
    ) }
    if (reportOpen) ReportDialog(onDismiss = { reportOpen = false }, onSubmit = { text, forward -> viewModel.report(text, forward); reportOpen = false })
    if (imageEdit.isOpen && profile != null) EditProfileDialog(profile, imageEdit,
        viewModel::selectProfileImage, viewModel::dismissProfileEdit, viewModel::updateProfile)
    if (qrOpen && profile != null) QrDialog(profile.url) { qrOpen = false }
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
        if (images.importing.isNotEmpty()) Text("画像を取り込み中…")
        images.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(locked, { locked = it }, enabled = !images.isSaving); Spacer(Modifier.width(8.dp)); Text("フォローを承認制にする") }
        fields.forEachIndexed { index, field -> Row { OutlinedTextField(field.first, { fields[index] = it to field.second }, Modifier.weight(0.4f), enabled = !images.isSaving, label = { Text("項目") }); OutlinedTextField(field.second, { fields[index] = field.first to it }, Modifier.weight(0.6f), enabled = !images.isSaving, label = { Text("内容") }) } }
        if (fields.size < 4) TextButton(enabled = !images.isSaving, onClick = { fields.add("" to "") }) { Text("プロフィール項目を追加") }
    } }, confirmButton = { TextButton(enabled = images.importing.isEmpty() && !images.isSaving, onClick = { onSave(ProfileEditRequest(name, note, locked, true, fields.toList())) }) { Text(if (images.isSaving) "保存中…" else "保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

@Composable private fun QrDialog(url: String, onDismiss: () -> Unit) {
    val bitmap = remember(url) {
        val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640)
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 640) for (x in 0 until 640) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("プロフィールQRコード") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Image(bitmap.asImageBitmap(), "プロフィールQRコード", Modifier.fillMaxWidth()); Text(url) } }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}
