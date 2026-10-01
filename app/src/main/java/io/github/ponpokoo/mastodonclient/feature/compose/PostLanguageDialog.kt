package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale

@Composable
internal fun PostLanguageDialog(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val languages = remember {
        Locale.getISOLanguages().map { code ->
            code to Locale.forLanguageTag(code).getDisplayLanguage(Locale.JAPANESE)
        }.sortedBy { it.second }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth()) {
                Text("投稿の言語", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                LanguageOption("デフォルト", selected == null) { onSelect(null) }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(languages, key = { it.first }) { (code, label) ->
                        LanguageOption("$label ($code)", selected == code) { onSelect(code) }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(8.dp)) {
                    Text("閉じる")
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}
