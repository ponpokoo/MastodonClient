package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.ProfileEditRequest
import io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileImageEditorTest {
    private class Images : ProfileImageRepository {
        val pending = mutableMapOf<String, CompletableDeferred<String>>()
        val deleted = mutableListOf<String>()
        override suspend fun importImage(uri: String): String = withContext(NonCancellable) {
            pending.getOrPut(uri) { CompletableDeferred() }.await()
        }
        override suspend fun deleteImages(paths: List<String>) { deleted += paths }
    }
    private val request = ProfileEditRequest("name", "note", false, false)

    @Test fun reselectionRejectsLateResultAndDeletesReplacedFile() = runTest {
        val images = Images(); val editor = ProfileImageEditor(images, this)
        editor.open(); editor.select(ProfileImageSlot.Avatar, "first"); runCurrent()
        assertNull(editor.beginSave(request))
        editor.select(ProfileImageSlot.Avatar, "second"); runCurrent()
        images.pending.getValue("second").complete("second-file"); runCurrent()
        images.pending.getValue("first").complete("first-file"); runCurrent()
        assertEquals("second-file", editor.state.value.avatarPath)
        assertEquals(listOf("first-file"), images.deleted)
        editor.select(ProfileImageSlot.Avatar, "third"); runCurrent()
        images.pending.getValue("third").complete("third-file"); runCurrent()
        assertTrue("second-file" in images.deleted)
        editor.dismiss(); runCurrent()
        assertTrue("third-file" in images.deleted)
    }

    @Test fun dismissRejectsLateCompletionEvenAfterReopening() = runTest {
        val images = Images(); val editor = ProfileImageEditor(images, this)
        editor.open(); editor.select(ProfileImageSlot.Header, "slow"); runCurrent()
        editor.dismiss(); editor.open()
        images.pending.getValue("slow").complete("unused-file"); runCurrent()
        assertNull(editor.state.value.headerPath)
        assertTrue(editor.state.value.importing.isEmpty())
        assertEquals(listOf("unused-file"), images.deleted)
    }

    @Test fun failedImportKeepsPreviousImageAndAllowsSaving() = runTest {
        val images = Images(); val editor = ProfileImageEditor(images, this)
        editor.open(); editor.select(ProfileImageSlot.Avatar, "ok"); runCurrent()
        images.pending.getValue("ok").complete("kept-file"); runCurrent()
        for (error in listOf(SecurityException(), java.io.IOException())) {
            editor.select(ProfileImageSlot.Avatar, "failed-${error.javaClass.simpleName}"); runCurrent()
            images.pending.getValue("failed-${error.javaClass.simpleName}").completeExceptionally(error); runCurrent()
            assertEquals("kept-file", editor.state.value.avatarPath)
            assertNotNull(editor.state.value.error)
            assertTrue(editor.state.value.importing.isEmpty())
        }
        val save = editor.beginSave(request)!!
        editor.finishSave(save, false)
        assertFalse("kept-file" in images.deleted)
        editor.dismiss(); runCurrent()
    }

    @Test fun saveRetainsFilesDuringDismissAndReleasesAfterCompletion() = runTest {
        val images = Images(); val editor = ProfileImageEditor(images, this)
        editor.open(); editor.select(ProfileImageSlot.Avatar, "ok"); runCurrent()
        images.pending.getValue("ok").complete("upload-file"); runCurrent()
        val save = editor.beginSave(request)!!
        assertEquals("upload-file", save.request.avatarFilePath)
        editor.dismiss(); runCurrent()
        assertTrue(images.deleted.isEmpty())
        assertNull(editor.beginSave(request))
        editor.finishSave(save, false)
        assertEquals(listOf("upload-file"), images.deleted)
        assertFalse(editor.state.value.isSaving)
    }

    @Test fun failedSaveKeepsFileForRetryAndSuccessfulRetryDeletesIt() = runTest {
        val images = Images(); val editor = ProfileImageEditor(images, this)
        editor.open(); editor.select(ProfileImageSlot.Header, "ok"); runCurrent()
        images.pending.getValue("ok").complete("retry-file"); runCurrent()
        editor.finishSave(editor.beginSave(request)!!, false)
        assertTrue(images.deleted.isEmpty())
        assertTrue(editor.state.value.isOpen)
        editor.finishSave(editor.beginSave(request)!!, true)
        assertEquals(listOf("retry-file"), images.deleted)
        assertFalse(editor.state.value.isOpen)
    }
}
