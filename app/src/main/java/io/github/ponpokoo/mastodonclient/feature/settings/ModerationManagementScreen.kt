package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationManagementScreen(viewModel: ModerationManagementViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var inputFocused by remember { mutableStateOf(false) }
    var inputBounds by remember { mutableStateOf<Rect?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    var accountPicker by remember { mutableStateOf(false) }
    var input by rememberSaveable(state.selected?.sessionId) { mutableStateOf("") }
    var deleteWord by remember(state.selected?.sessionId, state.page) { mutableStateOf<String?>(null) }
    var release by remember(state.selected?.sessionId, state.page) { mutableStateOf<ModerationAccount?>(null) }
    val back = { if (state.page == ModerationPage.Overview) onBack() else viewModel.open(ModerationPage.Overview) }
    BackHandler(onBack = back)
    LaunchedEffect(state.selected?.sessionId, state.page) { snackbar.currentSnackbarData?.dismiss() }
    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            if (state.page == ModerationPage.Words && input.trim() in state.words) input = ""
            if (snackbar.showTwoSecondSnackbar(notice.message, if (notice.canUndo) "取り消し" else null) == SnackbarResult.ActionPerformed)
                viewModel.undoNotice(notice.id)
            viewModel.consumeNotice(notice.id)
        }
    }
    val enabled = state.selected != null && !state.busy && state.lists.values.none { it.loading }
    Scaffold(modifier = Modifier.onGloballyPositioned { screenOrigin = it.positionInRoot() }
        .pointerInput(focusManager, keyboard) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!inputFocused || inputBounds?.contains(down.position + screenOrigin) != false) return@awaitEachGesture
                var dragged = false
                while (true) {
                    // Observe after child controls handle the tap; do not consume their events.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        event.changes.any { it.id != down.id && it.pressed }) dragged = true
                    if (!change.pressed) {
                        if (!dragged) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                        break
                    }
                }
            }
        }, topBar = { TopAppBar(title = { Text("ミュート・ブロックの管理") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("moderation_management"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item { AccountSwitcherRow(state.selected, { accountPicker = true }, Modifier.testTag("management_account_switcher"), enabled = state.sessions.isNotEmpty()) }
            if (state.selected == null) item { Text("管理するアカウントがありません", Modifier.padding(vertical = 24.dp)) }
            if (state.page == ModerationPage.Overview) {
                items(listOf(ModerationPage.Words, ModerationPage.Mutes, ModerationPage.Blocks)) { page ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.open(page) }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(page.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    }
                    HorizontalDivider()
                }
            } else if (state.page == ModerationPage.Words) {
                item {
                    Row(Modifier.padding(top = 20.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(input, { if (it.length <= 100) input = it }, Modifier.weight(1f).testTag("word_mute_input")
                            .onFocusChanged { inputFocused = it.isFocused }
                            .onGloballyPositioned { inputBounds = it.boundsInRoot() },
                            label = { Text("追加するワード") }, singleLine = true, enabled = enabled)
                        Button(onClick = { viewModel.addWord(input) }, enabled = enabled && input.isNotBlank()) { Text("追加") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("登録したワード", style = MaterialTheme.typography.labelLarge)
                        Text("${state.words.size}件", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(state.words, key = { it }) { word ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(word, Modifier.weight(1f))
                        IconButton(onClick = { deleteWord = word }, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "$word を削除") }
                    }
                    HorizontalDivider()
                }
                if (state.words.isEmpty()) item { Text("登録したワードはありません", Modifier.padding(vertical = 24.dp)) }
                item { Text("部分一致で判定します。英字の大文字・小文字は区別しません。", Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val kind = if (state.page == ModerationPage.Mutes) ModerationListKind.Mutes else ModerationListKind.Blocks
                val list = state.lists[kind] ?: ManagementList()
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("設定中のアカウント", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = viewModel::refresh, enabled = enabled) { Icon(Icons.Outlined.Refresh, "一覧を更新") }
                    }
                }
                items(list.accounts, key = { it.account.id }) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(entry.account.avatarUrl, null, Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) {
                            Text(entry.account.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("@${entry.account.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (kind == ModerationListKind.Mutes && entry.relationship.blocking || kind == ModerationListKind.Blocks && entry.relationship.muting)
                                Text(if (kind == ModerationListKind.Mutes) "ブロックも設定中" else "ミュートも設定中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { release = entry }, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text("解除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
                if (list.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                if (list.loaded && list.accounts.isEmpty() && !list.loading) item { Text("${state.page.label()}のアカウントはありません", Modifier.padding(vertical = 24.dp)) }
                if (list.nextMaxId != null && !list.loading) item { TextButton(viewModel::loadMore, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
            }
            state.error?.let { error -> item { Text(error, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error); TextButton(viewModel::retry, enabled = !state.busy) { Text("再試行") } } }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }
        }
    }
    if (accountPicker) AccountSwitchDialog("アカウントを切り替える", state.sessions, state.selected?.sessionId,
        { accountPicker = false; viewModel.selectAccount(it) }, { accountPicker = false })
    deleteWord?.let { word -> AlertDialog(onDismissRequest = { deleteWord = null }, title = { Text("ワードミュートを削除") },
        text = { Text("「$word」を削除しますか？") }, confirmButton = { TextButton({ deleteWord = null; viewModel.removeWord(word) }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleteWord = null }) { Text("キャンセル") } }) }
    release?.let { entry ->
        val mute = state.page == ModerationPage.Mutes
        AlertDialog(onDismissRequest = { release = null }, title = { Text(if (mute) "ミュート解除" else "ブロック解除") },
            text = { Column {
                Text("${entry.account.displayName}さんの${if (mute) "ミュート" else "ブロック"}を解除しますか？")
                if (mute && entry.relationship.blocking || !mute && entry.relationship.muting)
                    Text("${if (mute) "ブロック" else "ミュート"}は引き続き有効です。投稿の非表示も続きます。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton({ release = null; viewModel.release(entry) }) { Text("解除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ release = null }) { Text("キャンセル") } })
    }
}

private fun ModerationPage.label() = when (this) { ModerationPage.Words -> "ワードミュート"; ModerationPage.Mutes -> "ミュート中"; ModerationPage.Blocks -> "ブロック中"; ModerationPage.Overview -> "ミュート・ブロックの管理" }
