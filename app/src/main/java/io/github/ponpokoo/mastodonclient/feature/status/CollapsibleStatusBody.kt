package io.github.ponpokoo.mastodonclient.feature.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal const val CollapsedStatusMaxLines = 12

/** Only post bodies opt in; profile/about text and conversation detail keep their existing path. */
@Composable
internal fun CollapsibleStatusBody(
    accountKey: String,
    filterScope: String,
    timelineId: String,
    statusId: String,
    contentHtml: String,
    modifier: Modifier = Modifier,
    content: @Composable (maxLines: Int, onTextLayout: (TextLayoutResult) -> Unit) -> Unit,
) {
    // Validate restored values explicitly: rememberSaveable inputs alone don't validate restoration.
    // Hashing happens only when saving/restoring an expanded row, never during ordinary rendering.
    val saver = remember(accountKey, filterScope, timelineId, statusId, contentHtml) {
        fun identity() = listOf(accountKey, filterScope, timelineId, statusId, contentDigest(contentHtml))
        Saver<MutableState<Boolean>, List<String>>(
            save = { if (it.value) identity() else null },
            restore = { if (it == identity()) mutableStateOf(true) else null },
        )
    }
    var expanded by rememberSaveable(accountKey, filterScope, timelineId, statusId, contentHtml, saver = saver) {
        mutableStateOf(false)
    }
    val overflowing = remember { mutableStateOf(false) }
    val onLayout = remember {
        { result: TextLayoutResult ->
            overflowing.value = result.didOverflowHeight || result.lineCount > CollapsedStatusMaxLines
        }
    }
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    SubcomposeLayout(modifier.bringIntoViewRequester(requester)) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = subcompose("body") {
            Box { content(if (expanded) Int.MAX_VALUE else CollapsedStatusMaxLines, onLayout) }
        }.single().measure(loose)
        // Read after measuring the text: first placement already includes the correct button.
        // Only a change across the boundary invalidates measurement, never body composition.
        // This also handles font/emoji remeasurement with unchanged outer constraints.
        val toggle = if (!overflowing.value) null else subcompose("toggle") {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val focused by interaction.collectIsFocusedAsState()
            Box(
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).widthIn(min = 48.dp)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                        if (!expanded) expanded = true else scope.launch {
                            // Bring the start back before shrinking a row that fills the viewport.
                            // The standard parent scroll cancels when the user takes over.
                            requester.bringIntoView(Rect(0f, 0f, 1f, 1f))
                            expanded = false
                        }
                    }
                    .testTag("status_body_toggle").semantics {
                        stateDescription = if (expanded) "本文を展開中" else "本文を省略中"
                    }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (expanded) "閉じる" else "続きを読む",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.background(
                        if (pressed || focused) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else Color.Transparent,
                        RoundedCornerShape(3.dp),
                    ),
                )
            }
        }.single().measure(loose)
        layout(constraints.constrainWidth(maxOf(body.width, toggle?.width ?: 0)),
            constraints.constrainHeight(body.height + (toggle?.height ?: 0))) {
            body.placeRelative(0, 0)
            toggle?.placeRelative(0, body.height)
        }
    }
}

private fun contentDigest(content: String): String =
    MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
