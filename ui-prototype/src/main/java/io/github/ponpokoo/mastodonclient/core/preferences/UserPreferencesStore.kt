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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

@Serializable enum class FontSizePreset { Small, Standard, Large, ExtraLarge }
@Serializable enum class LineSpacingPreset { Compact, Standard, Relaxed }
@Serializable enum class AvatarIconSize { Small, Standard, Large }
@Serializable enum class AvatarIconShape { Circle, Square }
@Serializable enum class ActionIconSize { Small, Standard, Large }
@Serializable enum class ThumbnailSize { Compact, Standard }
@Serializable enum class AutoplayPolicy { Always, WifiOnly, Never }
@Serializable enum class StreamingPolicy { On, WifiOnly, Off }
@Serializable enum class ThemeMode { Light, Dark, System }
@Serializable enum class StatusAction { Reply, Boost, Favourite, Reaction, Bookmark, Share }
// Keep DeleteDraft as the serialized value so existing toolbar orders load; its UI action now clears only body text.
@Serializable enum class ComposerAction { Media, Poll, Emoji, ContentWarning, Mention, Language, Hashtag, SaveDraft, DeleteDraft }

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
    val avatarIconShape: AvatarIconShape = AvatarIconShape.Circle,
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
    val showReactions: Boolean = true,
    val thumbnailSize: ThumbnailSize = ThumbnailSize.Standard,
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
    val keepPositionOnPullRefresh: Boolean = false,
    val foregroundNotificationsEnabled: Boolean = true,
    val simpleNotificationsEnabled: Boolean = true,
    val simpleNotificationSetupFailed: Boolean = false,
    val altTextReminder: Boolean = true,
    val composerActionOrder: List<ComposerAction> = ComposerAction.entries,
    val hiddenComposerActions: Set<ComposerAction> = emptySet(),
    val accountPreferences: Map<String, AccountPreferences> = emptyMap(),
    val wordMutes: Map<String, List<String>> = emptyMap(),
) {
    fun forAccount(sessionId: String?) = sessionId?.let(accountPreferences::get) ?: AccountPreferences()
}

