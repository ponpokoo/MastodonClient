package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.ponpokoo.mastodonclient.domain.model.ServerFilter

@Composable
internal fun FilterWarning(
    filters: List<ServerFilter>,
    expanded: Boolean,
    onToggle: () -> Unit,
    media: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    ContentWarning(
        expanded = expanded,
        onToggle = onToggle,
        modifier = Modifier.testTag("filter_warning"),
        toggleModifier = Modifier.testTag("filter_reveal"),
        showLabel = if (media) "メディアを表示" else "内容を表示",
        hideLabel = if (media) "メディアを隠す" else "内容を隠す",
    ) {
        Text(
            "フィルター：" + filters.joinToString("、") { it.title.ifBlank { "名前なし" } },
            style = textStyle,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
