package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun insertHashtag(value: TextFieldValue): TextFieldValue {
    val cursor = value.selection.end
    return TextFieldValue(value.text.replaceRange(cursor, cursor, "#"), TextRange(cursor + 1))
}
