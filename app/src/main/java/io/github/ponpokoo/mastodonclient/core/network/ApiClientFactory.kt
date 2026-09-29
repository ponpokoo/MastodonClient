package io.github.ponpokoo.mastodonclient.core.network

import io.github.ponpokoo.mastodonclient.data.remote.MastodonApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

class ApiClientFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    fun createForMedia(baseUrl: String, accessToken: String): MastodonApi = ApiClientFactory(
        client.newBuilder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build(), json,
    ).create(baseUrl, accessToken)

    // Bounded, in-memory only; credentials are part of the key to isolate sessions.
    private data class ClientKey(val baseUrl: String, val accessToken: String?)
    private val services = object : LinkedHashMap<ClientKey, MastodonApi>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClientKey, MastodonApi>?): Boolean = size > 8
    }

    @Synchronized
    fun create(baseUrl: String, accessToken: String? = null): MastodonApi {
        val key = ClientKey("${baseUrl.removeSuffix("/")}/", accessToken)
        services[key]?.let { return it }
        val authenticatedClient = if (accessToken == null) {
            client
        } else {
            client.newBuilder()
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                    chain.proceed(request)
                })
                .build()
        }

        return Retrofit.Builder()
        .baseUrl("${baseUrl.removeSuffix("/")}/")
        .client(authenticatedClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MastodonApi::class.java)
        .also { services[key] = it }
    }
}
