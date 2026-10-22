package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountModerationKey
import io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.filterPresentation
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import java.time.Instant

/** ViewModel-owned, synchronous cache: immediate moderation reads keep their existing guarantees. */
internal class TimelineModerationCache(
    private val plainText: (String) -> String = ::moderationPlainText,
    private val clock: () -> Instant = Instant::now,
) {
    private var account: AccountModerationKey? = null
    private var display: Rows? = null
    private var cleanup: Rows? = null
    private val texts = boundedCache<String, String>()
    private val matches = boundedCache<Pair<String, String>, Boolean>()
    private var words: List<String> = emptyList()

    private class Rows(
        val source: List<TimelineStatus>,
        val moderation: AccountModerationState,
        val context: String,
        val evaluatedAt: Instant,
        val expiresAt: Instant?,
        val visible: List<TimelineStatus>,
        val ids: Set<String>,
    )

    fun clear() {
        account = null
        display = null
        cleanup = null
        texts.clear()
        matches.clear()
        words = emptyList()
    }

    fun apply(state: TimelineUiState, moderation: AccountModerationState, session: AccountSession): TimelineUiState {
        val key = AccountModerationKey(session.sessionId, session.instanceUrl, session.accountId)
        if (account != key) {
            clear()
            account = key
        }
        val context = if (state.selectedFeed == TimelineFeed.Home) "home" else "public"
        val now = clock()
        val previous = if (moderation.applyServerFilters) display else cleanup
        val rows = previous?.takeIf {
            it.source === state.statuses && it.moderation == moderation && it.context == context &&
                now >= it.evaluatedAt && (it.expiresAt == null || now < it.expiresAt)
        } ?: filter(state.statuses, moderation, session, context, now).also {
            if (moderation.applyServerFilters) display = it else cleanup = it
        }
        return state.copy(statuses = rows.visible, unseenStreamIds = state.unseenStreamIds.intersect(rows.ids),
            unseenRefreshIds = state.unseenRefreshIds.intersect(rows.ids))
    }

    private fun filter(source: List<TimelineStatus>, moderation: AccountModerationState,
        session: AccountSession, context: String, now: Instant): Rows {
        val currentWords = moderation.wordMutes[session.sessionId].orEmpty()
        // Cleanup deliberately omits local word mutes; do not evict presentation matches for it.
        if (currentWords.isNotEmpty() && words != currentWords) {
            words = currentWords.toList()
            matches.clear()
        }
        val currentFilters = moderation.serverFilters[session.sessionId]
        var expiry: Instant? = null
        val visible = buildList {
            for (status in source) {
                if (moderation.hides(session, status) || matchesWord(status, currentWords)) continue
                val presented = if (!moderation.applyServerFilters) status else {
                    for (result in status.filtered.orEmpty()) {
                        val rule = if (currentFilters == null) result.filter else currentFilters.firstOrNull { it.id == result.filter.id }
                        val date = rule?.takeIf { context in it.context }?.expiresAt?.let {
                            runCatching { Instant.parse(it) }.getOrNull()
                        }
                        if (date != null && date > now && (expiry == null || date < expiry)) expiry = date
                    }
                    val presentation = status.filterPresentation(context, now, currentFilters)
                    val scope = session.sessionId + session.instanceUrl
                    if (status.filterDisplay == presentation && status.filterScope == scope) status
                    else status.copy(filterDisplay = presentation, filterScope = scope)
                }
                if (!moderation.applyServerFilters || !presented.filterDisplay.hidden) add(presented)
            }
        }
        return Rows(source, moderation, context, now, expiry, visible, visible.mapTo(mutableSetOf()) { it.timelineId })
    }

    private fun matchesWord(status: TimelineStatus, currentWords: List<String>): Boolean {
        if (currentWords.isEmpty()) return false
        return matches.getOrPut(status.contentHtml to status.spoilerText) {
            val text = texts.getOrPut(status.contentHtml) { plainText(status.contentHtml) }
            currentWords.any { it.isNotBlank() &&
                (text.contains(it, ignoreCase = true) || status.spoilerText.contains(it, ignoreCase = true)) }
        }
    }
}

private fun <K, V> boundedCache(): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > 500
}
