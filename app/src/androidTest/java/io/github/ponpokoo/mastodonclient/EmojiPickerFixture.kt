package io.github.ponpokoo.mastodonclient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerConnection
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerDataSource
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerState

/** Offline connection; production connections are created by the owning ViewModel. */
@Composable
internal fun rememberTestEmojiPickerConnection(
    source: EmojiPickerDataSource,
    sessionKey: String = "fixture",
): EmojiPickerConnection {
    val scope = rememberCoroutineScope()
    return remember(scope, sessionKey) { EmojiPickerConnection(sessionKey) { EmojiPickerState(scope, source) } }
}
