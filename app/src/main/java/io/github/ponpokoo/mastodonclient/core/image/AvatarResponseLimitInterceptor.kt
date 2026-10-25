package io.github.ponpokoo.mastodonclient.core.image

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Enforce the limit for both Content-Length and streamed/chunked response bodies. */
internal class AvatarResponseLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (body.contentLength() > MAX_BYTES) {
            response.close()
            throw IOException("Avatar exceeds size limit")
        }
        val limited = object : ResponseBody() {
            private val source = object : ForwardingSource(body.source()) {
                private var received = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, minOf(byteCount, MAX_BYTES - received + 1))
                    if (read > 0) received += read
                    if (received > MAX_BYTES) throw IOException("Avatar exceeds size limit")
                    return read
                }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }
        return response.newBuilder().body(limited).build()
    }

    companion object { const val MAX_BYTES = 2 * 1024 * 1024L }
}
