package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
    val listState = rememberLazyListState()
    var accountPicker by remember(state.page) { mutableStateOf(false) }
    var deleteFilter by remember(state.selected?.sessionId) { mutableStateOf<ServerFilter?>(null) }
    var release by remember(state.selected?.sessionId, state.page) { mutableStateOf<ModerationAccount?>(null) }
    val back = { if (state.page == ModerationPage.Overview) onBack() else viewModel.open(ModerationPage.Overview) }
    LaunchedEffect(state.selected?.sessionId, state.page) { snackbar.currentSnackbarData?.dismiss() }
    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            if (snackbar.showTwoSecondSnackbar(notice.message, if (notice.canUndo) "取り消し" else null) == SnackbarResult.ActionPerformed)
                viewModel.undoNotice(notice.id)
            viewModel.consumeNotice(notice.id)
        }
    }
    state.editor?.let {
        ServerFilterEditor(it, state.busy, state.error, viewModel::changeFilter, viewModel::saveFilter, viewModel::closeFilterEditor)
        return
    }
    BackHandler(onBack = back)
    val enabled = state.selected != null && !state.busy && state.lists.values.none { it.loading }
    Scaffold(topBar = { TopAppBar(title = { Text("ミュート・ブロック・フィルター") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") }
    }) }, snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("management_snackbar")) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("moderation_management"), state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            if (state.page == ModerationPage.Overview) {
                item { AccountSwitcherRow(state.selected, { accountPicker = true }, Modifier.testTag("management_account_switcher"), enabled = state.sessions.isNotEmpty()) }
            }
            if (state.selected == null) item { Text("管理するアカウントがありません", Modifier.padding(vertical = 24.dp)) }
            if (state.page == ModerationPage.Overview) {
                items(listOf(ModerationPage.Mutes, ModerationPage.Blocks, ModerationPage.Filters)) { page ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.open(page) }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(page.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    }
                    HorizontalDivider()
                }
            } else if (state.page == ModerationPage.Filters) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("設定中のフィルター", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = viewModel::refresh, enabled = enabled && state.server?.fetch?.get(ServerSetting.Filters)?.loading != true) {
                            Icon(Icons.Outlined.Refresh, "一覧を更新")
                        }
                    }
                    Button(onClick = { viewModel.editFilter(null) }, enabled = enabled && state.server?.pendingCreation == null && state.server?.fetch?.get(ServerSetting.Filters)?.unsupported != true) { Text("フィルターを追加") }
                    val fetch = state.server?.fetch?.get(ServerSetting.Filters)
                    fetch?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.server?.filters == null) Text("フィルターは未取得です。設定内の更新ボタンから取得してください。")
                    if (state.server?.pendingCreation != null) Text("作成結果の確認待ちです。次の同期で確認します。")
                }
                items(state.server?.filters.orEmpty(), key = { it.id }) { filter ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(filter.title, style = MaterialTheme.typography.titleMedium)
                        Text(filter.context.joinToString("、") { filterContexts[it] ?: it }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("動作：" + when (filter.action) { "hide" -> "非表示"; "blur" -> "メディアを隠す"; else -> "警告（CW）" },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("期限：" + formatFilterExpiry(filter.expiresAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { viewModel.editFilter(filter) }, enabled = enabled) { Text("編集") }
                            TextButton(onClick = { deleteFilter = filter }, enabled = enabled) { Text("削除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    HorizontalDivider()
                }
                if (state.server?.filters?.isEmpty() == true) item { Text("登録したフィルターはありません") }
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
                if (!list.loaded && !list.loading) item { Text("未取得の一覧です。設定内の更新ボタンから取得してください。") }
                state.server?.fetch?.get(if (kind == ModerationListKind.Mutes) ServerSetting.Mutes else ServerSetting.Blocks)?.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (list.loaded && list.accounts.isEmpty() && !list.loading) item { Text("${state.page.label()}のアカウントはありません", Modifier.padding(vertical = 24.dp)) }
                if (list.nextMaxId != null && !list.loading) item { TextButton(viewModel::loadMore, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
            }
            state.error?.let { error -> item { Text(error, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error); TextButton(viewModel::retry, enabled = !state.busy) { Text("再試行") } } }
            if (state.busy && state.page != ModerationPage.Filters) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }
        }
    }
    deleteFilter?.let { filter -> AlertDialog(onDismissRequest = { deleteFilter = null }, title = { Text("フィルターを削除") },
        text = { Text("「" + filter.title + "」を削除しますか？") }, confirmButton = { TextButton(onClick = { deleteFilter = null; viewModel.deleteFilter(filter) }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deleteFilter = null }) { Text("キャンセル") } }) }
    if (accountPicker) AccountSwitchDialog("アカウントを切り替える", state.sessions, state.selected?.sessionId,
        { accountPicker = false; viewModel.selectAccount(it) }, { accountPicker = false })
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

private fun ModerationPage.label() = when (this) { ModerationPage.Mutes -> "ミュート中"; ModerationPage.Blocks -> "ブロック中"; ModerationPage.Filters -> "サーバーのフィルター"; ModerationPage.Overview -> "ミュート・ブロックの管理" }
