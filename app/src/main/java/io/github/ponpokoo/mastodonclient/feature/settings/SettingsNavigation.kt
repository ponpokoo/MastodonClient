package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

internal enum class SettingsPage(val title: String, val description: String) {
    Root("設定", ""),
    Appearance("外観", "ライト・ダーク・端末のテーマ"),
    Timeline("タイムライン表示", "文字・アイコン・操作の並びとプレビュー"),
    Media("メディア", "GIF・動画の自動再生"),
    Connection("タイムラインと通信", "ストリーミング・更新時の動作"),
    Notifications("通知", "通知の受け取り方・Android通知の表示"),
    Composer("投稿", "公開範囲・ALT確認・ボタンの並び"),
    Accounts("アカウント管理", "登録済みアカウント・追加・ログアウト"),
    Storage("ストレージ", "画像キャッシュの削除"),
    About("このアプリについて", "バージョン情報"),
}

@Composable
fun SettingsScreen(
    store: UserPreferencesStore,
    activeSession: AccountSession?,
    sessions: List<AccountSession>,
    maintenance: SettingsMaintenanceViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    pushSettings: PushSettingsViewModel? = null,
) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = SettingsPage.Root.name,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220)) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) },
    ) {
        SettingsPage.entries.forEach { page ->
            composable(page.name) {
                SettingsPageContent(
                    page = page,
                    onPage = { nav.navigate(it.name) { launchSingleTop = true } },
                    maintenance = maintenance,
                    store = store,
                    activeSession = activeSession,
                    sessions = sessions,
                    onBack = { if (page == SettingsPage.Root) onBack() else nav.popBackStack() },
                    onLogout = onLogout,
                    onAddAccount = onAddAccount,
                    pushSettings = pushSettings,
                )
            }
        }
    }
}

@Composable
internal fun CacheSettings(viewModel: SettingsMaintenanceViewModel) {
    val state by viewModel.cacheState.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(20.dp)) {
        Text("画像キャッシュ", style = MaterialTheme.typography.titleMedium)
        Text(
            "読み込んだ画像の一時データを削除します。アカウント・設定・下書きは保持されます。画像は表示時に再取得します。",
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { confirm = true }, enabled = !state.isClearing) {
            Text(if (state.isClearing) "削除中…" else "キャッシュを削除")
        }
        state.message?.let { Text(it, Modifier.padding(top = 12.dp)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("画像キャッシュを削除しますか？") },
            text = { Text("アカウント・設定・下書きは削除されません。画像の再取得には通信が発生します。") },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.clearCache() }) { Text("削除") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
        )
    }
}
