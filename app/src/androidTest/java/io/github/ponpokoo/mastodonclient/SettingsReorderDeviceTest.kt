package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction
import io.github.ponpokoo.mastodonclient.core.preferences.*
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsDragAndDropList
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsPage
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsPageContent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import io.github.ponpokoo.mastodonclient.feature.settings.ReorderActionList
import io.github.ponpokoo.mastodonclient.feature.settings.settingsIcon
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsReorderDeviceTest {
    // Queue coroutine launches as in the application. Unconfined execution can run the
    // library scroll loop before its first scroll request has been queued.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
    private fun showPage(page: SettingsPage, data: MemoryPreferences, compact: Boolean = false): UserPreferencesStore {
        val store = UserPreferencesStore(data)
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"; override val versionCode = 1L
            override suspend fun clearImageCache() { }
        })
        compose.setContent { MaterialTheme {
            Box(if (compact) Modifier.height(300.dp) else Modifier.fillMaxSize()) {
            SettingsPageContent(page, {}, maintenance, store, null, emptyList(), {}, {}, {})
            }
        } }
        return store
    }

    private fun scrollToHandle(key: String) {
        val screen = compose.onNodeWithTag("settings_screen")
        repeat(20) {
            if (compose.onNodeWithTag("reorder_handle_$key").isDisplayed()) return
            screen.performTouchInput {
                swipe(Offset(center.x, height * .8f), Offset(center.x, height * .2f), 600)
            }
        }
        compose.onNodeWithTag("reorder_handle_$key").assertIsDisplayed()
    }

    @Test fun metadataChangesDuringDragKeepStableItemAndSaveOnlyOnDrop() {
        val first = AccountSession("first", "https://one.example", "a", "a", "Alice", "", "synthetic")
        val second = first.copy(sessionId = "second", instanceUrl = "https://two.example", displayName = "Bob")
        val items = mutableStateOf(listOf(first, second))
        val moves = mutableListOf<Pair<AccountSession, AccountSession?>>()
        compose.setContent { MaterialTheme {
            SettingsDragAndDropList(items.value, AccountSession::sessionId, { it.displayName },
                onMove = { item, before -> moves += item to before }) { Text(it.displayName, Modifier.weight(1f)) }
        } }
        val handle = compose.onNodeWithTag("reorder_handle_first")
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(0f, 40f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        handle.performTouchInput { moveBy(Offset(0f, 140f)) }
        val updated = first.copy(displayName = "Alice updated", avatarRevision = 1)
        compose.runOnIdle { items.value = listOf(updated, second); assertTrue(moves.isEmpty()) }
        compose.mainClock.advanceTimeBy(160)
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(listOf(updated to null), moves) }
    }

    @Test fun draggingAtViewportEdgeScrollsToEndOfComposerButtons() {
        val data = MemoryPreferences(); val store = showPage(SettingsPage.Composer, data, compact = true)
        val screen = compose.onNodeWithTag("settings_screen")
        scrollToHandle("Media")
        val handle = compose.onNodeWithTag("reorder_handle_Media")
        compose.onNodeWithTag("reorder_handle_DeleteDraft").assertIsNotDisplayed()
        val delta = screen.fetchSemanticsNode().boundsInRoot.bottom - handle.fetchSemanticsNode().boundsInRoot.center.y - 16f
        compose.mainClock.autoAdvance = false
        // Start the gesture before moving to the edge, as with consecutive real touch events.
        handle.performTouchInput { down(center); moveBy(Offset(0f, 40f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        handle.performTouchInput { moveBy(Offset(0f, delta - 40f)) }
        // Give Android layout a chance to apply each scroll before the next frame.
        repeat(90) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        compose.onNodeWithTag("reorder_handle_DeleteDraft").assertIsDisplayed()
        compose.runOnIdle { runBlocking {
            assertEquals(ComposerAction.entries, store.preferences.first().composerActionOrder)
        } }
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { runBlocking {
            val saved = UserPreferencesStore(data).preferences.first()
            assertEquals(ComposerAction.Media, saved.composerActionOrder.last())
            assertEquals(ComposerAction.entries.drop(1) + ComposerAction.Media, saved.composerActionOrder)
        } }
    }

    @Test fun cancelledDragDoesNotSaveAndAccessibilityUsesSamePlacement() {
        val order = mutableStateOf(listOf(StatusAction.Reply, StatusAction.Boost, StatusAction.Favourite))
        var saves = 0
        compose.setContent { MaterialTheme {
            ReorderActionList(order.value, { it.name }, { it.settingsIcon() }, { order.value = it; saves++ })
        } }
        val handle = compose.onNodeWithTag("reorder_handle_Reply")
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(0f, 40f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        handle.performTouchInput { moveBy(Offset(0f, 150f)) }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("reorder_handle_Boost").fetchSemanticsNode().boundsInRoot.top < handle.fetchSemanticsNode().boundsInRoot.top)
        handle.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, saves); assertEquals(StatusAction.Reply, order.value.first()) }
        val actions = handle.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "下へ移動" }.action()) }
        compose.runOnIdle {
            assertEquals(listOf(StatusAction.Boost, StatusAction.Reply, StatusAction.Favourite), order.value)
            assertEquals(1, saves)
        }
    }

    @Test fun itemsRemovedDuringDragCancelStaleDrop() {
        val items = mutableStateOf(listOf("first", "second", "third"))
        var saves = 0
        compose.setContent { MaterialTheme {
            SettingsDragAndDropList(items.value, { it }, { it }, onMove = { _, _ -> saves++ }) { Text(it, Modifier.weight(1f)) }
        } }
        val handle = compose.onNodeWithTag("reorder_handle_first")
        handle.performTouchInput { down(center); moveBy(Offset(0f, 40f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        handle.performTouchInput { moveBy(Offset(0f, 150f)) }
        // Finish the gesture only after the data source removes a possible destination.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items.value = listOf("first", "third") }
        compose.mainClock.advanceTimeBy(160)
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, saves); assertEquals(listOf("first", "third"), items.value) }
    }

    @Test fun composerButtonsDragAndKeepVisibilityAcrossStoreRecreation() {
        val data = MemoryPreferences(); val store = showPage(SettingsPage.Composer, data)
        val screen = compose.onNodeWithTag("settings_screen")
        scrollToHandle("Emoji")
        val mediaCheckbox = compose.onNode(isToggleable() and hasAnySibling(hasText("画像・動画")))
        mediaCheckbox.assertIsOn().performClick().assertIsOff()
        val media = compose.onNodeWithTag("reorder_handle_Media")
        val emoji = compose.onNodeWithTag("reorder_handle_Emoji")
        val distance = emoji.fetchSemanticsNode().boundsInRoot.center.y - media.fetchSemanticsNode().boundsInRoot.center.y
        media.performTouchInput { swipe(center, center + Offset(0f, distance), 500) }
        compose.runOnIdle { runBlocking {
            val saved = UserPreferencesStore(data).preferences.first()
            assertEquals(listOf(ComposerAction.Poll, ComposerAction.Emoji, ComposerAction.Media), saved.composerActionOrder.take(3))
            assertEquals(setOf(ComposerAction.Media), saved.hiddenComposerActions)
            assertEquals(saved, store.preferences.first())
        } }
    }

    @Test fun statusIconsDragAndKeepVisibilityAcrossStoreRecreation() {
        val data = MemoryPreferences(); showPage(SettingsPage.Timeline, data)
        val screen = compose.onNodeWithTag("settings_screen")
        scrollToHandle("Favourite")
        val replyCheckbox = compose.onNode(isToggleable() and hasAnySibling(hasText("返信")))
        replyCheckbox.assertIsOn().performClick().assertIsOff()
        val reply = compose.onNodeWithTag("reorder_handle_Reply")
        val favourite = compose.onNodeWithTag("reorder_handle_Favourite")
        val distance = favourite.fetchSemanticsNode().boundsInRoot.center.y - reply.fetchSemanticsNode().boundsInRoot.center.y
        reply.performTouchInput { swipe(center, center + Offset(0f, distance), 500) }
        compose.runOnIdle { runBlocking {
            val display = UserPreferencesStore(data).preferences.first().timelineDisplay
            assertEquals(listOf(StatusAction.Boost, StatusAction.Favourite, StatusAction.Reply), display.actionOrder.take(3))
            assertTrue(StatusAction.Reply in display.hiddenActions)
            assertTrue(StatusAction.Bookmark in display.hiddenActions)
        } }
    }

    @Test fun draggingHandleSavesNewOrder() {
        val order = mutableStateOf(listOf(StatusAction.Reply, StatusAction.Boost, StatusAction.Favourite))
        compose.setContent {
            MaterialTheme {
                ReorderActionList(order.value, { it.name }, { it.settingsIcon() }, { order.value = it })
            }
        }
        val first = compose.onNodeWithContentDescription("Replyの並び替え")
        val last = compose.onNodeWithContentDescription("Favouriteの並び替え")
        val distance = last.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        // Move slightly past the destination center to avoid a boundary tie in the settle index.
        first.performTouchInput { swipe(center, center + Offset(0f, distance + 16.dp.toPx()), 500) }
        compose.runOnIdle {
            assertEquals(listOf(StatusAction.Boost, StatusAction.Favourite, StatusAction.Reply), order.value)
        }
    }

    @Test fun delayedSaveKeepsRowsAndSettledOrderWithoutRecreatingContent() {
        val source = mutableStateOf(listOf("first", "second", "third"))
        val busy = mutableStateOf(false)
        val created = mutableMapOf<String, Int>()
        val disposed = mutableListOf<String>()
        var requested: List<String>? = null
        compose.setContent { MaterialTheme {
            SettingsDragAndDropList(source.value, { it }, { it }, enabled = !busy.value,
                onMove = { item, before ->
                    requested = source.value.toMutableList().apply {
                        remove(item)
                        add(if (before == null) size else indexOf(before), item)
                    }
                }) { item ->
                DisposableEffect(item) {
                    created[item] = (created[item] ?: 0) + 1
                    onDispose { disposed += item }
                }
                Text(item, Modifier.weight(1f))
            }
        } }
        val first = compose.onNodeWithTag("reorder_handle_first")
        val last = compose.onNodeWithTag("reorder_handle_third")
        val distance = last.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput { swipe(center, center + Offset(0f, distance + 16.dp.toPx()), 500) }
        compose.runOnIdle {
            assertEquals(listOf("second", "third", "first"), requested)
            assertEquals(listOf("first", "second", "third"), source.value)
            busy.value = true
        }
        val beforeAck = first.fetchSemanticsNode().boundsInRoot
        assertTrue(beforeAck.top > last.fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle { busy.value = false }
        // Busy completion may arrive before the saved-order Flow; keep the settled rows visible.
        assertEquals(beforeAck.top, first.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { source.value = requested!! }
        val afterAck = first.fetchSemanticsNode().boundsInRoot
        assertEquals(beforeAck.top, afterAck.top, 1f)
        compose.runOnIdle {
            assertEquals(mapOf("first" to 1, "second" to 1, "third" to 1), created)
            assertTrue("Saved-order acknowledgement recreated rows: $disposed", disposed.isEmpty())
        }
        first.assertIsEnabled()
    }

    @Test fun failedSaveRestoresSourceOrderAndAllowsRetry() {
        val source = listOf("first", "second")
        val busy = mutableStateOf(false)
        val failed = mutableStateOf(false)
        var saves = 0
        compose.setContent { MaterialTheme {
            SettingsDragAndDropList(source, { it }, { it }, enabled = !busy.value, saveFailed = failed.value,
                onMove = { _, _ -> saves++ }) { Text(it, Modifier.weight(1f)) }
        } }
        val first = compose.onNodeWithTag("reorder_handle_first")
        val second = compose.onNodeWithTag("reorder_handle_second")
        val distance = second.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput { swipe(center, center + Offset(0f, distance + 16.dp.toPx()), 500) }
        compose.runOnIdle { assertEquals(1, saves); busy.value = true }
        first.assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; failed.value = true }
        first.assertIsEnabled()
        assertTrue(first.fetchSemanticsNode().boundsInRoot.top < second.fetchSemanticsNode().boundsInRoot.top)
    }
}
