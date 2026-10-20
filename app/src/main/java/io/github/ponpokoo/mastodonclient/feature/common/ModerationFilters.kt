package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import io.github.ponpokoo.mastodonclient.feature.profile.*
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailUiState
import io.github.ponpokoo.mastodonclient.feature.search.*
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineUiState

internal fun TimelineStatus.matchesWordMute(words: List<String>): Boolean {
    if (words.isEmpty()) return false
    val text = moderationPlainText(contentHtml)
    return words.any { it.isNotBlank() && (text.contains(it, ignoreCase = true) || spoilerText.contains(it, ignoreCase = true)) }
}

internal fun moderationPlainText(html: String): String = if ('<' in html || '&' in html)
    androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
else html

private fun TimelineStatus.present(m: AccountModerationState, s: AccountSession, context: String?): TimelineStatus =
    if (!m.applyServerFilters || context == null) this else copy(filterDisplay = filterPresentation(context, current = m.serverFilters[s.sessionId]), filterScope = s.sessionId + s.instanceUrl)
private fun List<TimelineStatus>.visible(m: AccountModerationState, s: AccountSession, context: String? = null) =
    filterNot { m.hides(s, it) || it.matchesWordMute(m.wordMutes[s.sessionId].orEmpty()) }
        .map { it.present(m, s, context) }.filterNot { m.applyServerFilters && context != null && it.filterDisplay.hidden }
private fun UserProfile.visible(m: AccountModerationState, s: AccountSession) =
    copy(statuses = statuses.visible(m, s, "account"), pinnedStatuses = pinnedStatuses.visible(m, s, "account"))
private fun Map<ProfileStatusTab, ProfileTabUiState>.visible(m: AccountModerationState, s: AccountSession) =
    mapValues { (_, tab) -> tab.copy(statuses = tab.statuses.visible(m, s, "account")) }

internal fun TimelineUiState.withModeration(m: AccountModerationState, s: AccountSession): TimelineUiState {
    val visible = statuses.visible(m, s, if (selectedFeed == TimelineFeed.Home) "home" else "public")
    val ids = visible.mapTo(mutableSetOf()) { it.timelineId }
    return copy(statuses = visible, unseenStreamIds = unseenStreamIds.intersect(ids))
}

internal fun NotificationsUiState.withModeration(m: AccountModerationState, s: AccountSession): NotificationsUiState {
    fun present(rows: List<TimelineNotification>) = rows.filterNot {
        m.hides(s, it) || it.status?.matchesWordMute(m.wordMutes[s.sessionId].orEmpty()) == true
    }.map { notification ->
        notification.copy(status = notification.status?.present(m, s, "notifications"))
    }.filterNot { m.applyServerFilters && it.status?.filterDisplay?.hidden == true }
    val visible = present(notifications)
    val filteredLists = lists.mapValues { (_, list) -> list.copy(notifications = present(list.notifications)) }
    if (visible == notifications && filteredLists == lists) return this
    val ids = (visible + filteredLists.values.flatMap { it.notifications }).mapTo(mutableSetOf()) { it.id }
    val originalIds = (notifications + lists.values.flatMap { it.notifications }).mapTo(mutableSetOf()) { it.id }
    if (ids == originalIds) return copy(notifications = visible, lists = filteredLists)
    val pending = pendingNewNotificationIds.intersect(ids)
    return copy(notifications = visible, lists = filteredLists, pendingNewNotificationIds = pending,
        highlightedNotificationIds = highlightedNotificationIds.intersect(ids),
        refreshResult = refreshResult?.copy(hasNewNotifications = pending.isNotEmpty()),
        shownNewNotice = if (pending.isEmpty()) null else shownNewNotice?.copy(count = pending.size))
}

internal fun ProfileUiState.withModeration(m: AccountModerationState, s: AccountSession): ProfileUiState {
    val relationship = profile?.author?.id?.let { m.relationship(s, it) } ?: relationship
    if (relationship?.let { it.muting || it.blocking } == true) {
        // An empty, suppressed profile must not keep fetching every older page.
        val tabs = ProfileStatusTab.entries.associateWith { tab ->
            (profileTabs[tab] ?: ProfileTabUiState()).let { it.copy(statuses = it.statuses.visible(m, s), nextMaxId = null,
                endReached = true, isLoaded = true, isLoading = false, isRefreshing = false,
                isLoadingMore = false, isPullRefreshing = false, error = null) }
        }
        return copy(profile = profile?.visible(m, s)?.copy(
            nextMaxId = null, endReached = true), profileTabs = tabs, relationship = relationship,
            isLoadingProfile = false, isRefreshingProfile = false, isLoadingMoreProfile = false)
    }
    return copy(profile = profile?.visible(m, s), profileTabs = profileTabs.visible(m, s), relationship = relationship)
}
internal fun SavedTimelinesUiState.withModeration(m: AccountModerationState, s: AccountSession, context: String? = null) =
    copy(statuses = statuses.visible(m, s, context))
internal fun HashtagTimelineUiState.withModeration(m: AccountModerationState, s: AccountSession) =
    copy(statuses = statuses.visible(m, s))
internal fun SearchUiState.withModeration(m: AccountModerationState, s: AccountSession) =
    copy(tabs = tabs.mapValues { (_, tab) -> tab.copy(results = tab.results?.let {
        it.copy(statuses = it.statuses.visible(m, s))
    }) })
internal fun ExploreUiState.withModeration(m: AccountModerationState, s: AccountSession) =
    copy(tabs = tabs.mapValues { (_, tab) -> tab.copy(page = tab.page?.let {
        it.copy(statuses = it.statuses.visible(m, s))
    }) })
internal fun StatusDetailUiState.withModeration(m: AccountModerationState, s: AccountSession): StatusDetailUiState {
    val current = detail ?: return this
    if (m.hides(s, current.status)) return copy(detail = null, isLoading = false,
        errorMessage = "ミュートまたはブロック中のアカウントの投稿です")
    if (current.status.matchesWordMute(m.wordMutes[s.sessionId].orEmpty())) return copy(detail = null, isLoading = false,
        errorMessage = "ワードミュートに一致する投稿です")
    val status = current.status.present(m, s, "thread")
    if (m.applyServerFilters && status.filterDisplay.hidden) return copy(detail = null, isLoading = false, errorMessage = "フィルターで非表示の投稿です")
    return copy(detail = current.copy(status = status, ancestors = current.ancestors.visible(m, s, "thread"), descendants = current.descendants.visible(m, s, "thread")))
}
