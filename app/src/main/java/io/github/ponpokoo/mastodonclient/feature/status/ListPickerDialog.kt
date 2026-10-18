package io.github.ponpokoo.mastodonclient.feature.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList

@Composable
internal fun ListPickerDialog(
    lists: List<MastodonList>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("追加するリストを選択") },
        text = {
            when {
                loading -> Text("読み込み中…")
                lists.isEmpty() -> Text("利用できるリストがありません")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxContentHeight)) {
                    items(lists, key = MastodonList::id) { list ->
                        Text(
                            text = list.title,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSelected(list.id) }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
