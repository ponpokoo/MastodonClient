package io.github.ponpokoo.mastodonclient.core.common

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Test

class CancellableResultTest {
    @Test(expected = CancellationException::class)
    fun cancellationIsPropagated() {
        runCatchingCancellable { throw CancellationException("cancelled") }
    }

    @Test
    fun ordinaryFailureIsReturned() {
        val error = IllegalStateException("failure")
        assertSame(error, runCatchingCancellable { throw error }.exceptionOrNull())
    }
}
