package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimedSnackbarTest {
    @Test fun dismissesAtTwoSecondsAndActionEndsItEarly() = runTest {
        val host = SnackbarHostState()
        var result: SnackbarResult? = null
        backgroundScope.launch { result = host.showTwoSecondSnackbar("変更しました", "取り消し") }
        runCurrent(); assertEquals("取り消し", host.currentSnackbarData?.visuals?.actionLabel)
        advanceTimeBy(1_999); runCurrent(); assertNotNull(host.currentSnackbarData)
        advanceTimeBy(1); runCurrent(); assertNull(host.currentSnackbarData); assertEquals(SnackbarResult.Dismissed, result)
        backgroundScope.launch { result = host.showTwoSecondSnackbar("再変更しました", "取り消し") }
        runCurrent(); host.currentSnackbarData!!.performAction(); runCurrent()
        assertEquals(SnackbarResult.ActionPerformed, result); assertNull(host.currentSnackbarData)
    }
}