@Serializable
data class ComposeDraft(
    val key: String,
    val sessionId: String,
    val replyToId: String? = null,
    val quotedStatusId: String? = null,
    val quotedStatusUrl: String? = null,
    val nativeQuote: Boolean = false,
    val text: String = "",
    val spoilerText: String = "",
    val visibility: PostVisibility = PostVisibility.Public,
    val sensitive: Boolean = false,
    val language: String? = null,
    val attachmentUris: List<String> = emptyList(),
    val attachmentFileNames: Map<String, String> = emptyMap(),
    val attachmentMimeTypes: Map<String, String> = emptyMap(),
    val attachmentDescriptions: Map<String, String> = emptyMap(),
    val attachmentMediaIds: Map<String, String> = emptyMap(),
    val pollOptions: List<String> = emptyList(),
    val pollExpiresInSeconds: Long = 86_400,
    val pollMultiple: Boolean = false,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

class UserPreferencesStore(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val isAccountPresent: suspend (String) -> Boolean = { true },
) {
    constructor(context: Context, json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) :
        this(context.applicationContext.userPreferencesDataStore, json, { id ->
            io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore(context).getSessions().any { it.sessionId == id }
        })
    private val composeBuffers = MutableStateFlow<Map<String, ComposeDraft>>(emptyMap())
    private val bufferLock = Any()
    private val removedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private suspend fun canWriteAccount(id: String) = id !in removedAccounts && isAccountPresent(id)

    val preferences: Flow<AppPreferences> = dataStore.data.map { stored ->
        stored[APP_PREFERENCES]?.let { encoded ->
            runCatching { decodeAppPreferences(encoded) }.getOrNull()
        } ?: AppPreferences(openLinksInApp = stored[LEGACY_OPEN_LINKS_IN_APP] ?: true)
    }.map { preferences ->
        preferences.copy(composerActionOrder = preferences.composerActionOrder.distinct() +
            ComposerAction.entries.filterNot(preferences.composerActionOrder::contains))
    }

    val openLinksInApp: Flow<Boolean> = preferences.map { it.openLinksInApp }

    suspend fun editWordMutes(sessionId: String, transform: (List<String>) -> List<String>) = update(sessionId) {
        it.copy(wordMutes = it.wordMutes + (sessionId to transform(it.wordMutes[sessionId].orEmpty())))
    }

    val reactionHistory: Flow<Map<String, List<String>>> = dataStore.data.map { stored ->
        stored[REACTION_HISTORY]?.let { encoded ->
            runCatching { json.decodeFromString<Map<String, List<String>>>(encoded) }.getOrNull()
        }.orEmpty()
    }

    val composerEmojiHistory: Flow<Map<String, List<String>>> = dataStore.data.map { stored ->
        stored[COMPOSER_EMOJI_HISTORY]?.let { encoded ->
            runCatching { json.decodeFromString<Map<String, List<String>>>(encoded) }.getOrNull()
        }.orEmpty()
    }

    suspend fun recordReaction(sessionId: String, emoji: String) {
        dataStore.edit { stored ->
            if (!canWriteAccount(sessionId)) return@edit
            val current = stored[REACTION_HISTORY]?.let { encoded ->
                runCatching { json.decodeFromString<Map<String, List<String>>>(encoded) }.getOrNull()
            }.orEmpty()
            val recent = (listOf(emoji) + current[sessionId].orEmpty().filterNot { it == emoji }).take(20)
            stored[REACTION_HISTORY] = json.encodeToString(current + (sessionId to recent))
        }
    }

    suspend fun setReactionHistory(sessionId: String, emojis: List<String>) {
        dataStore.edit { stored ->
            if (!canWriteAccount(sessionId)) return@edit
            val current = stored[REACTION_HISTORY]?.let { encoded ->
                runCatching { json.decodeFromString<Map<String, List<String>>>(encoded) }.getOrNull()
            }.orEmpty()
            stored[REACTION_HISTORY] = json.encodeToString(current + (sessionId to emojis.distinct().take(20)))
        }
    }

    suspend fun recordComposerEmoji(sessionId: String, emoji: String) {
        dataStore.edit { stored ->
            if (!canWriteAccount(sessionId)) return@edit
            val current = stored[COMPOSER_EMOJI_HISTORY]?.let { encoded ->
                runCatching { json.decodeFromString<Map<String, List<String>>>(encoded) }.getOrNull()
            }.orEmpty()
            val recent = (listOf(emoji) + current[sessionId].orEmpty().filterNot { it == emoji }).take(20)
            stored[COMPOSER_EMOJI_HISTORY] = json.encodeToString(current + (sessionId to recent))
        }
    }

    suspend fun setComposerEmojiHistory(sessionId: String, emojis: List<String>) {
        dataStore.edit { stored ->
            if (!canWriteAccount(sessionId)) return@edit
            val current = stored[COMPOSER_EMOJI_HISTORY]?.let { encoded ->
                runCatching { json.decodeFromString<Map<String, List<String>>>(encoded) }.getOrNull()
            }.orEmpty()
            stored[COMPOSER_EMOJI_HISTORY] = json.encodeToString(current + (sessionId to emojis.distinct().take(20)))
        }
    }

    suspend fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    suspend fun setOpenLinksInApp(enabled: Boolean) = update { it.copy(openLinksInApp = enabled) }
    suspend fun setTimelineDisplay(value: TimelineDisplayPreferences) = update { it.copy(timelineDisplay = value) }
    suspend fun setGifAutoplay(value: AutoplayPolicy) = update { it.copy(gifAutoplay = value) }
    suspend fun setVideoAutoplay(value: AutoplayPolicy) = update { it.copy(videoAutoplay = value) }
    suspend fun setPauseStreamingInBackground(enabled: Boolean) =
        update { it.copy(pauseStreamingInBackground = enabled) }
    suspend fun setKeepPositionOnPullRefresh(enabled: Boolean) =
        update { it.copy(keepPositionOnPullRefresh = enabled) }
    suspend fun setForegroundNotificationsEnabled(enabled: Boolean) =
        update { it.copy(foregroundNotificationsEnabled = enabled) }
    suspend fun setSimpleNotificationsEnabled(enabled: Boolean) =
        update { it.copy(simpleNotificationsEnabled = enabled, simpleNotificationSetupFailed = false) }
    suspend fun setSimpleNotificationSetupFailed(failed: Boolean) =
        update { it.copy(simpleNotificationSetupFailed = failed) }
    suspend fun setAltTextReminder(enabled: Boolean) = update { it.copy(altTextReminder = enabled) }
    suspend fun setComposerActionOrder(value: List<ComposerAction>) = update {
        it.copy(composerActionOrder = value.distinct() + ComposerAction.entries.filterNot(value::contains))
    }
    suspend fun setHiddenComposerActions(value: Set<ComposerAction>) = update {
        it.copy(hiddenComposerActions = value)
    }
    suspend fun setAccountPreferences(sessionId: String, value: AccountPreferences) = update(sessionId) {
        it.copy(accountPreferences = it.accountPreferences + (sessionId to value))
    }

    val drafts: Flow<List<ComposeDraft>> = dataStore.data.map { stored ->
        stored[DRAFTS]?.let { encoded ->
            runCatching { json.decodeFromString<List<ComposeDraft>>(encoded) }.getOrNull()
        }.orEmpty()
    }

    suspend fun saveDraft(draft: ComposeDraft) {
        dataStore.edit { stored ->
            check(canWriteAccount(draft.sessionId)) { "アカウントの登録が削除されています" }
            val drafts = stored[DRAFTS]?.let {
                runCatching { json.decodeFromString<List<ComposeDraft>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.key == draft.key } + draft
            stored[DRAFTS] = json.encodeToString(drafts)
        }
    }

    suspend fun deleteDraft(key: String) {
        dataStore.edit { stored ->
            val allDrafts = stored[DRAFTS]?.let {
                runCatching { json.decodeFromString<List<ComposeDraft>>(it) }.getOrNull()
            }.orEmpty()
            // Preserve ownership of legacy flat files when a saved draft becomes active input.
            allDrafts.firstOrNull { it.key == key }?.let { draft ->
                val legacyFiles = draft.attachmentUris.filter { value ->
                    val uri = runCatching { java.net.URI(value) }.getOrNull()
                    uri?.scheme == "file" && uri.path?.substringBeforeLast('/')?.endsWith("/draft_media") == true
                }
                if (legacyFiles.isNotEmpty()) {
                    val media = stored[DETACHED_MEDIA]?.let { json.decodeFromString<Map<String, Set<String>>>(it) }.orEmpty()
                    stored[DETACHED_MEDIA] = json.encodeToString(media + (draft.sessionId to
                        (media[draft.sessionId].orEmpty() + legacyFiles)))
                }
            }
            val drafts = allDrafts.filterNot { it.key == key }
            if (drafts.isEmpty()) stored.remove(DRAFTS) else stored[DRAFTS] = json.encodeToString(drafts)
        }
    }

    fun getComposeBuffer(key: String): ComposeDraft? = composeBuffers.value[key]?.takeUnless { it.sessionId in removedAccounts }

    fun retainComposeBuffer(draft: ComposeDraft) = synchronized(bufferLock) {
        if (draft.sessionId !in removedAccounts) composeBuffers.update { it + (draft.key to draft) }
    }

    fun removeComposeBuffer(key: String) {
        composeBuffers.update { it - key }
    }

    /** Remove account content atomically, journaling attachment paths before losing references. */
    suspend fun removeAccountData(sessionId: String): Set<String> {
        val buffers = synchronized(bufferLock) {
            removedAccounts.add(sessionId)
            composeBuffers.value.values.toList()
        }
        var files = emptySet<String>()
        dataStore.edit { stored ->
            val settings = stored[APP_PREFERENCES]?.let(::decodeAppPreferences)
            if (settings != null) stored[APP_PREFERENCES] = json.encodeToString(settings.copy(
                accountPreferences = settings.accountPreferences - sessionId,
                wordMutes = settings.wordMutes - sessionId,
            ))
            for (key in listOf(REACTION_HISTORY, COMPOSER_EMOJI_HISTORY)) {
                val history = stored[key]?.let { json.decodeFromString<Map<String, List<String>>>(it) }.orEmpty() - sessionId
                if (history.isEmpty()) stored.remove(key) else stored[key] = json.encodeToString(history)
            }
            val drafts = stored[DRAFTS]?.let { json.decodeFromString<List<ComposeDraft>>(it) }.orEmpty()
            val detached = stored[DETACHED_MEDIA]?.let { json.decodeFromString<Map<String, Set<String>>>(it) }.orEmpty()
            val pending = stored[PENDING_MEDIA]?.let { json.decodeFromString<Map<String, Set<String>>>(it) }.orEmpty()
            val retained = (drafts + buffers).filter { it.sessionId != sessionId }.flatMap { it.attachmentUris }.toSet() +
                detached.filterKeys { it != sessionId }.values.flatten()
            files = ((drafts + buffers).filter { it.sessionId == sessionId }.flatMap { it.attachmentUris }.toSet() +
                detached[sessionId].orEmpty() + pending[sessionId].orEmpty()) - retained
            stored[PENDING_MEDIA] = json.encodeToString(pending + (sessionId to files))
            val remaining = drafts.filterNot { it.sessionId == sessionId }
            if (remaining.isEmpty()) stored.remove(DRAFTS) else stored[DRAFTS] = json.encodeToString(remaining)
            if ((detached - sessionId).isEmpty()) stored.remove(DETACHED_MEDIA)
            else stored[DETACHED_MEDIA] = json.encodeToString(detached - sessionId)
        }
        synchronized(bufferLock) { composeBuffers.update { current -> current.filterValues { it.sessionId != sessionId } } }
        return files
    }

    suspend fun completeAccountMediaRemoval(sessionId: String) {
        dataStore.edit { stored ->
            val remaining = stored[PENDING_MEDIA]?.let { json.decodeFromString<Map<String, Set<String>>>(it) }.orEmpty() - sessionId
            if (remaining.isEmpty()) stored.remove(PENDING_MEDIA) else stored[PENDING_MEDIA] = json.encodeToString(remaining)
        }
    }

    private suspend fun update(sessionId: String? = null, transform: (AppPreferences) -> AppPreferences) {
        dataStore.edit { stored ->
            if (sessionId != null && !canWriteAccount(sessionId)) return@edit
            val current = stored[APP_PREFERENCES]?.let {
                runCatching { decodeAppPreferences(it) }.getOrNull()
            } ?: AppPreferences(openLinksInApp = stored[LEGACY_OPEN_LINKS_IN_APP] ?: true)
            stored[APP_PREFERENCES] = json.encodeToString(transform(current))
            stored.remove(LEGACY_OPEN_LINKS_IN_APP)
        }
    }

    private fun decodeAppPreferences(encoded: String): AppPreferences {
        val supportedActions = ComposerAction.entries.map { it.name }.toSet()
        // Removed toolbar actions must not make the rest of the saved settings unreadable.
        val settings = json.parseToJsonElement(encoded).jsonObject.mapValues { (key, value) ->
            when (key) {
                "composerActionOrder", "hiddenComposerActions" ->
                    JsonArray(value.jsonArray.filter { it.jsonPrimitive.content in supportedActions })
                "timelineDisplay" -> {
                    // The removed Large preset now uses Standard without resetting other preferences.
                    val display = value.jsonObject
                    if (display["thumbnailSize"]?.jsonPrimitive?.content == "Large") {
                        JsonObject(display + ("thumbnailSize" to JsonPrimitive(ThumbnailSize.Standard.name)))
                    } else value
                }
                else -> value
            }
        }
        return json.decodeFromJsonElement<AppPreferences>(JsonObject(settings))
    }

    private companion object {
        val DETACHED_MEDIA = stringPreferencesKey("detached_draft_media")
        val PENDING_MEDIA = stringPreferencesKey("pending_account_media_removal")
        val APP_PREFERENCES = stringPreferencesKey("app_preferences_v2")
        val DRAFTS = stringPreferencesKey("compose_drafts")
        val REACTION_HISTORY = stringPreferencesKey("reaction_history")
        val COMPOSER_EMOJI_HISTORY = stringPreferencesKey("composer_emoji_history")
        val LEGACY_OPEN_LINKS_IN_APP = booleanPreferencesKey("open_links_in_app")
    }
}
