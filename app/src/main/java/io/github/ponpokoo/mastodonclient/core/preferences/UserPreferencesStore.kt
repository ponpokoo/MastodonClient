package io.github.ponpokoo.mastodonclient.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

@Serializable enum class FontSizePreset { Small, Standard, Large, ExtraLarge }
@Serializable enum class LineSpacingPreset { Compact, Standard, Relaxed }
@Serializable enum class AvatarIconSize { Small, Standard, Large }
@Serializable enum class ActionIconSize { Small, Standard, Large }
@Serializable enum class ThumbnailSize { Compact, Standard, Large }
@Serializable enum class AutoplayPolicy { Always, WifiOnly, Never }
@Serializable enum class StreamingPolicy { On, WifiOnly, Off }
@Serializable enum class ThemeMode { Light, Dark, System }
@Serializable enum class StatusAction { Reply, Boost, Favourite, Reaction, Bookmark, Share }
@Serializable enum class ComposerAction { Media, Poll, Emoji, ContentWarning, Mention, SaveDraft, DeleteDraft }

@Serializable
enum class PostVisibility(val apiValue: String) {
    Public("public"),
    Unlisted("unlisted"),
    FollowersOnly("private"),
    Direct("direct");

    companion object {
        fun fromApi(value: String?) = entries.firstOrNull { it.apiValue == value } ?: Public
    }
}

@Serializable
data class TimelineDisplayPreferences(
    val fontSize: FontSizePreset = FontSizePreset.Standard,
    val lineSpacing: LineSpacingPreset = LineSpacingPreset.Standard,
    val avatarIconSize: AvatarIconSize = AvatarIconSize.Standard,
    val actionIconSize: ActionIconSize = ActionIconSize.Small,
    val actionOrder: List<StatusAction> = listOf(
        StatusAction.Reply,
        StatusAction.Boost,
        StatusAction.Favourite,
        StatusAction.Reaction,
        StatusAction.Bookmark,
        StatusAction.Share,
    ),
    val hiddenActions: Set<StatusAction> = setOf(StatusAction.Bookmark),
    val showCounts: Boolean = true,
    val thumbnailSize: ThumbnailSize = ThumbnailSize.Compact,
)

@Serializable
data class AccountPreferences(
    val defaultVisibility: PostVisibility = PostVisibility.Public,
    val streaming: StreamingPolicy = StreamingPolicy.On,
)

@Serializable
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
    val openLinksInApp: Boolean = true,
    val timelineDisplay: TimelineDisplayPreferences = TimelineDisplayPreferences(),
    val gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    val videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
    val pauseStreamingInBackground: Boolean = true,
    val altTextReminder: Boolean = true,
    val composerActionOrder: List<ComposerAction> = ComposerAction.entries,
    val accountPreferences: Map<String, AccountPreferences> = emptyMap(),
) {
    fun forAccount(sessionId: String?) = sessionId?.let(accountPreferences::get) ?: AccountPreferences()
}

@Serializable
data class ComposeDraft(
    val key: String,
    val sessionId: String,
    val replyToId: String? = null,
    val text: String = "",
    val spoilerText: String = "",
    val visibility: PostVisibility = PostVisibility.Public,
    val sensitive: Boolean = false,
    val attachmentUris: List<String> = emptyList(),
    val attachmentFileNames: Map<String, String> = emptyMap(),
    val attachmentMimeTypes: Map<String, String> = emptyMap(),
    val attachmentDescriptions: Map<String, String> = emptyMap(),
    val pollOptions: List<String> = emptyList(),
    val pollExpiresInSeconds: Long = 86_400,
    val pollMultiple: Boolean = false,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

class UserPreferencesStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val dataStore = context.applicationContext.userPreferencesDataStore
    private val composeBuffers = MutableStateFlow<Map<String, ComposeDraft>>(emptyMap())

    val preferences: Flow<AppPreferences> = dataStore.data.map { stored ->
        stored[APP_PREFERENCES]?.let { encoded ->
            runCatching { json.decodeFromString<AppPreferences>(encoded) }.getOrNull()
        } ?: AppPreferences(openLinksInApp = stored[LEGACY_OPEN_LINKS_IN_APP] ?: true)
    }

    val openLinksInApp: Flow<Boolean> = preferences.map { it.openLinksInApp }

    suspend fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    suspend fun setOpenLinksInApp(enabled: Boolean) = update { it.copy(openLinksInApp = enabled) }
    suspend fun setTimelineDisplay(value: TimelineDisplayPreferences) = update { it.copy(timelineDisplay = value) }
    suspend fun setGifAutoplay(value: AutoplayPolicy) = update { it.copy(gifAutoplay = value) }
    suspend fun setVideoAutoplay(value: AutoplayPolicy) = update { it.copy(videoAutoplay = value) }
    suspend fun setPauseStreamingInBackground(enabled: Boolean) =
        update { it.copy(pauseStreamingInBackground = enabled) }
    suspend fun setAltTextReminder(enabled: Boolean) = update { it.copy(altTextReminder = enabled) }
    suspend fun setComposerActionOrder(value: List<ComposerAction>) = update {
        it.copy(composerActionOrder = value.distinct() + ComposerAction.entries.filterNot(value::contains))
    }
    suspend fun setAccountPreferences(sessionId: String, value: AccountPreferences) = update {
        it.copy(accountPreferences = it.accountPreferences + (sessionId to value))
    }

    val drafts: Flow<List<ComposeDraft>> = dataStore.data.map { stored ->
        stored[DRAFTS]?.let { encoded ->
            runCatching { json.decodeFromString<List<ComposeDraft>>(encoded) }.getOrNull()
        }.orEmpty()
    }

    suspend fun saveDraft(draft: ComposeDraft) {
        dataStore.edit { stored ->
            val drafts = stored[DRAFTS]?.let {
                runCatching { json.decodeFromString<List<ComposeDraft>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.key == draft.key } + draft
            stored[DRAFTS] = json.encodeToString(drafts)
        }
    }

    suspend fun deleteDraft(key: String) {
        dataStore.edit { stored ->
            val drafts = stored[DRAFTS]?.let {
                runCatching { json.decodeFromString<List<ComposeDraft>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.key == key }
            if (drafts.isEmpty()) stored.remove(DRAFTS) else stored[DRAFTS] = json.encodeToString(drafts)
        }
    }

    fun getComposeBuffer(key: String): ComposeDraft? = composeBuffers.value[key]

    fun retainComposeBuffer(draft: ComposeDraft) {
        composeBuffers.update { it + (draft.key to draft) }
    }

    fun removeComposeBuffer(key: String) {
        composeBuffers.update { it - key }
    }

    private suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        dataStore.edit { stored ->
            val current = stored[APP_PREFERENCES]?.let {
                runCatching { json.decodeFromString<AppPreferences>(it) }.getOrNull()
            } ?: AppPreferences(openLinksInApp = stored[LEGACY_OPEN_LINKS_IN_APP] ?: true)
            stored[APP_PREFERENCES] = json.encodeToString(transform(current))
            stored.remove(LEGACY_OPEN_LINKS_IN_APP)
        }
    }

    private companion object {
        val APP_PREFERENCES = stringPreferencesKey("app_preferences_v2")
        val DRAFTS = stringPreferencesKey("compose_drafts")
        val LEGACY_OPEN_LINKS_IN_APP = booleanPreferencesKey("open_links_in_app")
    }
}
