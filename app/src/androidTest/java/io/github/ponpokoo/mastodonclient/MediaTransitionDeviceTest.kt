package io.github.ponpokoo.mastodonclient

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.view.Window
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.media.*
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaTransitionDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val files = mutableListOf<File>()
    private lateinit var motion: MediaTransitionState
    private lateinit var window: Window
    private val session = mutableStateOf("account-A:1")
    private val route = mutableStateOf("timeline")
    private val sourceVisible = mutableStateOf(true)
    private lateinit var items: List<MediaAttachment>

    @After fun cleanup() { files.forEach { it.delete() } }

    private fun image(id: String, width: Int, height: Int): MediaAttachment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("transition-$id-", ".png", context.cacheDir).also(files::add)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return MediaAttachment(id, "image", file.toURI().toString(), file.toURI().toString(), id,
            aspectRatio = width.toFloat() / height)
    }
    private fun status(media: List<MediaAttachment>) = TimelineStatus(
        "post", "post", "2026-10-15T00:00:00Z", StatusAuthor("author", "Example", "example", ""),
        null, "", "", false, "public", null, 0, 0, 0, mediaAttachments = media,
    )

    private fun render(copies: Int = 1, profile: Boolean = false, restoration: StateRestorationTester? = null) {
        items = if (profile) listOf(image("profile-avatar", 200, 200), image("profile-header", 600, 200))
            else listOf(image("portrait", 240, 400), image("landscape", 600, 400))
        val content: @Composable () -> Unit = {
            motion = rememberMediaTransitionState()
            window = LocalContext.current.activity().window
            MaterialTheme {
                MediaTransitionHost(motion, session.value, route.value) {
                    if (sourceVisible.value) {
                        val open: (List<MediaAttachment>, Int) -> Unit = { media, index ->
                            motion.open(media, index, session.value, route.value)
                        }
                        if (profile) {
                            val user = UserProfile(StatusAuthor("author", "Example", "example", items[0].url!!),
                                items[1].url!!, "", 0, 0, 0, emptyList())
                            ProfileContent(state = ProfileUiState(profile = user), padding = PaddingValues(),
                                onRetry = {}, onRefresh = {}, onStatusClick = {}, onOpenLink = {}, onReply = {},
                                onBoost = {}, onQuote = { _, _ -> }, onFavourite = {}, onReact = { _, _ -> },
                                onAccountClick = {}, onMediaClick = open,
                                onAvatarClick = { open(listOf(items[0]), 0) },
                                onHeaderClick = { open(listOf(items[1]), 0) })
                        } else Column(Modifier.fillMaxSize().windowInsetsPadding(mediaContentInsets())
                            .verticalScroll(rememberScrollState())) {
                            repeat(copies) {
                                StatusCard(status(items), onStatusClick = null, onUnavailableAction = {}, onMediaClick = open)
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
        if (restoration == null) rule.setContent(content) else restoration.setContent(content)
        rule.waitUntil(10_000) { motion.thumbnailPainters.size >= items.size }
        rule.waitForIdle()
    }
    private fun phase() = motion.phase
    private fun viewer() = rule.onNodeWithTag("media_viewer")
    private fun viewerConfig() = viewer().fetchSemanticsNode().config
    private fun scale() = viewerConfig()[MediaViewerScale]
    private fun offset() = viewerConfig()[MediaViewerOffset]
    private fun advance(ms: Long) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun openFirst() {
        rule.onAllNodesWithTag("media_attachment")[0].performClick()
        rule.waitUntil(5_000) { phase() == MediaPhase.Viewing }
    }

    @Test fun firstDownwardSwipeDuringHandoffSurvivesTheStartOfViewing() {
        render()
        rule.mainClock.autoAdvance = false
        rule.onAllNodesWithTag("media_attachment")[0].performClick()
        repeat(60) { if (phase() == MediaPhase.Opening) advance(16) }
        assertEquals(MediaPhase.HandingOff, phase())
        val image = viewer()
        image.performTouchInput { down(center); moveTo(center + Offset(0f, height * 0.06f), 16) }
        advance(16)
        val duringHandoff = motion.dragOffset
        assertTrue(duringHandoff > 0f)
        assertTrue(rule.onNodeWithTag("media_transition").fetchSemanticsNode().config[MediaImageBounds].top > 0f)
        advance(160)
        assertEquals(MediaPhase.Viewing, phase())
        assertEquals(duringHandoff, motion.dragOffset, 0.01f)
        image.performTouchInput { moveTo(center + Offset(0f, height * 0.25f), 32); up() }
        advance(500)
        assertEquals(MediaPhase.Closed, phase())
        rule.mainClock.autoAdvance = true
    }

    @Test fun aFingerPlacedWhileOpeningCanDismissWithoutLiftingAndTryingAgain() {
        render()
        rule.mainClock.autoAdvance = false
        rule.onAllNodesWithTag("media_attachment")[0].performClick()
        advance(96)
        assertEquals(MediaPhase.Opening, phase())
        viewer().performTouchInput { down(center) }
        repeat(60) { if (phase() == MediaPhase.Opening) advance(16) }
        viewer().performTouchInput { moveTo(center + Offset(0f, height * 0.25f), 32) }
        advance(16)
        assertTrue(motion.dragOffset > 0f)
        viewer().performTouchInput { up() }
        advance(500)
        assertEquals(MediaPhase.Closed, phase())
        rule.mainClock.autoAdvance = true
    }

    @Test fun movingDoubleTapShrinksAndTheNextDoubleTapEnlargesAfterPanning() {
        render(); openFirst()
        val image = viewer()
        image.performTouchInput { doubleClick(center) }
        image.performTouchInput { down(center); moveTo(center + Offset(width * 0.2f, 0f), 200); up() }
        assertTrue(offset().getDistance() > 0f)
        image.performTouchInput {
            repeat(2) { down(center); moveTo(center + Offset(1f, 0f), 16); up(); advanceEventTime(100) }
        }
        assertEquals(1f, scale(), 0.01f)
        image.performTouchInput { doubleClick(center) }
        assertEquals(2.5f, scale(), 0.01f)
    }

    @Test fun establishedPanContinuesOnReversalAndAfterOneFingerLeavesAPinch() {
        render(); openFirst()
        val image = viewer()
        image.performTouchInput { doubleClick(center) }
        val slop = ViewConfiguration.get(InstrumentationRegistry.getInstrumentation().targetContext).scaledTouchSlop.toFloat()
        image.performTouchInput { down(center); moveTo(center + Offset(slop * 0.5f, 0f), 32) }
        assertEquals(Offset.Zero, offset())
        image.performTouchInput { moveTo(center + Offset(slop * 1.5f, 0f), 32) }
        val afterCrossing = offset().x
        assertTrue(afterCrossing > 0f)
        image.performTouchInput { moveTo(center + Offset(slop * 0.25f, 0f), 32); up() }
        assertTrue(offset().x < afterCrossing)
        image.performTouchInput { doubleClick(center) }
        image.performTouchInput {
            down(0, center - Offset(width * 0.1f, 0f)); down(1, center + Offset(width * 0.1f, 0f))
            updatePointerTo(0, center - Offset(width * 0.25f, 0f)); updatePointerTo(1, center + Offset(width * 0.25f, 0f))
            move(100); up(1)
        }
        val pinched = scale()
        val beforePan = offset().x
        assertTrue(pinched > 1f)
        image.performTouchInput { moveBy(0, Offset(1f, 0f), 16) }
        assertTrue(offset().x > beforePan)
        image.performTouchInput { up(0) }
        assertEquals(pinched, scale(), 0.01f)
    }

    @Test fun secondFingerCancelsDownwardDismissalUntilTheGestureEnds() {
        render(); openFirst()
        val image = viewer()
        image.performTouchInput { down(0, center); moveTo(0, center + Offset(0f, height * 0.2f), 200) }
        assertTrue(motion.dragOffset > 0f)
        image.performTouchInput {
            down(1, center + Offset(width * 0.1f, height * 0.2f))
            updatePointerTo(0, center + Offset(-width * 0.2f, height * 0.2f))
            updatePointerTo(1, center + Offset(width * 0.3f, height * 0.2f)); move(200)
            up(1); moveTo(0, center + Offset(-width * 0.2f, height * 0.3f), 100); up(0)
        }
        assertEquals(MediaPhase.Viewing, phase())
        assertTrue(scale() > 1f)
        assertEquals(0f, motion.dragOffset, 0.01f)
    }

    @Test fun backWhileOpeningCanReverseAndImmediatelyOpenAnotherImage() {
        render()
        rule.mainClock.autoAdvance = false
        rule.onAllNodesWithTag("media_attachment")[0].performClick()
        advance(80)
        assertTrue(motion.progress.value in 0f..0.99f)
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        rule.waitUntil(5_000) { phase() == MediaPhase.Closing }
        advance(32)
        val progress = motion.progress.value
        advance(64)
        assertTrue(motion.progress.value <= progress)
        advance(500)
        assertEquals(MediaPhase.Closed, phase())
        rule.mainClock.autoAdvance = true
        rule.onAllNodesWithTag("media_attachment")[1].performClick()
        rule.waitUntil(5_000) { phase() == MediaPhase.Viewing }
        assertEquals(1, motion.page)
        assertEquals(MediaPhase.Viewing, phase())
    }

    @Test fun restoredViewerKeepsThePageAndFadesWithoutAnOldSource() {
        val restoration = StateRestorationTester(rule)
        render(restoration = restoration); openFirst()
        viewer().performTouchInput { swipeLeft() }
        assertEquals(1, viewerConfig()[MediaViewerPage])
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(MediaPhase.Viewing, phase())
        assertEquals(1, viewerConfig()[MediaViewerPage])
        assertNull(motion.transitionThumbnail)
        rule.onNodeWithTag("media_viewer_close").performClick()
        rule.waitForIdle()
        assertEquals(MediaPhase.Closed, phase())
    }

    @Test fun pagingReturnsToTheLastImageInTheClickedCopyOfTheGallery() {
        render(copies = 2)
        val thumbnail = rule.onAllNodesWithTag("media_attachment")[2]
        thumbnail.performScrollTo().performClick()
        rule.waitUntil(5_000) { phase() == MediaPhase.Viewing }
        viewer().performTouchInput { swipeLeft() }
        assertEquals(1, viewerConfig()[MediaViewerPage])
        val group = motion.sourceGroup
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("media_viewer_close").performClick()
        advance(200)
        assertTrue(motion.selectedKey.startsWith(group))
        val returnFrame = motion.transitionThumbnail
        assertNotNull(returnFrame)
        assertEquals(rule.onAllNodesWithTag("media_attachment")[3].fetchSemanticsNode().boundsInRoot, returnFrame!!.visibleBounds)
        advance(600)
        assertEquals(MediaPhase.Closed, phase())
        rule.mainClock.autoAdvance = true
    }

    @Test fun removedReturnSourceFadesAndChangingAccountOrRouteClosesTheViewer() {
        render(); openFirst()
        rule.runOnIdle { sourceVisible.value = false }
        rule.onNodeWithTag("media_viewer_close").performClick()
        rule.waitForIdle()
        assertEquals(MediaPhase.Closed, phase())
        rule.runOnIdle { sourceVisible.value = true }
        rule.waitUntil(10_000) { motion.thumbnailPainters.size >= items.size }
        openFirst()
        rule.runOnIdle { session.value = "account-A:3" }
        rule.onNodeWithTag("media_viewer").assertDoesNotExist()
        assertTrue(motion.media.isEmpty())
        rule.waitUntil(10_000) { motion.thumbnailPainters.size >= items.size }
        openFirst()
        rule.runOnIdle { route.value = "different-screen" }
        rule.onNodeWithTag("media_viewer").assertDoesNotExist()
    }

    @Test fun togglingSystemBarsAndClosingKeepsSourceBoundsAndRestoresAppearance() {
        render()
        val before = rule.onAllNodesWithTag("media_attachment")[0].fetchSemanticsNode().boundsInRoot
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val appearance = controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
        openFirst()
        viewer().performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("media_viewer_close").fetchSemanticsNodes().isEmpty() }
        assertEquals(before, motion.thumbnailFrames[motion.selectedKey]!!.visibleBounds)
        viewer().performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("media_viewer_close").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("media_viewer_close").performClick()
        rule.waitForIdle()
        assertEquals(before, rule.onAllNodesWithTag("media_attachment")[0].fetchSemanticsNode().boundsInRoot)
        assertEquals(appearance.first, controller.isAppearanceLightStatusBars)
        assertEquals(appearance.second, controller.isAppearanceLightNavigationBars)
    }

    @Test fun profileAvatarAndHeaderOpenFromTheirOwnSources() {
        render(profile = true)
        for (description in listOf("プロフィール画像", "ヘッダー画像")) {
            rule.onNodeWithContentDescription(description).performClick()
            rule.waitUntil(5_000) { phase() == MediaPhase.Viewing }
            assertNotNull(motion.transitionThumbnail)
            assertEquals(if (description == "プロフィール画像") "profile-avatar" else "profile-header", motion.media[0].id)
            rule.onNodeWithTag("media_viewer_close").performClick()
            rule.waitForIdle()
        }
    }

    @Test fun savingStillLaunchesCreateDocumentAndCancellationKeepsTheViewerOpen() {
        render(); openFirst()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(filter, ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            rule.onNodeWithContentDescription("メディアを保存").performClick()
            rule.waitUntil(5_000) { monitor.hits > 0 }
            assertEquals(MediaPhase.Viewing, phase())
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun Context.activity(): Activity {
        var current = this
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        return current as Activity
    }
}
