package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

/** Confirmation belongs to the exact credentials shown, never a later active account. */
@Composable
fun AccountRemovalButton(account: AccountSession?, onRemove: (AccountSession) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    var confirming by remember(account?.sessionId, account?.instanceUrl, account?.accountId,
        account?.accessToken, account?.scopes) { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, enabled = account != null && enabled,
        modifier = modifier.testTag("remove_account")) {
        Text("現在のアカウントをこのアプリから削除", color = MaterialTheme.colorScheme.error)
    }
    if (confirming && account != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("アカウントを削除しますか？") },
            text = {
                androidx.compose.foundation.layout.Column {
                    val displayName = account.displayName.ifBlank { account.username }
                    val username = "@${account.username}"
                    Text(if (displayName == account.username) username else "$displayName ($username)")
                    Text(account.instanceUrl)
                    Text("アプリから登録を削除します。")
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRemove(account) }, enabled = enabled,
                    modifier = Modifier.testTag("confirm_account_removal")) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("キャンセル") } },
        )
    }
}
