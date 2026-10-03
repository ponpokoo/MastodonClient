package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.AudioPlayer
import io.github.ponpokoo.mastodonclient.feature.media.AudioPlayerContent
import io.github.ponpokoo.mastodonclient.feature.media.MediaPlayback
import io.github.ponpokoo.mastodonclient.feature.media.rememberMediaPlayback
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioPlayerUiDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun mp3SeeksAndReplaysWithAvatarControls() = checkSeeking("silence.mp3")
    @Test fun wavSeeksAndReplaysWithAvatarControls() = checkSeeking("tone.wav")

    private fun checkSeeking(name: String) {
        val state = showPlayer(audio(name))
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) { state.get().playing && state.get().seekable }
        rule.onNodeWithContentDescription("音声を一時停止").performClick()
        rule.onNodeWithTag("media_seek").performTouchInput {
            swipe(Offset(width * .05f, center.y), Offset(width * .75f, center.y), 400)
        }
        rule.waitUntil(5_000) { state.get().position in 3_500L..4_100L }
        rule.runOnIdle { assertFalse(state.get().player!!.playWhenReady) }
        rule.onNodeWithContentDescription("5秒戻る").performClick()
        rule.waitUntil(5_000) { state.get().position <= 100 }
        rule.onNodeWithContentDescription("10秒進む").performClick()
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(5_000) { state.get().playing && state.get().position < 2_000 }
    }

    @Test fun upwardVolumeDragAndOutsideDismissalReachPlayer() {
        val state = showPlayer(audio("tone.wav"))
        rule.onNodeWithTag("media_volume").assertDoesNotExist()
        rule.onNodeWithContentDescription("音声の音量調整").performClick()
        rule.onNodeWithTag("audio_volume_popup").assertIsDisplayed()
        saveScreenshot("audio-player-volume-ui.png")
        rule.onNodeWithTag("media_volume").performTouchInput {
            swipe(Offset(center.x, height * .85f), Offset(center.x, height * .55f), 400)
        }
        val lowered = state.get().volume
        assertTrue("vertical drag lowers volume: $lowered", lowered > 0f && lowered < .7f)
        rule.onNodeWithTag("media_volume").performTouchInput {
            swipe(Offset(center.x, height * .55f), Offset(center.x, height * .1f), 400)
        }
        val raised = state.get().volume
        assertTrue("upward drag raises volume: $raised", raised > lowered + .2f)
        // Tap outside the popup, without activating any playback button.
        val bounds = rule.onNodeWithTag("audio_attachment").fetchSemanticsNode().boundsInRoot
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val tapTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(tapTime, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width * .05f, bounds.top + bounds.height * .45f, 0)
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("audio_volume_popup").assertDoesNotExist()
        rule.onNodeWithContentDescription("音声の音量調整").performClick()
        rule.onNodeWithTag("audio_volume_popup").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        rule.onNodeWithTag("audio_volume_popup").assertDoesNotExist()
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) { state.get().playing }
        rule.runOnIdle { assertEquals(raised, state.get().player!!.volume, .001f) }
        rule.onNodeWithContentDescription("音声の音量調整").performClick()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithTag("audio_volume_popup").assertDoesNotExist()
        rule.runOnIdle { assertFalse(state.get().player!!.playWhenReady) }
    }

    @Test fun avatarLoadingChangesBackgroundAndFailureFallsBackWithoutLosingPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val iconFile = File.createTempFile("audio-avatar-", ".png", context.cacheDir).also(files::add)
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(0, 180, 140))
            iconFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val media = audio("tone.wav").copy(authorAvatarUrl = iconFile.toURI().toString())
        val avatar = mutableStateOf(media.authorAvatarUrl)
        rule.setContent { MaterialTheme { Box(Modifier.padding(16.dp)) { AudioPlayer(media, authorAvatarUrl = avatar.value) } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("audio_avatar_placeholder").fetchSemanticsNodes().isEmpty() }
        // The opaque, teal source must produce a teal surface, rather than using preview_url.
        rule.waitUntil(5_000) {
            val pixel = rule.onNodeWithTag("audio_attachment").captureToImage().toPixelMap()[24, 24]
            pixel.green > pixel.red * 1.4f
        }
        saveScreenshot("audio-player-ui.png")
        rule.runOnIdle { avatar.value = "file:///missing-author-icon.png" }
        rule.onNodeWithTag("audio_avatar_placeholder").assertExists()
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "再生中"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun inactiveAudioClosesPopupAndPreventsPlaybackUntilSelectedAgain() {
        val media = audio("tone.wav")
        val active = mutableStateOf(true)
        rule.setContent { MaterialTheme { AudioPlayer(media, active = active.value) } }
        rule.onNodeWithContentDescription("音声の音量調整").performClick()
        rule.runOnIdle { active.value = false }
        rule.onNodeWithTag("audio_volume_popup").assertDoesNotExist()
        rule.onNodeWithContentDescription("音声を再生").assertIsNotEnabled()
        rule.runOnIdle { active.value = true }
        rule.onNodeWithContentDescription("音声を再生").assertIsEnabled()
    }

    private fun showPlayer(media: MediaAttachment): AtomicReference<MediaPlayback> {
        val state = AtomicReference<MediaPlayback>()
        rule.setContent { MaterialTheme {
            val playback = rememberMediaPlayback(media.url)
            SideEffect { state.set(playback) }
            Box(Modifier.padding(16.dp)) { AudioPlayerContent(media, playback) }
        } }
        return state
    }

    private fun audio(name: String): MediaAttachment {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("audio-ui-", ".${name.substringAfterLast('.')}", instrumentation.targetContext.cacheDir).also(files::add)
        instrumentation.context.assets.open("audio/$name").use { input -> file.outputStream().use(input::copyTo) }
        return MediaAttachment(name, "audio", file.toURI().toString(), "file:///invalid-preview.png", name)
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
