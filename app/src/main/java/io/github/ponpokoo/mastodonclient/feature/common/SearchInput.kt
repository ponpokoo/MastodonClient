package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Shared editor and clear affordance; callers own focus, navigation, and query state. */
@Composable
internal fun SearchInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    clearButtonModifier: Modifier = Modifier,
    showClear: Boolean = value.text.isNotEmpty(),
    clearContentDescription: String = "検索文字を消去",
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).then(inputModifier),
            singleLine = true,
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { editor ->
                Box {
                    if (value.text.isEmpty()) Text(
                        placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = textStyle,
                    )
                    editor()
                }
            },
        )
        if (showClear) {
            IconButton(onClick = onClear, modifier = clearButtonModifier) {
                Icon(Icons.Outlined.Close, contentDescription = clearContentDescription)
            }
        }
    }
}
