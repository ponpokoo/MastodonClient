package io.github.ponpokoo.mastodonclient.domain.model

enum class RequestFailure { Unauthorized, Forbidden, NotFound, Unprocessable, RateLimited, Server, Timeout, Connection, Rejected }

/** The diagnostic contains only an HTTP status or exception type, never a response body. */
class RequestException(val failure: RequestFailure, val diagnostic: String, cause: Throwable) :
    Exception(cause.message, cause)

val Throwable.requestFailure: RequestFailure? get() = (this as? RequestException)?.failure
val Throwable.requiresAuthentication: Boolean get() = requestFailure == RequestFailure.Unauthorized || requestFailure == RequestFailure.Forbidden
