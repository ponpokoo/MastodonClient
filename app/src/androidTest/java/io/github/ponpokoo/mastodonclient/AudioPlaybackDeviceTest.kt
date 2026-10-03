package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

class AudioPlaybackDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun mp3DisplaysDecodesPausesEndsAndReplays() = exercise("silence.mp3")
    @Test fun wavDisplaysDecodesPausesEndsAndReplays() = exercise("tone.wav")

    private fun exercise(name: String) {
        val media = audio(name)
        rule.setContent { MaterialTheme { Post("post", media) } }
        rule.onNodeWithTag("audio_attachment").assertIsDisplayed()
        play("post")
        waitState("post", "再生中")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("0:01 /", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("音声を一時停止")).performClick()
        waitState("post", "一時停止")
        play("post")
        waitState("post", "再生終了")
        play("post")
        waitState("post", "再生中")
    }

    @Test fun selectingAnotherPostPausesPreviousAudioAndBackgroundDoesNotResumeIt() {
        val first = audio("tone.wav")
        val second = audio("silence.mp3")
        rule.setContent { MaterialTheme { Column { Post("first", first); Post("second", second) } } }
        play("first")
        waitState("first", "再生中")
        play("second")
        waitState("first", "一時停止")
        waitState("second", "再生中")
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitState("second", "一時停止")
    }

    @Test fun scrollingOffscreenPausesEvenWhenPostRemainsComposed() {
        val media = audio("tone.wav")
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("scroll")) {
                    Post("post", media)
                    Spacer(Modifier.height(1600.dp))
                    Box(Modifier.height(40.dp).testTag("bottom"))
                }
            }
        }
        play("post")
        waitState("post", "再生中")
        rule.onNodeWithTag("bottom").performScrollTo()
        waitState("post", "一時停止")
        rule.onNodeWithTag("post").performScrollTo()
        waitState("post", "一時停止")
    }

    @Test fun missingOriginalUrlDoesNotPlayPreview() {
        val media = audio("tone.wav").copy(url = null)
        rule.setContent { MaterialTheme { Post("post", media) } }
        rule.onNodeWithContentDescription("音声を再生").assertIsNotEnabled()
        rule.onNodeWithText("音声URLがありません").assertExists()
    }

    @Test fun fullScreenAudioAlsoUsesCommonPlayer() {
        val media = audio("tone.wav")
        rule.setContent { MaterialTheme { MediaViewerScreen(listOf(media), 0, {}) } }
        rule.onNodeWithTag("audio_attachment").assertIsDisplayed()
        rule.onNodeWithContentDescription("音声を再生").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "再生中"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun sourceFailureShowsRetryAndCanBeDisposed() {
        val shown = mutableStateOf(true)
        val media = audio("tone.wav").copy(url = "file:///nonexistent/nagisa-audio.wav")
        rule.setContent { MaterialTheme { if (shown.value) Post("post", media) } }
        play("post")
        waitState("post", "音声を再生できません。再試行してください")
        play("post")
        waitState("post", "音声を再生できません。再試行してください")
        rule.runOnIdle { shown.value = false }
        rule.onNodeWithTag("audio_attachment").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun Post(tag: String, attachment: MediaAttachment) {
        Box(Modifier.testTag(tag)) {
            StatusCard(TimelineStatus(
                timelineId = tag, statusId = tag, createdAt = "2026-10-03T00:00:00Z",
                author = StatusAuthor("author", "Example", "example", ""), boostedBy = null,
                contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = listOf(attachment),
            ), onStatusClick = null, onUnavailableAction = {})
        }
    }

    private fun play(tag: String) = rule.onNode(hasContentDescription("音声を再生") and hasAnyAncestor(hasTestTag(tag))).performClick()
    private fun waitState(tag: String, state: String) = rule.waitUntil(15_000) {
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)
            and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()
    }
    private fun audio(name: String): MediaAttachment {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("audio-test-", ".${name.substringAfterLast('.')}", instrumentation.targetContext.cacheDir)
        files.add(file)
        instrumentation.context.assets.open("audio/$name").use { input -> file.outputStream().use(input::copyTo) }
        return MediaAttachment(name, "audio", file.toURI().toString(), "file:///invalid-cover.png", name)
    }
}
