package io.github.ponpokoo.mastodonclient.core.preferences

import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountDataRemovalTest {
    @Test fun removesOnlySelectedAccountAndRejectsLateWritesAfterRecreation() = runTest {
        val data = MemoryAuthPreferences()
        val accounts = mutableSetOf("a", "b")
        fun store() = UserPreferencesStore(data, isAccountPresent = { it in accounts })
        val preferences = store()
        preferences.setThemeMode(ThemeMode.Dark)
        preferences.setOpenLinksInApp(false)
        for (id in accounts) {
            preferences.setAccountPreferences(id, AccountPreferences(PostVisibility.Direct, StreamingPolicy.Off))
            preferences.editWordMutes(id) { listOf("private-$id") }
            preferences.recordReaction(id, "reaction-$id")
            preferences.recordComposerEmoji(id, "emoji-$id")
            preferences.saveDraft(ComposeDraft(id, id, text = "draft-$id", attachmentUris = listOf("file:///$id")))
            preferences.retainComposeBuffer(ComposeDraft("buffer-$id", id, text = "input-$id", attachmentUris = listOf("file:///buffer-$id")))
        }
        val before = preferences.preferences.first()
        accounts.remove("a")
        assertEquals(setOf("file:///a", "file:///buffer-a"), preferences.removeAccountData("a"))
        assertNull(preferences.getComposeBuffer("buffer-a"))
        assertNotNull(preferences.getComposeBuffer("buffer-b"))
        preferences.retainComposeBuffer(ComposeDraft("late", "a", text = "late"))
        assertNull(preferences.getComposeBuffer("late"))
        val restored = store()
        restored.setAccountPreferences("a", AccountPreferences())
        restored.editWordMutes("a") { listOf("late") }
        restored.recordReaction("a", "late")
        restored.setReactionHistory("a", listOf("late"))
        restored.recordComposerEmoji("a", "late")
        restored.setComposerEmojiHistory("a", listOf("late"))
        assertTrue(runCatching { restored.saveDraft(ComposeDraft("late", "a", text = "late")) }.isFailure)
        assertEquals(before.copy(accountPreferences = before.accountPreferences - "a", wordMutes = before.wordMutes - "a"), restored.preferences.first())
        assertEquals(listOf("b"), restored.drafts.first().map { it.sessionId })
        assertEquals(mapOf("b" to listOf("reaction-b")), restored.reactionHistory.first())
        assertEquals(mapOf("b" to listOf("emoji-b")), restored.composerEmojiHistory.first())
    }

    @Test fun attachmentJournalSurvivesRetryAndProtectsOtherAccountsLegacyFiles() = runTest {
        val data = MemoryAuthPreferences()
        val preferences = UserPreferencesStore(data)
        preferences.saveDraft(ComposeDraft("a", "a", attachmentUris = listOf("file:///draft_media/only-a", "file:///draft_media/shared")))
        preferences.saveDraft(ComposeDraft("b", "b", attachmentUris = listOf("file:///draft_media/shared")))
        // Taking a draft out must retain the ownership of older, unscoped files.
        preferences.deleteDraft("a")
        assertEquals(setOf("file:///draft_media/only-a"), preferences.removeAccountData("a"))
        val recreated = UserPreferencesStore(data)
        assertEquals(setOf("file:///draft_media/only-a"), recreated.removeAccountData("a"))
        recreated.completeAccountMediaRemoval("a")
        assertTrue(recreated.removeAccountData("a").isEmpty())
        assertEquals("b", recreated.drafts.first().single().sessionId)
    }
}
