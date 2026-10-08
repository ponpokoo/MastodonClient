package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.domain.model.RequestException
import io.github.ponpokoo.mastodonclient.domain.model.RequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RequestFailureMappingTest {
    @Test fun httpBoundariesKeepDistinctFailuresAndOriginalCause() {
        for ((code, expected) in listOf(401 to RequestFailure.Unauthorized, 403 to RequestFailure.Forbidden,
            404 to RequestFailure.NotFound, 422 to RequestFailure.Unprocessable, 429 to RequestFailure.RateLimited,
            499 to RequestFailure.Rejected, 500 to RequestFailure.Server, 503 to RequestFailure.Server)) {
            val cause = HttpException(Response.error<Unit>(code, "private response".toResponseBody()))
            val mapped = cause.toRequestException() as RequestException
            assertEquals(expected, mapped.failure); assertSame(cause, mapped.cause)
            assertEquals("HTTP $code", mapped.diagnostic)
        }
    }
    @Test fun timeoutAndConnectionAreDistinctAndDiagnosticsContainOnlyType() {
        val timeout = java.net.SocketTimeoutException("private message").toRequestException() as RequestException
        assertEquals(RequestFailure.Timeout, timeout.failure); assertEquals("SocketTimeoutException", timeout.diagnostic)
        val connection = java.io.IOException("private message").toRequestException() as RequestException
        assertEquals(RequestFailure.Connection, connection.failure); assertEquals("IOException", connection.diagnostic)
    }
    @Test fun cancellationPropagatesAndExistingDomainFailureIsNotWrappedAgain() = runTest {
        val cancel = CancellationException("cancel")
        try { requestResult<Unit> { throw cancel }; fail("Cancellation was converted to failure") }
        catch (error: CancellationException) { assertSame(cancel, error) }
        val domain = RequestException(RequestFailure.NotFound, "HTTP 404", IllegalStateException())
        assertSame(domain, requestResult<Unit> { throw domain }.exceptionOrNull())
    }
}
