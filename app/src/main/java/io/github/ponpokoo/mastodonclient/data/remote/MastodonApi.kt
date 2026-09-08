package io.github.ponpokoo.mastodonclient.data.remote

import io.github.ponpokoo.mastodonclient.data.remote.dto.InstanceDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.CredentialApplicationDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.TokenDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusContextDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.AnnouncementDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.NotificationDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.SearchResultDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.MarkerResponseDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.PUT
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

    @GET("api/v1/accounts/{id}")
    suspend fun getAccount(@Path("id") id: String): AccountDto

    @GET("api/v1/accounts/{id}/statuses")
    suspend fun getAccountStatuses(
        @Path("id") id: String,
        @Query("limit") limit: Int = 20,
        @Query("exclude_reblogs") excludeReblogs: Boolean = false,
    ): List<StatusDto>

    @GET("api/v1/timelines/home")
    suspend fun getHomeTimeline(
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 20,
    ): List<StatusDto>

    @GET("api/v1/timelines/public")
    suspend fun getPublicTimeline(
        @Query("local") local: Boolean = false,
        @Query("remote") remote: Boolean = false,
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 20,
    ): List<StatusDto>

    @GET("api/v1/timelines/tag/{hashtag}")
    suspend fun getHashtagTimeline(
        @Path("hashtag") hashtag: String,
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 20,
    ): List<StatusDto>

    @GET("api/v1/announcements")
    suspend fun getAnnouncements(): List<AnnouncementDto>

    @GET("api/v1/notifications")
    suspend fun getNotifications(
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 40,
    ): List<NotificationDto>

    @FormUrlEncoded
    @POST("api/v1/markers")
    suspend fun saveNotificationMarker(
        @Field("notifications[last_read_id]") lastReadId: String,
    ): MarkerResponseDto

    @GET("api/v2/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("resolve") resolve: Boolean = false,
    ): SearchResultDto

    @GET("api/v1/statuses/{id}")
    suspend fun getStatus(@Path("id") id: String): StatusDto

    @GET("api/v1/statuses/{id}/context")
    suspend fun getStatusContext(@Path("id") id: String): StatusContextDto

    @GET("api/v1/statuses/{id}/reblogged_by")
    suspend fun getRebloggedBy(@Path("id") id: String): List<AccountDto>

    @GET("api/v1/statuses/{id}/favourited_by")
    suspend fun getFavouritedBy(@Path("id") id: String): List<AccountDto>

    // Fedibird extension. Called only when a status advertises emoji_reactions.
    @GET("api/v1/statuses/{id}/emoji_reactioned_by")
    suspend fun getEmojiReactionedBy(@Path("id") id: String): List<AccountDto>

    @POST("api/v1/statuses/{id}/favourite")
    suspend fun favourite(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/unfavourite")
    suspend fun unfavourite(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/reblog")
    suspend fun reblog(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/unreblog")
    suspend fun unreblog(@Path("id") id: String): StatusDto

    @FormUrlEncoded
    @POST("api/v1/statuses")
    suspend fun createStatus(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Field("status") status: String,
        @Field("in_reply_to_id") inReplyToId: String? = null,
    ): StatusDto

    @PUT("api/v1/statuses/{id}/emoji_reactions/{emoji}")
    suspend fun addFedibirdReaction(
        @Path("id") id: String,
        @Path("emoji") emoji: String,
    ): StatusDto

    @POST("api/v1/statuses/{id}/emoji_unreaction")
    suspend fun removeFedibirdReaction(@Path("id") id: String): StatusDto
}
