package io.github.ponpokoo.mastodonclient.feature.common

import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.data.local.AppLicensesLocalDataSource
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAppLicensesRepository
import io.github.ponpokoo.mastodonclient.domain.model.AppLicenseNotice
import io.github.ponpokoo.mastodonclient.domain.repository.AppLicensesRepository
import io.github.ponpokoo.mastodonclient.feature.settings.LicensesViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.licenseTextChunks
import io.github.ponpokoo.mastodonclient.feature.settings.licenseTextBlocks
import java.io.FileNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LicensesViewModelTest : ScreenViewModelTestBase() {
    @Test fun reopeningLicenseDocumentDoesNotDuplicateAnInFlightOrCompletedLoad() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<AppLicenseNotice>>()
        var reads = 0
        val model = own(LicensesViewModel(object : AppLicensesRepository {
            override suspend fun readNotices(): List<AppLicenseNotice> { reads++; return pending.await() }
        }))
        model.load()
        model.load()
        advanceUntilIdle()
        assertEquals(1, reads)
        assertTrue(model.uiState.value.loading)
        val notices = listOf(AppLicenseNotice("app", "App", "", "Apache-2.0", "Full license"))
        pending.complete(notices)
        advanceUntilIdle()
        model.load()
        advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals(notices, model.uiState.value.notices)
        assertFalse(model.uiState.value.loading)
    }

    @Test fun missingBundledDocumentIsShownAsFailureAndCanBeRetried() = runTest(dispatcher) {
        val assets = mutableMapOf(
            "licenses/index.json" to """{"futureField":true,"entries":[{"id":"app","title":"App","license":"Apache-2.0","file":"app.txt"}]}""",
        )
        val local = AppLicensesLocalDataSource({ path -> assets[path] ?: throw FileNotFoundException(path) }, dispatcher)
        val model = own(LicensesViewModel(DefaultAppLicensesRepository(local)))
        model.load()
        advanceUntilIdle()
        assertTrue(model.uiState.value.failed)
        assertFalse(model.uiState.value.loading)
        assertTrue(model.uiState.value.notices.isEmpty())
        assets["licenses/app.txt"] = "License body\nCopyright holder\nUnicode: 🐈"
        model.load()
        advanceUntilIdle()
        assertFalse(model.uiState.value.failed)
        assertEquals(assets["licenses/app.txt"], model.uiState.value.notices.single().text)
    }

    @Test fun disposalCancelsLoadingWithoutTurningCancellationIntoAnError() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<AppLicenseNotice>>()
        val model = own(LicensesViewModel(object : AppLicensesRepository {
            override suspend fun readNotices(): List<AppLicenseNotice> = pending.await()
        }))
        model.load()
        advanceUntilIdle()
        model.viewModelScope.cancel()
        pending.complete(emptyList())
        advanceUntilIdle()
        assertFalse(model.uiState.value.failed)
        assertTrue(model.uiState.value.notices.isEmpty())
    }

    @Test fun largeNoticesKeepEveryCharacterAndDoNotSplitUnicodePairs() {
        val text = "a".repeat(3999) + "🐈" + "b".repeat(18000) + "\n" + "A notice line\n".repeat(2000)
        val chunks = licenseTextChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun sharedDocumentsKeepAllComponentNamesAndDistinctCopyrightNotices() {
        val shared = "Common license\nCopyright A\n"
        val distinct = "Common license\nCopyright B\n"
        val notices = listOf(
            AppLicenseNotice("a", "Library A", "1", "Apache-2.0", shared),
            AppLicenseNotice("b", "Library B", "2", "Apache-2.0", shared),
            AppLicenseNotice("c", "Library C", "3", "Apache-2.0", distinct),
        )
        val blocks = licenseTextBlocks(notices)
        val body = blocks.joinToString("") { it.text }
        assertEquals(1, Regex(Regex.escape(shared)).findAll(body).count())
        assertEquals(1, Regex(Regex.escape(distinct)).findAll(body).count())
        notices.forEach { assertTrue(body.contains("${it.title} · ${it.version} · ${it.license}")) }
        assertEquals(blocks.size, blocks.map { it.id }.distinct().size)
    }
}
