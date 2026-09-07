package io.github.ponpokoo.mastodonclient.data.remote

import io.github.ponpokoo.mastodonclient.data.remote.dto.InstanceDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.CredentialApplicationDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.TokenDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MastodonApi {
    @GET("api/v2/instance")
    suspend fun getInstance(): InstanceDto

    @FormUrlEncoded
    @POST("api/v1/apps")
    suspend fun createApplication(
        @Field("client_name") clientName: String,
        @Field("redirect_uris") redirectUris: String,
        @Field("scopes") scopes: String,
        @Field("website") website: String? = null,
    ): CredentialApplicationDto

    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun exchangeToken(
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("code_verifier") codeVerifier: String,
    ): TokenDto

    @GET("api/v1/accounts/verify_credentials")
    suspend fun verifyCredentials(): AccountDto

    @GET("api/v1/timelines/home")
    suspend fun getHomeTimeline(
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 20,
    ): List<StatusDto>
}
