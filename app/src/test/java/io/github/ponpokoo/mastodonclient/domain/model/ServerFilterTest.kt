package io.github.ponpokoo.mastodonclient.domain.model

import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ServerFilterTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val warn = ServerFilter("opaque", "ネタバレ", setOf("notifications"))
    @Test fun evaluatesContextExpiryUnknownActionAndHidePrecedence() {
        val status = testStatus().copy(filtered = listOf(StatusFilterResult(warn, statusMatches = listOf("post")),
            StatusFilterResult(warn.copy(id = "expired", action = "hide", expiresAt = now.toString())),
            StatusFilterResult(warn.copy(id = "unknown", action = "extension"))))
        assertEquals(listOf("opaque", "unknown"), status.filterPresentation("notifications", now).warnings.map { it.id })
        assertEquals(FilterPresentation(), status.filterPresentation("home", now))
        assertTrue(status.copy(filtered = status.filtered!! + StatusFilterResult(warn.copy(id = "hide", action = "hide")))
            .filterPresentation("notifications", now).hidden)
    }
    @Test fun deletedRulesStopApplyingAndBlurOnlyHidesMedia() {
        val status = testStatus().copy(filtered = listOf(StatusFilterResult(warn)))
        assertTrue(status.filterPresentation("notifications", now, emptyList()).warnings.isEmpty())
        val blur = status.filterPresentation("notifications", now, listOf(warn.copy(action = "blur")))
        assertFalse(blur.hidden); assertTrue(blur.warnings.isEmpty()); assertEquals("opaque", blur.mediaWarnings.single().id)
    }
    @Test fun cacheKeepsMatchesAndReadsOldStatusesWithoutFiltered() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val status = testStatus().copy(filtered = listOf(StatusFilterResult(warn, listOf("secret"), listOf("post"))),
            filterDisplay = FilterPresentation(warnings = listOf(warn)), filterScope = "account-a")
        val restored = json.decodeFromString<TimelineStatus>(json.encodeToString(status))
        assertEquals(status.filtered, restored.filtered)
        assertEquals(FilterPresentation(), restored.filterDisplay)
        assertEquals("", restored.filterScope)
        assertNull(json.decodeFromString<TimelineStatus>(json.encodeToString(testStatus())).filtered)
    }
}
