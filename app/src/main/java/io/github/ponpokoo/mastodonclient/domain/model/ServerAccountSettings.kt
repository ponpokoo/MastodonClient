package io.github.ponpokoo.mastodonclient.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable enum class ServerSetting { Preferences, Mutes, Blocks, Filters }
@Serializable data class SettingFetch(val loading: Boolean = false, val fetchedAt: Long? = null, val error: String? = null, val unsupported: Boolean = false)
@Serializable data class PostingDefaults(val visibility: String? = null, val quotePolicy: String? = null,
    val language: String? = null, val languageKnown: Boolean = false, val quotePolicySupported: Boolean = false)
@Serializable data class ServerAccountSettings(val instanceUrl: String, val accountId: String,
    val defaults: PostingDefaults? = null, val mutes: List<ModerationAccount>? = null,
    val blocks: List<ModerationAccount>? = null, val filters: List<ServerFilter>? = null,
    val fetch: Map<ServerSetting, SettingFetch> = emptyMap(), val displayPending: Boolean = false,
    val pendingCreation: FilterEdit? = null, @kotlinx.serialization.Transient val expiryRevision: Long = 0,
    @kotlinx.serialization.Transient val displayChecks: Map<String, Boolean?> = emptyMap()) {
    fun belongsTo(session: AccountSession) = instanceUrl.trimEnd('/') == session.instanceUrl.trimEnd('/') && accountId == session.accountId
}
@Serializable data class ServerFilter(val id: String, val title: String, val context: Set<String> = emptySet(),
    val expiresAt: String? = null, val action: String = "warn", val keywords: List<FilterKeyword> = emptyList(),
    val statuses: List<FilterStatus> = emptyList()) {
    fun applies(context: String, now: Instant): Boolean = context in this.context &&
        (expiresAt == null || runCatching { Instant.parse(expiresAt).isAfter(now) }.getOrDefault(true))
}
@Serializable data class FilterKeyword(val id: String? = null, val keyword: String, val wholeWord: Boolean = false)
@Serializable data class FilterStatus(val id: String, val statusId: String)
@Serializable data class StatusFilterResult(val filter: ServerFilter, val keywordMatches: List<String>? = null, val statusMatches: List<String>? = null)
@Serializable data class FilterEdit(val title: String, val context: Set<String>, val action: String,
    val keywords: List<FilterKeyword>, val expiresIn: Long? = null, val changeExpiry: Boolean = false)
data class FilterPresentation(val hidden: Boolean = false, val warnings: List<ServerFilter> = emptyList(), val mediaWarnings: List<ServerFilter> = emptyList())

/** Shared server-match interpretation. Local word mutes remain independent. */
fun TimelineStatus.filterPresentation(context: String, now: Instant = Instant.now(), current: List<ServerFilter>? = null): FilterPresentation {
    val active = filtered.orEmpty().mapNotNull { result ->
        // Removed/expired rules can stop applying immediately. Newly matching keywords need fresh posts.
        val filter = if (current == null) result.filter else current.firstOrNull { it.id == result.filter.id }
        filter?.takeIf { it.applies(context, now) }
    }.distinctBy { it.id }
    return FilterPresentation(active.any { it.action == "hide" },
        active.filter { it.action != "hide" && it.action != "blur" }, active.filter { it.action == "blur" })
}
