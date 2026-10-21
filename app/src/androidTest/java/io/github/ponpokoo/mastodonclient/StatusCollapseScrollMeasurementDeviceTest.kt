package io.github.ponpokoo.mastodonclient

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in Debug diagnostic with a real frame clock, not a release benchmark or a CI performance gate. */
@SdkSuppress(minSdkVersion = 31)
class StatusCollapseScrollMeasurementDeviceTest {
    @Test fun compareShortAndOccasionalLongPosts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureStatusCollapse") == "true")
        val list = LazyListState()
        val enabled = mutableStateOf(false)
        val mixed = mutableStateOf(false)
        val frames = Collections.synchronizedList(mutableListOf<List<Long>>())
        val recording = AtomicBoolean(false)
        val worker = HandlerThread("collapse-frame-metrics").apply { start() }
        val handler = Handler(worker.looper)
        val csv = StringBuilder("fixture,enabled,round,frame,totalNs,layoutNs,drawNs,deadlineNs,callbackDrops\n")
        val listener = Window.OnFrameMetricsAvailableListener { _, metric, drops ->
            if (recording.get()) frames.add(listOf(metric.getMetric(FrameMetrics.TOTAL_DURATION),
                metric.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), metric.getMetric(FrameMetrics.DRAW_DURATION),
                metric.getMetric(FrameMetrics.DEADLINE), drops.toLong()))
        }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var animationScope: CoroutineScope
                lateinit var fling: FlingBehavior
                scenario.onActivity {
                    it.window.addOnFrameMetricsAvailableListener(listener, handler)
                    it.setContent {
                        MaterialTheme {
                            val behavior = ScrollableDefaults.flingBehavior()
                            val scope = rememberCoroutineScope()
                            SideEffect { fling = behavior; animationScope = scope }
                            key(enabled.value, mixed.value) {
                                LazyColumn(Modifier.width(320.dp).height(600.dp), state = list) {
                                    items(60, key = { "row-$it" }) { index ->
                                        StatusCard(fixture(index, mixed.value), onStatusClick = {}, onUnavailableAction = {},
                                            collapseLongContent = enabled.value, authorAvatarResource = R.drawable.nagisa_launcher_art)
                                    }
                                }
                            }
                        }
                    }
                }
                fun scroll(measure: Boolean) {
                    val done = CompletableDeferred<Unit>()
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        animationScope.launch {
                            try {
                                list.scrollToItem(0)
                                if (measure) { frames.clear(); recording.set(true) }
                                list.animateScrollBy(2200f, tween(900))
                                list.scroll { with(fling) { performFling(7500f) } }
                                done.complete(Unit)
                            } catch (error: Throwable) { done.completeExceptionally(error) }
                        }
                    }
                    runBlocking { withTimeout(15_000) { done.await() } }
                    SystemClock.sleep(150) // Drain the final presented frames on the separate callback thread.
                    recording.set(false)
                }
                for (mix in listOf(false, true)) {
                    for (on in listOf(false, true)) {
                        InstrumentationRegistry.getInstrumentation().runOnMainSync { mixed.value = mix; enabled.value = on }
                        SystemClock.sleep(400)
                        scroll(false)
                    }
                    repeat(3) { round ->
                        for (on in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                            InstrumentationRegistry.getInstrumentation().runOnMainSync { enabled.value = on }
                            SystemClock.sleep(300)
                            scroll(true)
                            val snapshot = synchronized(frames) { frames.toList() }
                            check(snapshot.isNotEmpty()) { "No frame metrics" }
                            snapshot.forEachIndexed { index, values ->
                                csv.appendLine("${if (mix) "mixed" else "short"},$on,$round,$index,${values.joinToString(",")}")
                            }
                        }
                    }
                }
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "status-collapse-frames.csv").writeText(csv.toString())
        } finally { recording.set(false); worker.quitSafely() }
    }

    private fun fixture(index: Int, mixed: Boolean) = TimelineStatus(
        timelineId = "row-$index", statusId = "status-$index", createdAt = "2026-10-21T00:00:00Z",
        author = StatusAuthor("author", "なぎさ", "nagisa@example.test", ""), boostedBy = null,
        contentHtml = "<p>投稿$index：" + if (mixed && index % 10 == 0) {
            "日本語と English の長文です。幅に応じて折り返します。 ".repeat(60) + "</p>"
        } else "短い投稿です。</p>",
        spoilerText = "", sensitive = false, visibility = "public", url = null,
        repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
    )
}
