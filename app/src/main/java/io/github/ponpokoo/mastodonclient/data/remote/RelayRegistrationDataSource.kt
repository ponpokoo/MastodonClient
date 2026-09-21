package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path

/** Relay-only client. Never use the Mastodon authenticated client here. */
class RelayRegistrationDataSource internal constructor(private val api: RelayApi) {
    constructor(baseUrl: String) : this(createApi(baseUrl))

    private companion object {
      private val json = Json { ignoreUnknownKeys = true }
      fun createApi(baseUrl: String): RelayApi {
        val url = baseUrl.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "Relay requires an HTTPS base URL" }
        return Retrofit.Builder()
            .baseUrl(url)
            .client(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(RelayApi::class.java)
      }
    }

    suspend fun put(registrationId: String, managementToken: String, fcmToken: String): RelayRegistrationDto {
        validate(registrationId, managementToken)
        require(fcmToken.isNotBlank()) { "FCM token is required" }
        val result = api.put(registrationId, "Bearer $managementToken", RelayRegistrationRequest(fcmToken))
        val endpoint = result.endpoint.toHttpUrl()
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null) {
            "Invalid Relay delivery endpoint"
        }
        return result
    }

    suspend fun remove(registrationId: String, managementToken: String) {
        validate(registrationId, managementToken)
        val response = api.remove(registrationId, "Bearer $managementToken")
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }

    private fun validate(id: String, token: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{22,128}"))) { "Invalid registration ID" }
        require(token.matches(Regex("[A-Za-z0-9_-]{43,128}"))) { "Invalid management token" }
    }
}

internal interface RelayApi {
    @PUT("v1/registrations/{id}")
    suspend fun put(
        @Path("id") id: String,
        @Header("Authorization") authorization: String,
        @Body request: RelayRegistrationRequest,
    ): RelayRegistrationDto

    @DELETE("v1/registrations/{id}")
    suspend fun remove(@Path("id") id: String, @Header("Authorization") authorization: String): Response<Unit>
}

@Serializable
internal class RelayRegistrationRequest(val fcmToken: String)

@Serializable
class RelayRegistrationDto(val endpoint: String)
