package io.github.ponpokoo.mastodonclient.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.ponpokoo.mastodonclient.feature.common.SearchInput

/** The same input survives search/explore mode changes; composition remains local to the editor. */
@Composable
internal fun SearchBar(
    query: String,
    active: Boolean,
    sessionKey: String?,
    onQueryChanged: (String) -> Unit,
    onEnterSearch: () -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    focusRequester: FocusRequester = remember(sessionKey) { FocusRequester() },
) {
    var value by remember(sessionKey) { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    var acceptingEdits by remember(sessionKey) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(sessionKey) {
        // An account change also ends the editor/IME session, including unconfirmed text.
        focusManager.clearFocus()
        keyboard?.hide()
    }
    // Active preserves search results; only explicit input actions should start editing.
    LaunchedEffect(query, sessionKey) {
        if (value.text != query) value = TextFieldValue(query, TextRange(query.length))
    }
    val back = {
        acceptingEdits = false
        value = value.copy(composition = null)
        focusManager.clearFocus()
        keyboard?.hide()
        onBack()
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    BackHandler(enabled = active && isVisible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED), onBack = back)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                modifier = Modifier.testTag(if (active) "search_back" else "search_open"),
                onClick = {
                    if (active) back() else {
                        acceptingEdits = true
                        onEnterSearch()
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                },
            ) {
                Icon(if (active) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Search,
                    contentDescription = if (active) "探索に戻る" else "検索を開く")
            }
            SearchInput(
                value = value,
                onValueChange = { next ->
                    // Ignore a late IME commit after clear/back has closed this editing session.
                    if (acceptingEdits) { value = next; onQueryChanged(next.text) }
                },
                modifier = Modifier.weight(1f),
                inputModifier = Modifier.padding(vertical = 12.dp)
                    .focusRequester(focusRequester).onFocusChanged {
                        if (it.isFocused) { acceptingEdits = true; onEnterSearch() }
                    }.testTag("search_input"),
                placeholder = "Mastodon を検索",
                showClear = active && value.text.isNotEmpty(),
                clearButtonModifier = Modifier.testTag("search_clear"),
                clearContentDescription = "入力を消して探索に戻る",
                onClear = {
                    acceptingEdits = false
                    value = TextFieldValue("")
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onClear()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (value.composition == null && value.text.isNotBlank()) {
                        acceptingEdits = false
                        focusManager.clearFocus()
                        keyboard?.hide()
                        onSearch()
                    }
                }),
            )
        }
    }
}
