package io.github.ponpokoo.mastodonclient.feature.settings

import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostingSettingsViewModelTest : ScreenViewModelTestBase() {
    private class Repository : ScreenRepositoryFake() {
        override val serverSettings = MutableStateFlow(listOf(testAccount, secondAccount).associate { account ->
            account.sessionId to ServerAccountSettings(account.instanceUrl, account.accountId,
                defaults = PostingDefaults(if (account == testAccount) "unlisted" else "private", "followers", "ja", true, true))
        })
        val calls = mutableListOf<Pair<String, String>>()
        var save: suspend () -> Result<Unit> = { Result.success(Unit) }
        override suspend fun savePostingVisibility(session: AccountSession, visibility: String): Result<Unit> {
            calls += session.sessionId to visibility
            return save().onSuccess {
                serverSettings.value = serverSettings.value + (session.sessionId to serverSettings.value.getValue(session.sessionId).let {
                    it.copy(defaults = it.defaults!!.copy(visibility = visibility))
                })
            }
        }
    }

    @Test fun reflectsFetchedDefaultsAndOnlyConfirmsSelectionAfterSuccessfulSave() = runTest(dispatcher) {
        val repository = Repository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(PostingSettingsViewModel(repository, browsing)); advanceUntilIdle()
        assertEquals(PostVisibility.Unlisted, model.state.value.visibility)
        val gate = CompletableDeferred<Result<Unit>>()
        repository.save = { gate.await() }
        model.setVisibility(PostVisibility.FollowersOnly); model.setVisibility(PostVisibility.Public); runCurrent()
        assertTrue(model.state.value.saving)
        assertEquals(PostVisibility.Unlisted, model.state.value.visibility)
        assertEquals(listOf(testAccount.sessionId to "private"), repository.calls)
        gate.complete(Result.success(Unit)); advanceUntilIdle()
        assertEquals(PostVisibility.FollowersOnly, model.state.value.visibility)
        assertFalse(model.state.value.saving)
        assertNull(model.state.value.error)
        assertEquals("ja", repository.serverSettings.value.getValue(testAccount.sessionId).defaults!!.language)
        repository.save = { Result.failure(IllegalArgumentException("権限不足")) }
        model.setVisibility(PostVisibility.Public); advanceUntilIdle()
        assertEquals(PostVisibility.FollowersOnly, model.state.value.visibility)
        assertEquals("権限不足", model.state.value.error)
        repository.save = { Result.success(Unit) }
        model.setVisibility(PostVisibility.Public); advanceUntilIdle()
        assertEquals(PostVisibility.Public, model.state.value.visibility); assertNull(model.state.value.error)
        model.setVisibility(PostVisibility.Direct); advanceUntilIdle()
        assertEquals(3, repository.calls.size)
    }

    @Test fun lateFailureAndOldCacheCannotLeakAcrossAccountSwitches() = runTest(dispatcher) {
        val repository = Repository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(PostingSettingsViewModel(repository, browsing)); advanceUntilIdle()
        val gate = CompletableDeferred<Result<Unit>>()
        repository.save = { withContext(NonCancellable) { gate.await() } }
        model.setVisibility(PostVisibility.Public); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        assertEquals(secondAccount, model.state.value.account)
        assertEquals(PostVisibility.FollowersOnly, model.state.value.visibility)
        assertFalse(model.state.value.saving)
        browsing.activate(testAccount); runCurrent()
        gate.complete(Result.failure(IllegalArgumentException("old error"))); advanceUntilIdle()
        assertEquals(PostVisibility.Unlisted, model.state.value.visibility)
        assertNull(model.state.value.error); assertFalse(model.state.value.saving)
        repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, cached) -> cached.copy(instanceUrl = "https://wrong.example") }
        advanceUntilIdle(); assertNull(model.state.value.visibility)
    }
}
