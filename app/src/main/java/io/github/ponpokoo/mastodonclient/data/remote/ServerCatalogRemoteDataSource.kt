package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

fun interface ServerCatalogSource {
    suspend fun loadDomains(): List<String>
}

@Serializable
data class ServerCatalogEntryDto(val domain: String? = null)

interface ServerCatalogApi {
    @GET("servers")
    suspend fun servers(@Query("registrations") registrations: String = "all"): List<ServerCatalogEntryDto>
}

class ServerCatalogRemoteDataSource(private val api: ServerCatalogApi = createApi()) : ServerCatalogSource {
    override suspend fun loadDomains(): List<String> = api.servers().mapNotNull { it.domain }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        // A directory service, independent of the user's Mastodon instance and credentials.
        internal fun createApi(baseUrl: String = "https://api.joinmastodon.org/"): ServerCatalogApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder()
                .followRedirects(false)
                .callTimeout(15, TimeUnit.SECONDS)
                .build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ServerCatalogApi::class.java)
    }
}
