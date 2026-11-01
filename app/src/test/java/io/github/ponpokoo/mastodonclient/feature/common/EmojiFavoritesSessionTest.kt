package io.github.ponpokoo.mastodonclient.feature.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiFavoritesSessionTest : ScreenViewModelTestBase() {
    @Test fun pickerConnectionsShareFavoritesButKeepTheirHistoriesSeparate() = runTest(dispatcher) {
        val preferences = UserPreferencesStore(MemoryAuthPreferences())
        preferences.setReactionHistory(testAccount.sessionId, listOf("reaction"))
        preferences.setComposerEmojiHistory(testAccount.sessionId, listOf("composer"))
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val interactions = own(StatusInteractionsViewModel(ScreenRepositoryFake(), browsing, preferences))
        val composer = composer(preferences)
        runCurrent()
        val reactionConnection = interactions.emojiPickerConnection()!!
        val composerConnection = composer.emojiPickerConnection()!!
        assertSame(reactionConnection, interactions.emojiPickerConnection())
        assertSame(composerConnection, composer.emojiPickerConnection())
        val reaction = reactionConnection.createState().also { it.start() }
        val input = composerConnection.createState().also { it.start() }
        runCurrent()
        assertEquals(listOf("reaction"), reaction.state.value.history)
        assertEquals(listOf("composer"), input.state.value.history)
        assertTrue(input.actions.setFavorite("😊", true))
        reaction.close()
        val reopened = reactionConnection.createState().also { it.start() }
        runCurrent()
        assertEquals(listOf("😊"), reopened.state.value.favorites)
        val undo = reopened.actions.removeHistory("reaction")!!
        assertEquals(listOf("composer"), preferences.composerEmojiHistory.first()[testAccount.sessionId])
        composer.switchPostingAccount(secondAccount.sessionId)
        browsing.activate(secondAccount)
        runCurrent()
        composer.switchPostingAccount(testAccount.sessionId)
        browsing.activate(testAccount)
        runCurrent()
        assertNotSame(reactionConnection, interactions.emojiPickerConnection())
        assertNotSame(composerConnection, composer.emojiPickerConnection())
        assertTrue(runCatching { undo.restore() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { input.actions.setFavorite("late", true) }.exceptionOrNull() is CancellationException)
        assertTrue(preferences.reactionHistory.first()[testAccount.sessionId].orEmpty().isEmpty())
        assertEquals(listOf("😊"), preferences.emojiFavorites.first()[testAccount.sessionId])
        reopened.close(); input.close()
    }

    @Test fun pickerConnectionsRejectDelayedHistoryReadsAfterSwitchingAwayAndBack() = runTest(dispatcher) {
        for (inComposer in listOf(false, true)) {
            val memory = MemoryAuthPreferences()
            val gate = CompletableDeferred<Unit>()
            var delayNextRead = false
            val preferences = UserPreferencesStore(object : DataStore<Preferences> {
                override val data = flow {
                    if (delayNextRead) {
                        delayNextRead = false
                        withContext(NonCancellable) { gate.await() }
                    }
                    emit(memory.data.first())
                }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = memory.updateData(transform)
            })
            preferences.setReactionHistory(testAccount.sessionId, listOf("reaction"))
            preferences.setComposerEmojiHistory(testAccount.sessionId, listOf("composer"))
            val browsing = BrowsingSession().apply { activate(testAccount) }
            val interactions = own(StatusInteractionsViewModel(ScreenRepositoryFake(), browsing, preferences))
            val composer = composer(preferences)
            runCurrent()
            val connection = if (inComposer) composer.emojiPickerConnection()!! else interactions.emojiPickerConnection()!!
            val picker = connection.createState()
            delayNextRead = true
            picker.start(); runCurrent()
            assertFalse(picker.state.value.historyLoaded)
            composer.switchPostingAccount(secondAccount.sessionId); browsing.activate(secondAccount); runCurrent()
            composer.switchPostingAccount(testAccount.sessionId); browsing.activate(testAccount); runCurrent()
            gate.complete(Unit); runCurrent()
            assertTrue(picker.state.value.history.isEmpty())
            assertNull(picker.state.value.historyError)
            val freshConnection = if (inComposer) composer.emojiPickerConnection()!! else interactions.emojiPickerConnection()!!
            val fresh = freshConnection.createState().also { it.start() }
            runCurrent()
            assertEquals(listOf(if (inComposer) "composer" else "reaction"), fresh.state.value.history)
            picker.close(); fresh.close()
        }
    }

    @Test fun composerAndReactionPickerShareOnlyTheSelectedAccountsFavorites() = runTest(dispatcher) {
        val preferences = UserPreferencesStore(MemoryAuthPreferences())
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val interactions = own(StatusInteractionsViewModel(ScreenRepositoryFake(), browsing, preferences))
        val composer = composer(preferences)
        runCurrent()
        composer.setEmojiFavorite(testAccount.sessionId, "😊", true)
        assertEquals(listOf("😊"), interactions.loadEmojiFavorites(testAccount.sessionId))
        interactions.setEmojiFavorite(testAccount.sessionId, "custom", true)
        assertEquals(listOf("😊", "custom"), composer.loadEmojiFavorites(testAccount.sessionId))
        composer.switchPostingAccount(secondAccount.sessionId); browsing.activate(secondAccount); runCurrent()
        assertTrue(composer.loadEmojiFavorites(secondAccount.sessionId).isEmpty())
        assertTrue(interactions.loadEmojiFavorites(secondAccount.sessionId).isEmpty())
        interactions.setEmojiFavorite(secondAccount.sessionId, "👍", true)
        assertTrue(runCatching { interactions.setEmojiFavorite(testAccount.sessionId, "late", true) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { composer.setEmojiFavorite(testAccount.sessionId, "late", true) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("😊", "custom"), preferences.emojiFavorites.first()[testAccount.sessionId])
        assertEquals(listOf("👍"), preferences.emojiFavorites.first()[secondAccount.sessionId])
    }

    @Test fun delayedReadsAreRejectedAfterSwitchingAwayAndBackInEitherPicker() = runTest(dispatcher) {
        for (inComposer in listOf(false, true)) {
            val memory = MemoryAuthPreferences()
            val gate = CompletableDeferred<Unit>()
            var delayNextRead = false
            val data = object : DataStore<Preferences> {
                override val data = flow {
                    if (delayNextRead) {
                        delayNextRead = false
                        withContext(NonCancellable) { gate.await() }
                    }
                    emit(memory.data.first())
                }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = memory.updateData(transform)
            }
            val preferences = UserPreferencesStore(data)
            preferences.setEmojiFavorite(testAccount.sessionId, "😊", true)
            val browsing = BrowsingSession().apply { activate(testAccount) }
            val interactions = own(StatusInteractionsViewModel(ScreenRepositoryFake(), browsing, preferences))
            val composer = composer(preferences)
            runCurrent()
            delayNextRead = true
            val pending = async {
                runCatching {
                    if (inComposer) composer.loadEmojiFavorites(testAccount.sessionId)
                    else interactions.loadEmojiFavorites(testAccount.sessionId)
                }
            }
            runCurrent()
            assertFalse(pending.isCompleted)
            composer.switchPostingAccount(secondAccount.sessionId); browsing.activate(secondAccount); runCurrent()
            composer.switchPostingAccount(testAccount.sessionId); browsing.activate(testAccount); runCurrent()
            gate.complete(Unit); runCurrent()
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
        }
    }

    private fun composer(preferences: UserPreferencesStore) = own(ComposePostViewModel(
        null, null, ScreenRepositoryFake(), object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions(): List<AccountSession> = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
        }, logMediaFailure = {},
    ))
}
