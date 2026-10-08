package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.model.RequestException
import io.github.ponpokoo.mastodonclient.domain.model.RequestFailure
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

internal fun Throwable.toRequestException(): Throwable = when (this) {
    is CancellationException -> throw this
    is RequestException -> this
    is HttpException -> RequestException(when (code()) {
        401 -> RequestFailure.Unauthorized
        403 -> RequestFailure.Forbidden
        404 -> RequestFailure.NotFound
        422 -> RequestFailure.Unprocessable
        429 -> RequestFailure.RateLimited
        in 500..Int.MAX_VALUE -> RequestFailure.Server
        else -> RequestFailure.Rejected
    }, "HTTP ${code()}", this)
    is InterruptedIOException -> RequestException(RequestFailure.Timeout, javaClass.simpleName, this)
    is IOException -> RequestException(RequestFailure.Connection, javaClass.simpleName, this)
    else -> this
}

internal suspend fun <T> requestResult(block: suspend () -> T): Result<T> =
    runCatchingCancellable { block() }.fold(
        onSuccess = { Result.success(it) }, onFailure = { Result.failure(it.toRequestException()) },
    )
