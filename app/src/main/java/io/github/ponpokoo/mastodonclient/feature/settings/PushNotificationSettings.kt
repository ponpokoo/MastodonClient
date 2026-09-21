package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*

@Composable
internal fun PushNotificationSettings(viewModel: PushSettingsViewModel, sessions: List<AccountSession>) {
    val states by viewModel.states.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingPermission by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.let { if (granted) viewModel.setEnabled(it, true) else viewModel.notificationPermissionDenied() }
        pendingPermission = null
    }
    Column(Modifier.fillMaxWidth().padding(20.dp).testTag("push_settings")) {
        Text("リアルタイム通知", style = MaterialTheme.typography.titleMedium)
        Text("アカウントごとに、バックグラウンドのプッシュ通知を設定します。", style = MaterialTheme.typography.bodySmall)
        sessions.forEach { session ->
            val state = states[session.sessionId] ?: PushControlState()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("@${session.username}")
                    Text(session.instanceUrl, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.enabled, onCheckedChange = { enabled ->
                    if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingPermission = session.sessionId
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.setEnabled(session.sessionId, enabled)
                },
                    enabled = !busy && state.status !in setOf(PushControlStatus.REGISTERING, PushControlStatus.REMOVING) &&
                        (state.status != PushControlStatus.PREPARING || state.enabled),
                    modifier = Modifier.testTag("push_switch_${session.sessionId}").semantics { contentDescription = "@${session.username} のリアルタイム通知" })
            }
            Text(state.message ?: when (state.status) {
                PushControlStatus.PREPARING -> "準備中：通知サービスへの接続設定が完了していません。"
                PushControlStatus.OFF -> "オフ"
                PushControlStatus.NEEDS_AUTH -> "通知の権限を取得するため、再認証が必要です。"
                PushControlStatus.REGISTERING -> "登録中…"
                PushControlStatus.ACTIVE -> "有効（端末の通知許可と通信状態に従います）"
                PushControlStatus.REMOVING -> "解除待ち"
                PushControlStatus.ERROR -> "登録できませんでした。再試行してください。"
            }, style = MaterialTheme.typography.bodySmall)
            if (state.status == PushControlStatus.NEEDS_AUTH) TextButton(onClick = { viewModel.authorize(session.sessionId) }, enabled = !busy) { Text("再認証する") }
            if (state.status in setOf(PushControlStatus.ERROR, PushControlStatus.REMOVING)) TextButton(onClick = viewModel::retry) { Text("再試行") }
        }
        // Includes cleanup of accounts already logged out, without retaining their profile in UI.
        if (states.any { (id, value) -> sessions.none { it.sessionId == id } && value.status == PushControlStatus.REMOVING }) {
            Text("ログアウト済みアカウントの通知を解除待ちです。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::retry) { Text("解除を再試行") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = viewModel::retry) { Text("再試行") } }
    }
}
