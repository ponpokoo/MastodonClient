package io.github.ponpokoo.mastodonclient.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.feature.web.WebLinkLauncher
import kotlinx.coroutines.launch

internal const val USAGE_GUIDE_URL = "https://ponpokoo.github.io/nagisa-policy/guide/index.html"

/** Wait for persisted preferences before deciding whether to show the first-use guide. */
@Composable
internal fun FirstRunGuide(
    store: UserPreferencesStore,
    eligible: Boolean,
    onSettings: (String) -> Unit,
) {
    val preferences by store.preferences.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val openUsageGuide = rememberUsageGuideOpener(preferences?.openLinksInApp ?: true)
    if (eligible && preferences?.welcomeGuideCompleted == false) {
        WelcomeGuideDialog(
            onDismiss = { scope.launch { store.completeWelcomeGuide() } },
            onNotifications = { onSettings(SettingsPage.Notifications.name) },
            onTextSize = { onSettings(SettingsPage.Timeline.name) },
            onTheme = { onSettings(SettingsPage.Appearance.name) },
            onUsageGuide = openUsageGuide,
        )
    }
}

@Composable
internal fun rememberUsageGuideOpener(inApp: Boolean): () -> Unit {
    val context = LocalContext.current
    val launcher = remember(context) { WebLinkLauncher(context) }
    return {
        if (!launcher.open(USAGE_GUIDE_URL.toUri(), inApp = inApp)) {
            Toast.makeText(context, "リンクを開けるブラウザーが見つかりません", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun WelcomeGuideDialog(
    onDismiss: () -> Unit,
    onNotifications: () -> Unit,
    onTextSize: () -> Unit,
    onTheme: () -> Unit,
    onUsageGuide: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().testTag("welcome_guide"),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Nagisaへようこそ", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                // Keep the completion action reachable on small screens and with large system fonts.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text("Push通知は設定からオンにしてください", style = MaterialTheme.typography.bodyLarge)
                    GuideLink("通知を設定する", "welcome_notifications", onNotifications)
                    Text(
                        "アカウント・サーバーごとに対応が異なります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("文字サイズやテーマを調整できます", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuideLink("文字サイズ", "welcome_text_size", onTextSize)
                        GuideLink("テーマ", "welcome_theme", onTheme)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("使い方のQ＆Aは以下のサイトで確認できます", style = MaterialTheme.typography.bodyLarge)
                    GuideLink("使い方を見る", "welcome_usage", onUsageGuide, external = true)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("welcome_start")) {
                    Text("はじめる")
                }
            }
        }
    }
}

@Composable
private fun GuideLink(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    external: Boolean = false,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = if (external) "サイトを開く" else null,
            modifier = Modifier.size(18.dp),
        )
    }
}
