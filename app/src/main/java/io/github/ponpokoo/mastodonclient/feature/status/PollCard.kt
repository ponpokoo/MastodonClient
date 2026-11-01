package io.github.ponpokoo.mastodonclient.feature.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ponpokoo.mastodonclient.domain.model.StatusPoll
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class PollVoteUiState(val sessionKey: String? = null, val pendingIds: Set<String> = emptySet())
internal val LocalPollVoteState = staticCompositionLocalOf { PollVoteUiState() }

@Composable
internal fun PollCard(poll: StatusPoll, onVote: ((Set<Int>) -> Unit)?, voting: Boolean = false) {
    val shared = LocalPollVoteState.current
    val isVoting = voting || poll.id in shared.pendingIds
    var choices by rememberSaveable(shared.sessionKey, poll.id, poll.options.map { it.title }, poll.ownVotes) {
        mutableStateOf(poll.ownVotes)
    }
    var now by remember(poll.expiresAt) { mutableStateOf(Instant.now()) }
    LifecycleResumeEffect(poll.expiresAt) {
        now = Instant.now()
        onPauseOrDispose { }
    }
    val expiration = remember(poll.expiresAt) { poll.expirationInstant() }
    LaunchedEffect(expiration, now, poll.expired) {
        if (!poll.expired && expiration != null && expiration.isAfter(now)) {
            // Local deadline only; do not poll the server or invent final results.
            val wait = runCatching { java.time.Duration.between(Instant.now(), expiration).toMillis() }
                .getOrDefault(Long.MAX_VALUE).coerceAtLeast(1L)
            delay(minOf(wait, 60_000L))
            now = Instant.now()
        }
    }
    val ended = poll.hasEnded(now)
    val canVote = !ended && poll.voted != true && onVote != null
    val hidden = poll.options.any { it.votesCount == null }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("status_poll"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("アンケート", style = MaterialTheme.typography.titleSmall)
                if (poll.multiple) Text("複数選択可", style = MaterialTheme.typography.labelMedium)
                if (poll.voted == true) Text("✓ 投票済み", style = MaterialTheme.typography.labelMedium)
            }
            poll.options.forEachIndexed { index, option ->
                val selected = index in choices
                val fraction = poll.resultFraction(option.votesCount)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val input = when {
                        !canVote -> Modifier
                        poll.multiple -> Modifier.toggleable(selected, enabled = !isVoting, role = Role.Checkbox) {
                            choices = choices.toMutableSet().apply { if (!add(index)) remove(index) }
                        }
                        else -> Modifier.selectable(selected, enabled = !isVoting, role = Role.RadioButton) { choices = setOf(index) }
                    }
                    Row(
                        Modifier.fillMaxWidth().then(input).heightIn(min = if (canVote) 48.dp else 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canVote) {
                            if (poll.multiple) Checkbox(checked = selected, onCheckedChange = null, enabled = !isVoting)
                            else RadioButton(selected = selected, onClick = null, enabled = !isVoting)
                            Spacer(Modifier.width(8.dp))
                        }
                        CustomEmojiText(
                            text = option.title, emojis = poll.customEmojis,
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!canVote && index in poll.ownVotes) Text(" ✓", color = MaterialTheme.colorScheme.primary)
                        option.votesCount?.let { count ->
                            Spacer(Modifier.width(8.dp))
                            val label = when {
                                poll.multiple && poll.votersCount != null -> "${count}人"
                                else -> "${count}票"
                            } + (fraction?.let { "（${(it * 100).roundToInt()}%）" } ?: "")
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (fraction != null) Box(
                        Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxSize().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            if (hidden) Text(
                if (ended) "結果はまだ取得されていません" else "終了まで結果は非表示",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canVote) Button(
                onClick = { onVote?.invoke(choices) },
                enabled = choices.isNotEmpty() && !isVoting,
                modifier = Modifier.align(Alignment.End).testTag("poll_vote"),
            ) { Text(if (isVoting) "投票中…" else "投票する") }
            val voteCount = poll.votersCount?.let { "${it}人が投票" } ?: "${poll.votesCount.coerceAtLeast(0)}票"
            Text(
                "${if (ended) "投票終了" else poll.remainingLabel(now) ?: "投票受付中"}・$voteCount",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            expiration?.let {
                val formatter = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.JAPAN) }
                Text("${formatter.format(it.atZone(ZoneId.systemDefault()))} 終了",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
