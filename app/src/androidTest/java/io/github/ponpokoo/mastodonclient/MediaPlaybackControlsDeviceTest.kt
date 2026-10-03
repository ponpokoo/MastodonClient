package io.github.ponpokoo.mastodonclient

import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.feature.media.MediaPlayback
import io.github.ponpokoo.mastodonclient.feature.media.AudioPlayerContent
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.rememberMediaPlayback
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaPlaybackControlsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun mp3SliderSeeksWhilePausedAndSkipButtonsClampToSourceBounds() = checkSeeking("silence.mp3")
    @Test fun wavSliderSeeksWhilePausedAndSkipButtonsClampToSourceBounds() = checkSeeking("tone.wav")

    private fun checkSeeking(name: String) {
        val source = audio(name)
        val state = AtomicReference<MediaPlayback>()
        rule.setContent {
            MaterialTheme {
                val playback = rememberMediaPlayback(source)
                SideEffect { state.set(playback) }
                PlayerControls(playback)
            }
        }
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) { state.get()?.seekable == true && state.get()?.playing == true }
        rule.onNodeWithContentDescription("音声を一時停止").performClick()
        rule.onNodeWithTag("media_seek").performTouchInput {
            swipe(Offset(width * 0.05f, center.y), Offset(width * 0.75f, center.y), 500)
        }
        rule.waitUntil(5_000) { state.get().position in 3_500L..4_100L }
        rule.runOnIdle { assertFalse(state.get().player!!.playWhenReady) }
        rule.onNodeWithContentDescription("5秒戻る").performClick()
        rule.waitUntil(5_000) { state.get().position <= 100 }
        rule.onNodeWithContentDescription("10秒進む").performClick()
        rule.waitUntil(5_000) { kotlin.math.abs(state.get().position - state.get().duration) <= 100 }
        rule.runOnIdle { assertFalse(state.get().player!!.playWhenReady) }
        rule.onNodeWithContentDescription("音声を再生").performClick()
        try {
            rule.waitUntil(5_000) { state.get().playing && state.get().position < 2_000 }
        } catch (failure: Throwable) {
            var diagnostic = ""
            rule.runOnIdle {
                val playback = state.get()
                diagnostic = "position=${playback.position}, duration=${playback.duration}, state=${playback.player?.playbackState}, requested=${playback.player?.playWhenReady}, enabled=${playback.controlsEnabled}"
            }
            throw AssertionError(diagnostic, failure)
        }
    }

    @Test fun seekingToEndWhileLoopingDoesNotResetLaterResumePosition() {
        val source = audio("tone.wav")
        val state = AtomicReference<MediaPlayback>()
        rule.setContent { MaterialTheme {
            val playback = rememberMediaPlayback(source, loop = true)
            SideEffect { state.set(playback) }
            PlayerControls(playback)
        } }
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) { state.get().playing && state.get().seekable }
        rule.onNodeWithContentDescription("10秒進む").performClick()
        rule.waitUntil(5_000) { state.get().playing && state.get().position in 1_000L..2_000L }
        rule.onNodeWithContentDescription("音声を一時停止").performClick()
        var pausedAt = 0L
        rule.runOnIdle { pausedAt = state.get().player!!.currentPosition }
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(5_000) { state.get().playing }
        rule.runOnIdle { assertTrue(state.get().player!!.currentPosition >= pausedAt - 100) }
    }

    @Test fun volumeSetBeforePlaybackReachesPlayerAndMuteRestoresPreviousLevel() {
        val source = audio("tone.wav")
        val state = AtomicReference<MediaPlayback>()
        val manager = rule.activity.getSystemService(AudioManager::class.java)
        val systemVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        rule.setContent { MaterialTheme {
            val playback = rememberMediaPlayback(source)
            SideEffect { state.set(playback) }
            PlayerControls(playback)
        } }
        rule.onNodeWithContentDescription("音声の音量調整").performClick()
        rule.onNodeWithTag("audio_volume_popup").assertIsDisplayed()
        rule.onNodeWithTag("media_volume").performTouchInput { click(Offset(center.x, height * .7f)) }
        val selected = state.get().volume
        assertTrue(selected in 0.2f..0.4f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("audio_volume_popup").assertDoesNotExist()
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) { state.get().playing }
        rule.runOnIdle { assertEquals(selected, state.get().player!!.volume, 0.001f) }
        rule.runOnIdle {
            state.get().toggleMute()
            assertEquals(0f, state.get().player!!.volume, 0.001f)
            state.get().toggleMute()
            assertEquals(selected, state.get().player!!.volume, 0.001f)
            assertEquals(systemVolume, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }

    @Test fun inactivePlayerDisablesSeekingAndDoesNotRestartOnReturn() {
        val source = audio("tone.wav")
        val active = mutableStateOf(true)
        val state = AtomicReference<MediaPlayback>()
        rule.setContent { MaterialTheme {
            val playback = rememberMediaPlayback(source, active = active.value)
            SideEffect { state.set(playback) }
            PlayerControls(playback)
        } }
        rule.onNodeWithTag("media_seek").assertIsNotEnabled()
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) { state.get().playing }
        rule.runOnIdle { active.value = false }
        rule.onNodeWithTag("media_seek").assertIsNotEnabled()
        rule.runOnIdle { assertFalse(state.get().player!!.playWhenReady); active.value = true }
        rule.onNodeWithTag("media_seek").assertIsEnabled()
        rule.runOnIdle { assertFalse(state.get().player!!.playWhenReady) }
    }

    @androidx.compose.runtime.Composable
    private fun PlayerControls(playback: MediaPlayback) {
        AudioPlayerContent(MediaAttachment("fixture", "audio", playback.url, null, null), playback)
    }

    private fun audio(name: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("controls-test-", ".${name.substringAfterLast('.')}", instrumentation.targetContext.cacheDir)
        files.add(file)
        instrumentation.context.assets.open("audio/$name").use { input -> file.outputStream().use(input::copyTo) }
        return file.toURI().toString()
    }
}
