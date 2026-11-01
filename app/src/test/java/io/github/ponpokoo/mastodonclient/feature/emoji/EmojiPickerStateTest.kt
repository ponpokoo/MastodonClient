package io.github.ponpokoo.mastodonclient.feature.emoji

import java.io.IOException
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiPickerStateTest {
    @Test fun failedReadCanBeRetriedWithoutLosingFavorites() = runTest {
        val f = Fixture(backgroundScope)
        var fail = true
        f.loadFavorites = { if (fail) throw IOException() else listOf("😊", "😊", "old_custom") }
        f.picker.start(); runCurrent()
        assertFalse(f.state.favoritesLoaded)
        assertNotNull(f.state.favoritesError)
        fail = false
        f.picker.actions.retryFavorites(); runCurrent()
        assertEquals(listOf("😊", "old_custom"), f.state.favorites)
        assertTrue(f.state.favoritesLoaded)
        assertNull(f.state.favoritesError)
    }

    @Test fun savingRejectsDuplicateOperationsAndOnlyPublishesConfirmedResult() = runTest {
        val f = Fixture(backgroundScope)
        f.picker.start(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        f.updateFavorite = { name, _ -> writes++; gate.await(); listOf(name) }
        val first = async { f.picker.actions.setFavorite("😊", true) }
        runCurrent()
        assertTrue(f.state.favoriteSaving)
        assertTrue(f.state.favorites.isEmpty())
        assertFalse(f.picker.actions.setFavorite("👍", true))
        assertEquals(1, writes)
        gate.complete(Unit); runCurrent()
        assertTrue(first.await())
        assertEquals(listOf("😊"), f.state.favorites)
        assertFalse(f.state.favoriteSaving)
    }

    @Test fun failedOrUnconfirmedSaveKeepsPreviousFavoritesAndAllowsRetry() = runTest {
        val f = Fixture(backgroundScope)
        f.favorites = listOf("old_custom")
        f.picker.start(); runCurrent()
        f.updateFavorite = { _, _ -> throw IOException() }
        assertFalse(f.picker.actions.setFavorite("😊", true))
        assertEquals(listOf("old_custom"), f.state.favorites)
        assertNotNull(f.state.favoritesError)
        f.updateFavorite = { _, _ -> listOf("old_custom") }
        assertFalse(f.picker.actions.setFavorite("😊", true))
        f.updateFavorite = { name, _ -> listOf("old_custom", name) }
        assertTrue(f.picker.actions.setFavorite("😊", true))
        assertNull(f.state.favoritesError)
    }

    @Test fun historyRemovalCanBeUndoneWithoutChangingFavorites() = runTest {
        val f = Fixture(backgroundScope)
        f.history = listOf("😊", "old_custom")
        f.favorites = listOf("😊")
        f.picker.start(); runCurrent()
        val undo = f.picker.actions.removeHistory("😊")!!
        assertEquals(listOf("old_custom"), f.state.history)
        assertEquals(f.history, f.state.history)
        undo.restore()
        assertEquals(listOf("😊", "old_custom"), f.state.history)
        assertEquals(f.history, f.state.history)
        assertEquals(listOf("😊"), f.state.favorites)
    }

    @Test fun failedHistorySavePreservesEntriesAndFailedUndoPreservesRemoval() = runTest {
        val f = Fixture(backgroundScope)
        f.history = listOf("😊")
        f.picker.start(); runCurrent()
        f.failHistorySave = true
        assertNull(f.picker.actions.removeHistory("😊"))
        assertEquals(listOf("😊"), f.state.history)
        assertNotNull(f.state.historyError)
        f.failHistorySave = false
        val undo = f.picker.actions.removeHistory("😊")!!
        f.failHistorySave = true
        undo.restore()
        assertTrue(f.state.history.isEmpty())
        assertNotNull(f.state.historyError)
    }

    @Test fun anOlderUndoCannotOverwriteANewerHistoryEdit() = runTest {
        val f = Fixture(backgroundScope)
        f.history = listOf("😊", "👍")
        f.picker.start(); runCurrent()
        val oldUndo = f.picker.actions.removeHistory("😊")!!
        f.picker.actions.removeHistory("👍")
        oldUndo.restore()
        assertTrue(f.state.history.isEmpty())
        assertTrue(f.history.isEmpty())
    }

    @Test fun historyReadFailureCanBeRetried() = runTest {
        val f = Fixture(backgroundScope)
        var fail = true
        f.loadHistory = { if (fail) throw IOException() else listOf("old_custom") }
        f.picker.start(); runCurrent()
        assertFalse(f.state.historyLoaded)
        assertNotNull(f.state.historyError)
        fail = false
        f.picker.actions.retryHistory(); runCurrent()
        assertTrue(f.state.historyLoaded)
        assertEquals(listOf("old_custom"), f.state.history)
        assertNull(f.state.historyError)
    }

    @Test fun dismissalRejectsAllDelayedReadsEvenWhenTheyIgnoreCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val picker = EmojiPickerState(backgroundScope, EmojiPickerDataSource(
            customEmojis = flow {
                emit(EmojiPickerEmojis(loading = true))
                withContext(NonCancellable) { gate.await() }
                emit(EmojiPickerEmojis(listOf(CustomEmoji("old", "", "", null))))
            },
            loadHistory = { withContext(NonCancellable) { gate.await() }; listOf("old-history") },
            loadFavorites = { withContext(NonCancellable) { gate.await() }; listOf("old-favorite") },
        ))
        picker.start(); runCurrent()
        val before = picker.state.value
        picker.close()
        gate.complete(Unit); runCurrent()
        assertEquals(before, picker.state.value)
    }

    @Test fun staleConnectionDoesNotStartAnyDataOperations() = runTest {
        var calls = 0
        val picker = EmojiPickerState(backgroundScope, EmojiPickerDataSource(
            customEmojis = flow { calls++; emit(EmojiPickerEmojis()) },
            loadHistory = { calls++; emptyList() },
            loadFavorites = { calls++; emptyList() },
        ), checkSession = { throw CancellationException("Old generation") })
        picker.start(); runCurrent()
        assertEquals(0, calls)
        assertNull(picker.state.value.loadError)
        assertNull(picker.state.value.favoritesError)
        assertNull(picker.state.value.historyError)
        picker.close()
    }

    @Test fun switchingAwayAndBackRejectsADelayedSaveAndOldUndo() = runTest {
        var generation = 0
        val f = Fixture(backgroundScope) {
            if (generation != 0) throw CancellationException("Session changed")
        }
        f.history = listOf("😊")
        f.picker.start(); runCurrent()
        val undo = f.picker.actions.removeHistory("😊")!!
        val gate = CompletableDeferred<Unit>()
        f.updateFavorite = { name, _ -> withContext(NonCancellable) { gate.await() }; listOf(name) }
        val pending = async { runCatching { f.picker.actions.setFavorite("👍", true) } }
        runCurrent()
        generation += 2
        gate.complete(Unit); runCurrent()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertTrue(f.state.favorites.isEmpty())
        assertTrue(runCatching { undo.restore() }.exceptionOrNull() is CancellationException)
        assertTrue(f.history.isEmpty())
        assertNull(f.state.favoritesError)
    }

    @Test fun invalidSessionCancelsReadsAndDoesNotReportCancellationAsFailure() = runTest {
        val valid = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val picker = EmojiPickerState(backgroundScope, EmojiPickerDataSource(
            customEmojis = MutableStateFlow(EmojiPickerEmojis()),
            loadFavorites = { withContext(NonCancellable) { gate.await() }; listOf("old") },
        ), sessionValid = valid)
        picker.start(); runCurrent()
        valid.value = false; runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(picker.state.value.favorites.isEmpty())
        assertNull(picker.state.value.favoritesError)
    }

    @Test fun updatedCustomEmojiStateIsObservedWithoutReloadingSavedLists() = runTest {
        val f = Fixture(backgroundScope)
        var loads = 0
        f.loadFavorites = { loads++; listOf("old_custom") }
        f.picker.start(); runCurrent()
        f.emojis.value = EmojiPickerEmojis(loading = true); runCurrent()
        assertTrue(f.state.loading)
        f.emojis.value = EmojiPickerEmojis(error = "fixture error"); runCurrent()
        assertFalse(f.state.loading)
        assertEquals("fixture error", f.state.loadError)
        assertEquals(listOf("old_custom"), f.state.favorites)
        assertEquals(1, loads)
    }

    private class Fixture(scope: CoroutineScope, checkSession: () -> Unit = {}) {
        var history = emptyList<String>()
        var favorites = emptyList<String>()
        var failHistorySave = false
        var loadHistory: suspend () -> List<String> = { history }
        var loadFavorites: suspend () -> List<String> = { favorites }
        var updateFavorite: suspend (String, Boolean) -> List<String> = { name, favorite ->
            (if (favorite) (favorites + name).distinct() else favorites - name).also { favorites = it }
        }
        val emojis = MutableStateFlow(EmojiPickerEmojis())
        val picker = EmojiPickerState(scope, EmojiPickerDataSource(
            customEmojis = emojis,
            loadHistory = { loadHistory() },
            saveHistory = { if (failHistorySave) throw IOException(); history = it },
            loadFavorites = { loadFavorites() },
            updateFavorite = { name, favorite -> updateFavorite(name, favorite) },
        ), checkSession)
        val state get() = picker.state.value
    }
}
