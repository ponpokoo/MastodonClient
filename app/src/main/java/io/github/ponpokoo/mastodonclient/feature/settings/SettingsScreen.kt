package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.AccountPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ActionIconSize
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconSize
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ComposerAction
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.FontSizePreset
import io.github.ponpokoo.mastodonclient.core.preferences.LineSpacingPreset
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction
import io.github.ponpokoo.mastodonclient.core.preferences.StreamingPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.ThumbnailSize
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageContent(
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    maintenance: SettingsMaintenanceViewModel,
    store: UserPreferencesStore,
    activeSession: AccountSession?,
    sessions: List<AccountSession>,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val preferences by store.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
    val scope = rememberCoroutineScope()
    val updateDisplay = { value: io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences ->
        scope.launch { store.setTimelineDisplay(value) }
    }
    val accountPreferences = preferences.forAccount(activeSession?.sessionId)
    val updateAccount = { value: AccountPreferences ->
        activeSession?.let { session -> scope.launch { store.setAccountPreferences(session.sessionId, value) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("settings_screen")) {
            if (page == SettingsPage.Appearance) {
                item { SectionTitle("外観") }
                item {
                    ChoiceRow("テーマ", preferences.themeMode, ThemeMode.entries) {
                        scope.launch { store.setThemeMode(it) }
                    }
                }
            }
            if (page == SettingsPage.Timeline) {
                item { SectionTitle("タイムライン表示") }
                item {
                    ChoiceRow("フォントサイズ", preferences.timelineDisplay.fontSize, FontSizePreset.entries) {
                        updateDisplay(preferences.timelineDisplay.copy(fontSize = it))
                    }
                }
                item {
                    ChoiceRow("行間", preferences.timelineDisplay.lineSpacing, LineSpacingPreset.entries) {
                        updateDisplay(preferences.timelineDisplay.copy(lineSpacing = it))
                    }
                }
                item {
                    ChoiceRow("ユーザーアイコンサイズ", preferences.timelineDisplay.avatarIconSize, AvatarIconSize.entries) {
                        updateDisplay(preferences.timelineDisplay.copy(avatarIconSize = it))
                    }
                }
                item {
                    ChoiceRow("アクションボタンサイズ", preferences.timelineDisplay.actionIconSize, ActionIconSize.entries) {
                        updateDisplay(preferences.timelineDisplay.copy(actionIconSize = it))
                    }
                }
                item {
                    ChoiceRow("サムネイルサイズ", preferences.timelineDisplay.thumbnailSize, ThumbnailSize.entries) {
                        updateDisplay(preferences.timelineDisplay.copy(thumbnailSize = it))
                    }
                }
                item {
                    SwitchRow("反応数を表示", preferences.timelineDisplay.showCounts) {
                        updateDisplay(preferences.timelineDisplay.copy(showCounts = it))
                    }
                }
                item { Text("投稿下部アイコン", modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
                item {
                    ReorderActionList(
                        order = preferences.timelineDisplay.actionOrder,
                        label = { it.label() }, icon = { it.settingsIcon() },
                        onReorder = { updateDisplay(preferences.timelineDisplay.copy(actionOrder = it)) },
                        leading = { action ->
                            Checkbox(
                                checked = action !in preferences.timelineDisplay.hiddenActions,
                                onCheckedChange = { visible ->
                                    val hidden = preferences.timelineDisplay.hiddenActions.toMutableSet().apply {
                                        if (visible) remove(action) else add(action)
                                    }
                                    updateDisplay(preferences.timelineDisplay.copy(hiddenActions = hidden))
                                },
                            )
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = { updateDisplay(io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences()) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text("表示設定を初期値に戻す") }
                }
                item {
                    Text("プレビュー", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    StatusCard(
                        status = previewStatus,
                        onStatusClick = null,
                        onUnavailableAction = {},
                        displayPreferences = preferences.timelineDisplay,
                        gifAutoplay = preferences.gifAutoplay,
                        videoAutoplay = preferences.videoAutoplay,
                    )
                }

            }
            if (page == SettingsPage.Media) {
                item { SectionTitle("メディア") }
                item {
                    ChoiceRow("GIFの自動再生", preferences.gifAutoplay, AutoplayPolicy.entries) {
                        scope.launch { store.setGifAutoplay(it) }
                    }
                }
                item {
                    ChoiceRow("動画の自動再生", preferences.videoAutoplay, AutoplayPolicy.entries) {
                        scope.launch { store.setVideoAutoplay(it) }
                    }
                }

            }
            if (page == SettingsPage.Connection) {
                item { SectionTitle("タイムラインと通信") }
                item {
                    ChoiceRow("ストリーミング", accountPreferences.streaming, StreamingPolicy.entries, activeSession == null) {
                        updateAccount(accountPreferences.copy(streaming = it))
                    }
                }
                item {
                    SwitchRow("バックグラウンドでは停止", preferences.pauseStreamingInBackground) {
                        scope.launch { store.setPauseStreamingInBackground(it) }
                    }
                }
                item {
                    SwitchRow("引っ張って更新時に元の場所にとどまる", preferences.keepPositionOnPullRefresh) {
                        scope.launch { store.setKeepPositionOnPullRefresh(it) }
                    }
                }

            }
            if (page == SettingsPage.Notifications) {
                item { SectionTitle("通知") }
                item {
                    SwitchRow("起動中の通知", preferences.foregroundNotificationsEnabled) {
                        scope.launch { store.setForegroundNotificationsEnabled(it) }
                    }
                }
                item {
                    Text("アプリが前面にある間、閲覧中アカウントの新着通知をAndroid通知に表示します。ストリーミングと端末の通知許可が必要です。",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                item {
                    SwitchRow("簡易通知（約15分ごとに確認）", preferences.simpleNotificationsEnabled) {
                        scope.launch { store.setSimpleNotificationsEnabled(it) }
                    }
                }
                item {
                    Text(
                        "端末がアプリのバックグラウンド実行を制限している場合、通知が遅れたり届かなかったりすることがあります。",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preferences.simpleNotificationSetupFailed) {
                    item {
                        Text(
                            "定期確認を登録できませんでした。簡易通知を一度オフにしてから、もう一度オンにしてください。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

            }
            if (page == SettingsPage.Composer) {
                item { SectionTitle("投稿") }
                activeSession?.let { session ->
                    item {
                        Text(
                            "@${session.username} · ${session.instanceUrl}",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    ChoiceRow("既定の公開範囲", accountPreferences.defaultVisibility, PostVisibility.entries, activeSession == null) {
                        updateAccount(accountPreferences.copy(defaultVisibility = it))
                    }
                }
                item {
                    SwitchRow("代替テキスト未入力時に確認", preferences.altTextReminder) {
                        scope.launch { store.setAltTextReminder(it) }
                    }
                }
                item { Text("投稿画面のボタン順", modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
                item {
                    ReorderActionList(
                        order = preferences.composerActionOrder,
                        label = { it.label() }, icon = { it.settingsIcon() },
                        onReorder = { scope.launch { store.setComposerActionOrder(it) } },
                        leading = { action ->
                            Checkbox(
                                checked = action !in preferences.hiddenComposerActions,
                                onCheckedChange = { visible ->
                                    val hidden = preferences.hiddenComposerActions.toMutableSet().apply {
                                        if (visible) remove(action) else add(action)
                                    }
                                    scope.launch { store.setHiddenComposerActions(hidden) }
                                },
                            )
                        },
                    )
                }
                item {
                    SwitchRow("リンクをアプリ内で開く", preferences.openLinksInApp) {
                        scope.launch { store.setOpenLinksInApp(it) }
                    }
                }
            }
            if (page == SettingsPage.Accounts) {
                item { SectionTitle("アカウント管理") }
                items(sessions, key = AccountSession::sessionId) { session ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = session.avatarUrl,
                            contentDescription = "${session.displayName.ifBlank { session.username }}のアイコン",
                            modifier = Modifier.size(46.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.displayName.ifBlank { session.username }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "@${session.username} · ${session.instanceUrl.removePrefix("https://")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (session.sessionId == activeSession?.sessionId) {
                                Text("現在のアカウント", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("アカウントを追加")
                    }
                }
                item {
                    TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text("現在のアカウントからログアウト", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (page == SettingsPage.Root) {
                items(SettingsPage.entries.filter { it != SettingsPage.Root }) { destination ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(destination.title) },
                        supportingContent = { Text(destination.description) },
                        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable { onPage(destination) }.testTag("settings_category_" + destination.name),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
            if (page == SettingsPage.Storage) {
                item { CacheSettings(maintenance) }
            }
            if (page == SettingsPage.About) {
                item {
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("Nagisa") },
                        supportingContent = { Text("バージョン " + maintenance.versionName + " (" + maintenance.versionCode + ")") },
                    )
                }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    selected: T,
    choices: List<T>,
    disabled: Boolean = false,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !disabled) { expanded = true }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(label)
        Text(
            selected.displayLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.displayLabel()) },
                    onClick = { expanded = false; onSelected(choice) },
                )
            }
        }
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> = toMutableList().apply {
    add(to, removeAt(from))
}

private fun StatusAction.label() = when (this) {
    StatusAction.Reply -> "返信"
    StatusAction.Boost -> "ブースト"
    StatusAction.Favourite -> "お気に入り"
    StatusAction.Reaction -> "リアクション"
    StatusAction.Bookmark -> "ブックマーク"
    StatusAction.Share -> "共有"
}

private fun ComposerAction.label() = when (this) {
    ComposerAction.Media -> "画像・動画"
    ComposerAction.Poll -> "アンケート"
    ComposerAction.Emoji -> "絵文字"
    ComposerAction.ContentWarning -> "内容警告（CW）"
    ComposerAction.Mention -> "メンション（@）"
    ComposerAction.SaveDraft -> "下書きに保存"
    ComposerAction.DeleteDraft -> "本文をクリア"
}

private fun Any?.displayLabel(): String = when (this) {
    FontSizePreset.Small -> "小"
    FontSizePreset.Standard -> "標準"
    FontSizePreset.Large -> "大"
    FontSizePreset.ExtraLarge -> "特大"
    LineSpacingPreset.Compact -> "狭い"
    LineSpacingPreset.Standard -> "標準"
    LineSpacingPreset.Relaxed -> "広い"
    AvatarIconSize.Small -> "小"
    AvatarIconSize.Standard -> "標準"
    AvatarIconSize.Large -> "大"
    ActionIconSize.Small -> "小"
    ActionIconSize.Standard -> "標準"
    ActionIconSize.Large -> "大"
    ThumbnailSize.Compact -> "小"
    ThumbnailSize.Standard -> "標準"
    ThumbnailSize.Large -> "大"
    AutoplayPolicy.Always -> "常に再生"
    AutoplayPolicy.WifiOnly -> "Wi-Fiのみ"
    AutoplayPolicy.Never -> "再生しない"
    StreamingPolicy.On -> "オン"
    StreamingPolicy.WifiOnly -> "Wi-Fiのみ"
    StreamingPolicy.Off -> "オフ"
    ThemeMode.Light -> "ホワイト"
    ThemeMode.Dark -> "ダーク"
    ThemeMode.System -> "端末の設定に合わせる"
    PostVisibility.Public -> "公開"
    PostVisibility.Unlisted -> "ひかえめな公開"
    PostVisibility.FollowersOnly -> "フォロワー限定"
    PostVisibility.Direct -> "指定した相手のみ"
    else -> toString()
}

private val previewStatus = TimelineStatus(
    timelineId = "settings-preview",
    statusId = "settings-preview",
    createdAt = "2026-09-09T00:00:00Z",
    author = StatusAuthor("preview", "表示プレビュー", "preview@example.social", ""),
    boostedBy = null,
    contentHtml = "<p>フォントサイズ、行間、アイコンの並びをここで確認できます。</p>",
    spoilerText = "",
    sensitive = false,
    visibility = "public",
    url = null,
    repliesCount = 2,
    boostsCount = 3,
    favouritesCount = 5,
    supportsEmojiReactions = true,
    mediaAttachments = emptyList(),
)
