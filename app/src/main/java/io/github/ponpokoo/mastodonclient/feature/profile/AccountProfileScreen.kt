package io.github.ponpokoo.mastodonclient.feature.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.timeline.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountProfileScreen(
    viewModel: AccountProfileViewModel,
    openEditor: Boolean,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onStatusClick: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    onFollowers: (String) -> Unit,
    onFollowing: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    var reportOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var initialEditorHandled by rememberSaveable { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    val profile = state.profile

    LaunchedEffect(openEditor, profile?.author?.id) {
        if (openEditor && profile != null && !initialEditorHandled) {
            editOpen = true
            initialEditorHandled = true
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(profile?.author?.displayName ?: "プロフィール") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "プロフィールメニュー") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    fun close(action: () -> Unit) { menu = false; action() }
                    DropdownMenuItem(text = { Text("共有") }, onClick = { close { profile?.url?.let { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), "プロフィールを共有")) } } })
                    DropdownMenuItem(text = { Text("リンクをコピー") }, onClick = { close { profile?.url?.let { (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("profile", it)) } } })
                    DropdownMenuItem(text = { Text("QRコードを表示") }, onClick = { close { qrOpen = true } })
                    DropdownMenuItem(text = { Text("ブラウザーで開く") }, onClick = { close { profile?.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) } } })
                    if (profile?.isOwnProfile == true) {
                        DropdownMenuItem(text = { Text("お気に入り") }, onClick = { close { onOpenLink("${profile.url}/favourites") } })
                        DropdownMenuItem(text = { Text("ブックマーク") }, onClick = { close { onOpenLink("${profile.url}/bookmarks") } })
                        DropdownMenuItem(text = { Text("フォロー中のハッシュタグ") }, onClick = { close { onOpenLink("${profile.url}/followed_tags") } })
                        DropdownMenuItem(text = { Text("アカウント設定") }, onClick = { close { onOpenLink("${profile.url}/settings/profile") } })
                    } else if (profile != null) {
                        DropdownMenuItem(text = { Text(if (state.relationship?.muting == true) "ミュート解除" else "ミュート") }, onClick = { close { confirmAction = "mute" } })
                        DropdownMenuItem(text = { Text(if (state.relationship?.blocking == true) "ブロック解除" else "ブロック") }, onClick = { close { confirmAction = "block" } })
                        DropdownMenuItem(text = { Text("通報") }, onClick = { close { reportOpen = true } })
                    }
                }
            },
        )
    }, snackbarHost = { state.message?.let { LaunchedEffect(it) { viewModel.clearMessage() } } }) { padding ->
        ProfileContent(
            state = TimelineUiState(profile = profile, isLoadingProfile = state.isLoading, profileError = state.errorMessage),
            padding = padding, onRetry = viewModel::retry, onStatusClick = onStatusClick, onOpenLink = onOpenLink,
            onReply = onReply, onBoost = viewModel::toggleReblog, onFavourite = viewModel::toggleFavourite,
            onBookmark = viewModel::toggleBookmark, onReact = viewModel::setReaction, onAccountClick = onAccountClick,
            onMediaClick = onMediaClick, relationship = state.relationship, selectedTab = state.selectedTab,
            preferences = preferences,
            isLoadingMore = state.isLoadingMore, onSelectTab = viewModel::selectTab, onLoadMore = viewModel::loadMore,
            onFollowers = { profile?.author?.id?.let(onFollowers) },
            onFollowing = { profile?.author?.id?.let(onFollowing) },
            onHeaderClick = { profile?.headerUrl?.takeIf(String::isNotBlank)?.let { onMediaClick(MediaAttachment("header", "image", it, it, "ヘッダー画像")) } },
            onAvatarClick = { profile?.author?.avatarUrl?.takeIf(String::isNotBlank)?.let { onMediaClick(MediaAttachment("avatar", "image", it, it, "プロフィール画像")) } },
            onEditProfile = { editOpen = true }, onToggleFollow = viewModel::toggleFollow,
        )
    }

    confirmAction?.let { action -> AlertDialog(
        onDismissRequest = { confirmAction = null }, title = { Text(if (action == "mute") "ミュートを変更" else "ブロックを変更") },
        text = { Text("この操作を実行しますか？") }, confirmButton = { TextButton(onClick = { if (action == "mute") viewModel.toggleMute() else viewModel.toggleBlock(); confirmAction = null }) { Text("実行") } },
        dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("キャンセル") } },
    ) }
    if (reportOpen) ReportDialog(onDismiss = { reportOpen = false }, onSubmit = { text, forward -> viewModel.report(text, forward); reportOpen = false })
    if (editOpen && profile != null) EditProfileDialog(profile, { editOpen = false }) { viewModel.updateProfile(it); editOpen = false }
    if (qrOpen && profile != null) QrDialog(profile.url) { qrOpen = false }
}

@Composable private fun ReportDialog(onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }; var forward by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("アカウントを通報") }, text = { Column {
        OutlinedTextField(text, { text = it.take(1000) }, label = { Text("理由・補足") }, minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(forward, { forward = it }); Text("相手側のサーバーにも転送") }
    } }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSubmit(text, forward) }) { Text("送信") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

@Composable private fun EditProfileDialog(profile: UserProfile, onDismiss: () -> Unit, onSave: (ProfileEditRequest) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(profile.author.displayName) }; var note by remember { mutableStateOf(androidx.core.text.HtmlCompat.fromHtml(profile.noteHtml, 0).toString()) }; var locked by remember { mutableStateOf(profile.locked) }
    var avatarPath by remember { mutableStateOf<String?>(null) }; var headerPath by remember { mutableStateOf<String?>(null) }
    fun copyImage(uri: android.net.Uri, prefix: String): String? = runCatching {
        val file = File(context.cacheDir, "$prefix-${System.nanoTime()}")
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use(input::copyTo) }; file.absolutePath
    }.getOrNull()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> avatarPath = copyImage(uri, "profile-avatar") } }
    val headerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> headerPath = copyImage(uri, "profile-header") } }
    val fields = remember { mutableStateListOf<Pair<String, String>>().also { list -> list.addAll(profile.fields.take(4).map { it.name to androidx.core.text.HtmlCompat.fromHtml(it.valueHtml, 0).toString() }) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("プロフィールを編集") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("表示名") }); Spacer(Modifier.height(8.dp))
        OutlinedTextField(note, { note = it }, label = { Text("自己紹介") }, minLines = 4)
        Row { TextButton(onClick = { avatarPicker.launch("image/*") }) { Text(if (avatarPath == null) "アイコンを変更" else "アイコン選択済み") }; TextButton(onClick = { headerPicker.launch("image/*") }) { Text(if (headerPath == null) "ヘッダーを変更" else "ヘッダー選択済み") } }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(locked, { locked = it }); Spacer(Modifier.width(8.dp)); Text("フォローを承認制にする") }
        fields.forEachIndexed { index, field -> Row { OutlinedTextField(field.first, { fields[index] = it to field.second }, Modifier.weight(0.4f), label = { Text("項目") }); OutlinedTextField(field.second, { fields[index] = field.first to it }, Modifier.weight(0.6f), label = { Text("内容") }) } }
        if (fields.size < 4) TextButton(onClick = { fields.add("" to "") }) { Text("プロフィール項目を追加") }
    } }, confirmButton = { TextButton(onClick = { onSave(ProfileEditRequest(name, note, locked, true, fields.toList(), avatarPath, headerPath)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
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
