package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerTextEditingTest {
    @Test fun insertsHashAtCursorWithoutRemovingExistingText() {
        val result = insertHashtag(TextFieldValue("前 後", TextRange(2)))
        assertEquals("前 #後", result.text)
        assertEquals(TextRange(3), result.selection)
        assertEquals("カナ#", insertHashtag(TextFieldValue("カナ", TextRange(0, 2))).text)
    }
}
