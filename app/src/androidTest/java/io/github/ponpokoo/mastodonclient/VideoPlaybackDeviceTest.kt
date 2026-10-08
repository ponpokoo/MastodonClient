package io.github.ponpokoo.mastodonclient

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.media.VideoPlayer
import io.github.ponpokoo.mastodonclient.feature.media.MediaPlayback
import io.github.ponpokoo.mastodonclient.feature.media.MediaPlaybackControls
import io.github.ponpokoo.mastodonclient.feature.media.rememberMediaPlayback
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

class VideoPlaybackDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun fullScreenVideoStillDecodesAndPlays() {
        val media = video()
        rule.setContent { MaterialTheme { MediaViewerScreen(listOf(media), 0, {}) } }
        waitForVideoState("再生中")
        rule.onNodeWithContentDescription("動画を一時停止").performClick()
        waitForVideoState("一時停止")
        rule.onNodeWithTag("media_seek").performTouchInput { swipe(Offset(width * 0.05f, center.y), Offset(width * 0.6f, center.y), 500) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("0:05 /", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasText("0:06 /", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("動画の音量調整").performClick()
        rule.onNodeWithTag("video_volume_popup").assertIsDisplayed()
        rule.onNodeWithTag("media_volume").performTouchInput {
            swipe(Offset(center.x, height * .85f), Offset(center.x, height * .55f), 400)
        }
        val lowered = volumeLevel()
        assertTrue(lowered > 0f && lowered < .7f)
        rule.onNodeWithTag("media_volume").performTouchInput {
            swipe(Offset(center.x, height * .55f), Offset(center.x, height * .1f), 400)
        }
        assertTrue(volumeLevel() > lowered + .2f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("video_volume_popup").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
            File(instrumentation.targetContext.filesDir, "media-controls-video.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForVideoState("再生中")
        rule.onNodeWithContentDescription("動画を一時停止").performClick()
        rule.onNodeWithTag("media_seek").performTouchInput { swipe(Offset(width * 0.05f, center.y), Offset(width * 0.95f, center.y), 500) }
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForVideoState("再生終了")
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForVideoState("再生中")
    }

    private fun waitForVideoState(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
            and hasTestTag("video_player")).fetchSemanticsNodes().isNotEmpty()
    }

    private fun volumeLevel() = rule.onNodeWithTag("media_volume").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test fun landscapeVolumeButtonStaysAtSameScreenPositionAcrossPopupAndMute() =
        checkLandscapeVolumeAnchor(UiAutomation.ROTATION_FREEZE_90)

    @Test fun reverseLandscapeVolumePopupAvoidsCameraCutoutAndKeepsAnchor() =
        checkLandscapeVolumeAnchor(UiAutomation.ROTATION_FREEZE_270)

    private fun checkLandscapeVolumeAnchor(rotation: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val media = video(seconds = 20)
        val viewerView = AtomicReference<View>()
        try {
            assertTrue(automation.setRotation(rotation))
            rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rule.setContent { MaterialTheme {
                Dialog(onDismissRequest = {}, properties = DialogProperties(
                    usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                )) {
                    val view = LocalView.current
                    SideEffect { viewerView.set(view) }
                    Box(Modifier.fillMaxSize()) { MediaViewerScreen(listOf(media), 0, {}) }
                }
            } }
            waitForVideoState("再生中")
            rule.onNodeWithContentDescription("動画を一時停止").performTouchInput { click(center) }
            waitForVideoState("一時停止")
            val cutout = rule.runOnIdle { ViewCompat.getRootWindowInsets(viewerView.get())?.displayCutout }
            val leftInset = cutout?.safeInsetLeft ?: 0
            val rightInset = cutout?.safeInsetRight ?: 0
            val screenWidth = rule.runOnIdle { viewerView.get().resources.displayMetrics.widthPixels }
            println("Video cutout rotation=$rotation left=$leftInset right=$rightInset")
            assertInsideHorizontalSafeArea(rule.onNodeWithContentDescription("動画を再生").fetchSemanticsNode().layoutInfo.coordinates,
                leftInset, screenWidth - rightInset)
            assertInsideHorizontalSafeArea(rule.onNodeWithContentDescription("動画の音量調整").fetchSemanticsNode().layoutInfo.coordinates,
                leftInset, screenWidth - rightInset)
            val originalPosition = volumeButtonScreenCenter()
            val seekBounds = rule.onNodeWithTag("media_seek").fetchSemanticsNode().boundsInRoot
            rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
            rule.onNodeWithTag("video_volume_popup").assertIsDisplayed()
            assertInsideHorizontalSafeArea(rule.onNodeWithTag("video_volume_popup").fetchSemanticsNode().layoutInfo.coordinates,
                leftInset, screenWidth - rightInset)
            assertVolumeButtonPosition(originalPosition)
            automation.takeScreenshot().let { bitmap ->
                File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "video-volume-cutout-$rotation.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            // This is a real tap at the original screen location, rather than the relocated node.
            tapScreen(originalPosition)
            rule.onNodeWithContentDescription("動画の音量調整")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ミュート"))
            assertVolumeButtonPosition(originalPosition)
            tapScreen(originalPosition)
            rule.onNodeWithContentDescription("動画の音量調整")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "音量あり"))
            rule.onNodeWithTag("media_volume").performTouchInput {
                swipe(Offset(center.x, height * .85f), Offset(center.x, height * .55f), 400)
            }
            assertTrue(volumeLevel() < 1f)
            assertVolumeButtonPosition(originalPosition)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.onNodeWithTag("video_volume_popup").assertDoesNotExist()
            assertVolumeButtonPosition(originalPosition)
            assertEquals(seekBounds, rule.onNodeWithTag("media_seek").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("media_seek").performTouchInput {
                swipe(Offset(width * .25f, center.y), Offset(width * .6f, center.y), 400)
            }
            rule.waitUntil(5_000) {
                rule.onNodeWithTag("media_seek").fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo].current > .4f
            }
            assertVolumeButtonPosition(originalPosition)
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun assertInsideHorizontalSafeArea(coordinates: androidx.compose.ui.layout.LayoutCoordinates,
        safeLeft: Int, safeRight: Int) {
        val left = coordinates.localToScreen(Offset.Zero).x
        val right = coordinates.localToScreen(Offset(coordinates.size.width.toFloat(), 0f)).x
        assertTrue("Control at $left must avoid the left camera inset $safeLeft", left >= safeLeft - 1f)
        assertTrue("Control at $right must avoid the right camera inset $safeRight", right <= safeRight + 1f)
    }

    private fun volumeButtonScreenCenter(): Offset {
        val node = rule.onNodeWithContentDescription("動画の音量調整").fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        return coordinates.localToScreen(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
    }

    private fun assertVolumeButtonPosition(expected: Offset) {
        val actual = volumeButtonScreenCenter()
        assertEquals("Volume button must stay at its original horizontal screen position", expected.x, actual.x, 1f)
        assertEquals("Volume button must stay at its original vertical screen position", expected.y, actual.y, 1f)
    }

    private fun tapScreen(position: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                position.x, position.y, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        rule.waitForIdle()
    }

    @Test fun portraitVideoTapTogglesControlsButControlTouchesKeepThemVisible() = checkControlsToggle(landscape = false)
    @Test fun landscapeVideoTapTogglesCompactControlsWithoutResizingVideo() = checkControlsToggle(landscape = true)

    private fun checkControlsToggle(landscape: Boolean) {
        val media = video(seconds = 20)
        var closed = 0
        rule.setContent { MaterialTheme {
            Dialog(onDismissRequest = {}, properties = DialogProperties(
                dismissOnBackPress = false, dismissOnClickOutside = false,
                usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            )) {
                Box(Modifier.size(width = 360.dp, height = if (landscape) 240.dp else 640.dp)) {
                    MediaViewerScreen(listOf(media), 0, { closed++ })
                }
            }
        } }
        waitForVideoState("再生中")
        rule.onNodeWithTag("video_controls").assertIsDisplayed()
        rule.onNodeWithContentDescription("閉じる").assertIsDisplayed()
        rule.onNodeWithContentDescription("メディアを保存").assertIsDisplayed()
        val frameBefore = rule.onNodeWithTag("video_surface").fetchSemanticsNode().boundsInRoot.size
        if (landscape) {
            val band = rule.onNodeWithTag("video_controls").fetchSemanticsNode().boundsInRoot
            val viewport = rule.onNodeWithTag("video_player").fetchSemanticsNode().boundsInRoot
            assertTrue("Landscape controls must leave most of the video unobscured", band.height < viewport.height / 3)
            assertTrue("Controls belong near the bottom edge", band.bottom > viewport.bottom - viewport.height / 4)
        }
        rule.onNodeWithTag("video_tap_target").performTouchInput { click(Offset(width / 2f, height * .2f)) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("video_controls")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("閉じる").assertDoesNotExist()
        rule.onNodeWithContentDescription("メディアを保存").assertDoesNotExist()
        waitForVideoState("再生中")
        val frameHidden = rule.onNodeWithTag("video_surface").fetchSemanticsNode().boundsInRoot.size
        assertEquals(frameBefore.width, frameHidden.width, .5f)
        assertEquals(frameBefore.height, frameHidden.height, .5f)
        // The actual dialog drives system-bar callbacks, which must not immediately re-show the UI.
        rule.onNodeWithTag("video_controls").assertDoesNotExist()
        rule.onNodeWithTag("video_tap_target").performTouchInput { click(Offset(width / 2f, height * .2f)) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("video_controls")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("閉じる").assertIsDisplayed()
        rule.onNodeWithContentDescription("メディアを保存").assertIsDisplayed()
        rule.onNodeWithContentDescription("動画を一時停止").performTouchInput { click(center) }
        waitForVideoState("一時停止")
        rule.onNodeWithTag("video_controls").assertIsDisplayed()
        // Start at the track's middle, away from edge margins that grow as the safe area gets narrower.
        rule.onNodeWithTag("media_seek").performTouchInput { click(center) }
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("media_seek").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current > .35f
        }
        rule.onNodeWithTag("media_seek").performTouchInput {
            swipe(center, Offset(width * .8f, center.y), 400)
        }
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("media_seek").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current > .65f
        }
        rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
        rule.onNodeWithTag("video_volume_popup").assertIsDisplayed()
        rule.onNodeWithTag("media_volume").performTouchInput {
            swipe(Offset(center.x, height * .85f), Offset(center.x, height * .55f), 400)
        }
        assertTrue(volumeLevel() < 1f)
        rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
        rule.onNodeWithContentDescription("動画の音量調整")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ミュート"))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("video_volume_popup").assertDoesNotExist()
        // A tap in the band's padding must not count as a tap on the video.
        rule.onNodeWithTag("video_controls").performTouchInput { click(Offset(2f, 2f)) }
        rule.onNodeWithTag("video_controls").assertIsDisplayed()
        rule.onNodeWithContentDescription("閉じる").assertIsDisplayed()
        waitForVideoState("一時停止")
        rule.runOnIdle { assertEquals(0, closed) }
        if (landscape) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(instrumentation.targetContext.filesDir, "video-controls-landscape-ui.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun repeatedVolumeButtonTapsMuteAndRestorePlayerWhilePopupRemainsOpen() {
        val media = video()
        val state = AtomicReference<MediaPlayback>()
        rule.setContent { MaterialTheme {
            val playback = rememberMediaPlayback(media.url)
            SideEffect { state.set(playback) }
            Surface(color = Color.Black, contentColor = Color.White) {
                Box(Modifier.size(width = 360.dp, height = 240.dp).then(playback.visibilityModifier)) {
                    MediaPlaybackControls(playback, label = "動画")
                }
            }
        } }
        rule.runOnIdle {
            state.get().changeVolume(.4f)
            state.get().prepare()
        }
        // Use actual touch input: a semantics click would bypass the popup's separate window.
        rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
        rule.onNodeWithTag("video_volume_popup").assertIsDisplayed()
        rule.runOnIdle { assertEquals(.4f, state.get().player!!.volume, .001f) }
        rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
        rule.onNodeWithTag("video_volume_popup").assertIsDisplayed()
        rule.onNodeWithContentDescription("動画の音量調整")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ミュート"))
        rule.runOnIdle { assertEquals(0f, state.get().player!!.volume, .001f) }
        rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(.4f, state.get().player!!.volume, .001f) }
        rule.onNodeWithContentDescription("動画の音量調整").performTouchInput { click(center) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("video_volume_popup").assertDoesNotExist()
        rule.onNodeWithContentDescription("動画の音量調整")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ミュート"))
    }

    @Test fun controlsRemainOperableInAShortVideoViewport() {
        val media = video()
        rule.setContent { MaterialTheme {
            Box(Modifier.size(width = 360.dp, height = 240.dp)) { VideoPlayer(media.url, loop = false, active = true) }
        } }
        waitForVideoState("再生中")
        rule.onNodeWithContentDescription("動画を一時停止").performClick()
        rule.onNodeWithContentDescription("動画の音量調整").performClick()
        rule.onNodeWithTag("video_volume_popup").assertIsDisplayed()
        rule.onNodeWithTag("media_volume").performTouchInput {
            swipe(Offset(center.x, height * .85f), Offset(center.x, height * .55f), 400)
        }
        assertTrue(volumeLevel() < 1f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("video_volume_popup").assertDoesNotExist()
        rule.onNodeWithTag("media_seek").performTouchInput { swipe(Offset(width * 0.05f, center.y), Offset(width * 0.6f, center.y), 500) }
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForVideoState("再生中")
    }

    @Test fun inlineGifStillAutoplaysAndLoops() {
        val media = video(seconds = 1).copy(type = "gifv")
        rule.setContent { MaterialTheme {
            StatusCard(TimelineStatus(
                timelineId = "video", statusId = "video", createdAt = "2026-10-03T00:00:00Z",
                author = StatusAuthor("author", "Example", "example", ""), boostedBy = null,
                contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = listOf(media),
            ), onStatusClick = null, onUnavailableAction = {}, gifAutoplay = AutoplayPolicy.Always)
        } }
        waitForPlayback()
        // The one-second clip must continue past its first playback.
        Thread.sleep(1500)
        rule.runOnIdle { assertTrue(findVideo(rule.activity.window.decorView)?.isPlaying == true) }
        rule.onNodeWithContentDescription("動画を一時停止").performClick()
        rule.runOnIdle { assertTrue(findVideo(rule.activity.window.decorView)?.isPlaying == false) }
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForPlayback()
    }

    @Test fun inlineAutoplayPausesAndResumesWithoutOpeningTheViewer() {
        val media = video()
        var viewerOpens = 0
        rule.setContent { MaterialTheme {
            StatusCard(TimelineStatus(
                timelineId = "video", statusId = "video", createdAt = "2026-10-03T00:00:00Z",
                author = StatusAuthor("author", "Example", "example", ""), boostedBy = null,
                contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = listOf(media),
            ), onStatusClick = null, onUnavailableAction = {}, videoAutoplay = AutoplayPolicy.Always,
                onMediaClick = { _, _ -> viewerOpens++ })
        } }
        waitForPlayback()
        rule.onNodeWithContentDescription("動画を一時停止").performClick()
        rule.runOnIdle {
            assertTrue(findVideo(rule.activity.window.decorView)?.isPlaying == false)
            assertEquals(0, viewerOpens)
        }
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForPlayback()
        rule.runOnIdle { assertEquals(0, viewerOpens) }
        rule.onNodeWithTag("media_attachment").performClick()
        rule.runOnIdle { assertEquals(1, viewerOpens) }
    }

    @Test fun inlineAutoplayPausesInBackgroundAndDoesNotResumeUntilPressed() {
        val media = video().copy(type = "gifv")
        rule.setContent { MaterialTheme {
            StatusCard(TimelineStatus(
                timelineId = "video", statusId = "video", createdAt = "2026-10-03T00:00:00Z",
                author = StatusAuthor("author", "Example", "example", ""), boostedBy = null,
                contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = listOf(media),
            ), onStatusClick = null, onUnavailableAction = {}, gifAutoplay = AutoplayPolicy.Always)
        } }
        waitForPlayback()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithContentDescription("動画を再生").assertIsEnabled()
        rule.runOnIdle { assertTrue(findVideo(rule.activity.window.decorView)?.isPlaying == false) }
        rule.onNodeWithContentDescription("動画を再生").performClick()
        waitForPlayback()
    }

    @Test fun fullScreenGifLoopsWithCommonControls() {
        val media = video(seconds = 1).copy(type = "gifv")
        rule.setContent { MaterialTheme { MediaViewerScreen(listOf(media), 0, {}) } }
        waitForVideoState("再生中")
        Thread.sleep(1500)
        rule.onNodeWithContentDescription("動画を一時停止").performClick()
        waitForVideoState("一時停止")
        rule.onNodeWithTag("media_seek").assertIsEnabled()
    }

    @Test fun pagingFromVideoToAudioPausesPreviousPlayerAndReturningDoesNotAutoplay() {
        val video = video()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("page-audio-", ".wav", instrumentation.targetContext.cacheDir).also(files::add)
        instrumentation.context.assets.open("audio/tone.wav").use { input -> file.outputStream().use(input::copyTo) }
        val audio = MediaAttachment("audio", "audio", file.toURI().toString(), null, "audio")
        rule.setContent { MaterialTheme { MediaViewerScreen(listOf(video, audio), 0, {}) } }
        waitForVideoState("再生中")
        rule.onNodeWithTag("media_viewer").performTouchInput { swipe(Offset(width * 0.9f, height * 0.25f), Offset(width * 0.1f, height * 0.25f), 500) }
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "再生中")
                and hasTestTag("audio_attachment")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("media_viewer").performTouchInput { swipe(Offset(width * 0.1f, height * 0.25f), Offset(width * 0.9f, height * 0.25f), 500) }
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "一時停止")
                and hasTestTag("video_player")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("動画を再生").assertIsEnabled()
    }

    private fun waitForPlayback() = rule.waitUntil(10_000) {
        var playing = false
        rule.runOnIdle {
            val view = findVideo(rule.activity.window.decorView)
            playing = view != null && view.isPlaying && view.currentPosition > 0
        }
        playing
    }

    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findVideo(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    // Generate a tiny local H.264 fixture rather than relying on a remote clip.
    private fun video(seconds: Int = 10): MediaAttachment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("video-test-", ".mp4", context.cacheDir).also(files::add)
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 64, 64).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0
            var track = -1
            var done = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 10_000
            while (!done && System.currentTimeMillis() < deadline) {
                if (frame <= 30 * seconds) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val end = frame == 30 * seconds
                        if (!end) codec.getInputBuffer(input)!!.put(ByteArray(64 * 64 * 3 / 2) { 128.toByte() })
                        codec.queueInputBuffer(input, 0, if (end) 0 else 64 * 64 * 3 / 2,
                            frame * 1_000_000L / 30, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frame++
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
            check(done) { "Video fixture encoding timed out" }
        } finally {
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
        return MediaAttachment("video", "video", file.toURI().toString(), null, "video", aspectRatio = 1f)
    }
}
