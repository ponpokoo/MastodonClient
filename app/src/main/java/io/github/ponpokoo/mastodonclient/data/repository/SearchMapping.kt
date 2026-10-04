package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.domain.model.SearchFailure
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import java.io.IOException
import java.io.InterruptedIOException
import retrofit2.HttpException

internal fun SearchTarget.apiType(): String = when (this) {
    SearchTarget.Posts -> "statuses"
    SearchTarget.Accounts -> "accounts"
    SearchTarget.Hashtags -> "hashtags"
}

internal fun Throwable.toSearchFailure(): SearchFailure = when (this) {
    is InterruptedIOException -> SearchFailure.Timeout
    is IOException -> SearchFailure.Connection
    is HttpException -> when (code()) {
        401, 403 -> SearchFailure.Authentication
        429 -> SearchFailure.RateLimited
        in 500..599 -> SearchFailure.Server
        else -> SearchFailure.Rejected
    }
    else -> SearchFailure.Unknown
}
