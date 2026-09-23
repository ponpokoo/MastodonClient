package io.github.ponpokoo.mastodonclient.data.remote

import io.github.ponpokoo.mastodonclient.data.remote.dto.InstanceDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.CredentialApplicationDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.TokenDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.PollDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusContextDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.AnnouncementDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.NotificationDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.SearchResultDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.MarkerResponseDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.CustomEmojiDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.MediaAttachmentDto
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Field
import retrofit2.http.DELETE
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.PATCH
import retrofit2.http.PartMap
import io.github.ponpokoo.mastodonclient.data.remote.dto.RelationshipDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.ReportDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.ListDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusSourceDto

interface MastodonApi {
    @GET("api/v1/push/subscription")
    suspend fun getPushSubscription(): io.github.ponpokoo.mastodonclient.data.remote.dto.PushSubscriptionDto

    @FormUrlEncoded
    @POST("api/v1/push/subscription")
    suspend fun registerPushSubscription(
        @retrofit2.http.FieldMap fields: Map<String, String>,
    ): io.github.ponpokoo.mastodonclient.data.remote.dto.PushSubscriptionDto

    @DELETE("api/v1/push/subscription")
    suspend fun removePushSubscription(): retrofit2.Response<Unit>

    @GET("api/v2/instance")
    suspend fun getInstance(): InstanceDto

    @GET("api/v1/instance")
    suspend fun getLegacyInstance(): InstanceDto

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

    @GET("api/v1/custom_emojis")
    suspend fun getCustomEmojis(): List<CustomEmojiDto>

    @Multipart
    @POST("api/v2/media")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("description") description: RequestBody? = null,
    ): MediaAttachmentDto

    @GET("api/v1/media/{id}")
    suspend fun getMedia(@Path("id") id: String): MediaAttachmentDto

    @GET("api/v1/accounts/{id}")
    suspend fun getAccount(@Path("id") id: String): AccountDto

    @GET("api/v1/accounts/{id}/statuses")
    suspend fun getAccountStatuses(
        @Path("id") id: String,
        @Query("limit") limit: Int = 20,
        @Query("exclude_reblogs") excludeReblogs: Boolean = false,
        @Query("exclude_replies") excludeReplies: Boolean = false,
        @Query("only_media") onlyMedia: Boolean = false,
        @Query("pinned") pinned: Boolean = false,
        @Query("max_id") maxId: String? = null,
    ): List<StatusDto>

    @GET("api/v1/accounts/{id}/followers")
    suspend fun getFollowers(@Path("id") id: String, @Query("max_id") maxId: String? = null, @Query("limit") limit: Int = 40): retrofit2.Response<List<AccountDto>>

    @GET("api/v1/accounts/{id}/following")
    suspend fun getFollowing(@Path("id") id: String, @Query("max_id") maxId: String? = null, @Query("limit") limit: Int = 40): retrofit2.Response<List<AccountDto>>

    @GET("api/v1/accounts/relationships")
    suspend fun getRelationships(@Query("id[]") ids: List<String>): List<RelationshipDto>

    @POST("api/v1/accounts/{id}/follow") suspend fun follow(@Path("id") id: String): RelationshipDto
    @POST("api/v1/accounts/{id}/unfollow") suspend fun unfollow(@Path("id") id: String): RelationshipDto
    @FormUrlEncoded @POST("api/v1/accounts/{id}/mute") suspend fun mute(@Path("id") id: String, @Field("notifications") notifications: Boolean = true): RelationshipDto
    @POST("api/v1/accounts/{id}/unmute") suspend fun unmute(@Path("id") id: String): RelationshipDto
    @POST("api/v1/accounts/{id}/block") suspend fun block(@Path("id") id: String): RelationshipDto
    @POST("api/v1/accounts/{id}/unblock") suspend fun unblock(@Path("id") id: String): RelationshipDto

    @FormUrlEncoded
    @POST("api/v1/reports")
    suspend fun report(
        @Field("account_id") accountId: String,
        @Field("comment") comment: String,
        @Field("forward") forward: Boolean = false,
        @Field("category") category: String = "other",
        @Field("status_ids[]") statusIds: List<String>? = null,
    ): ReportDto

    @Multipart
    @PATCH("api/v1/accounts/update_credentials")
    suspend fun updateCredentials(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part avatar: MultipartBody.Part? = null,
        @Part header: MultipartBody.Part? = null,
    ): AccountDto

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

    @GET("api/v1/lists")
    suspend fun getLists(): List<ListDto>

    @GET("api/v1/timelines/list/{id}")
    suspend fun getListTimeline(
        @Path("id") id: String,
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 20,
    ): List<StatusDto>

    @GET("api/v1/bookmarks")
    suspend fun getBookmarks(@Query("max_id") maxId: String? = null, @Query("limit") limit: Int = 20): List<StatusDto>

    @GET("api/v1/favourites")
    suspend fun getFavourites(@Query("max_id") maxId: String? = null, @Query("limit") limit: Int = 20): List<StatusDto>

    @FormUrlEncoded
    @POST("api/v1/lists/{id}/accounts")
    suspend fun addAccountsToList(@Path("id") id: String, @Field("account_ids[]") accountIds: List<String>)

    @GET("api/v1/announcements")
    suspend fun getAnnouncements(): List<AnnouncementDto>

    @GET("api/v1/notifications")
    suspend fun getNotifications(
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int = 80,
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

    @GET("api/v1/statuses/{id}/source")
    suspend fun getStatusSource(@Path("id") id: String): StatusSourceDto

    @GET("api/v1/statuses/{id}/context")
    suspend fun getStatusContext(@Path("id") id: String): StatusContextDto

    @GET("api/v1/statuses/{id}/reblogged_by")
    suspend fun getRebloggedBy(@Path("id") id: String): List<AccountDto>

    @GET("api/v1/statuses/{id}/favourited_by")
    suspend fun getFavouritedBy(@Path("id") id: String): List<AccountDto>

    // Fedibird extension. Called only when a status advertises emoji_reactions.
    @GET("api/v1/statuses/{id}/emoji_reactioned_by")
    suspend fun getEmojiReactionedBy(@Path("id") id: String): JsonElement

    @POST("api/v1/statuses/{id}/favourite")
    suspend fun favourite(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/unfavourite")
    suspend fun unfavourite(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/reblog")
    suspend fun reblog(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/unreblog")
    suspend fun unreblog(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/bookmark")
    suspend fun bookmark(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/unbookmark")
    suspend fun unbookmark(@Path("id") id: String): StatusDto

    @FormUrlEncoded
    @POST("api/v1/polls/{id}/votes")
    suspend fun votePoll(
        @Path("id") id: String,
        @Field("choices[]") choices: List<Int>,
    ): PollDto

    @POST("api/v1/statuses/{id}/pin")
    suspend fun pin(@Path("id") id: String): StatusDto

    @POST("api/v1/statuses/{id}/unpin")
    suspend fun unpin(@Path("id") id: String): StatusDto

    @DELETE("api/v1/statuses/{id}")
    suspend fun deleteStatus(@Path("id") id: String): StatusDto

    @FormUrlEncoded
    @POST("api/v1/statuses")
    suspend fun createStatus(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Field("status") status: String,
        @Field("in_reply_to_id") inReplyToId: String? = null,
        @Field("quoted_status_id") quotedStatusId: String? = null,
        @Field("media_ids[]") mediaIds: List<String>? = null,
        @Field("spoiler_text") spoilerText: String? = null,
        @Field("sensitive") sensitive: Boolean = false,
        @Field("visibility") visibility: String = "public",
        @Field("language") language: String? = null,
        @Field("poll[options][]") pollOptions: List<String>? = null,
        @Field("poll[expires_in]") pollExpiresInSeconds: Long? = null,
        @Field("poll[multiple]") pollMultiple: Boolean? = null,
    ): StatusDto

    @FormUrlEncoded
    @PUT("api/v1/statuses/{id}")
    suspend fun updateStatus(
        @Path("id") id: String,
        @Field("status") status: String,
        @Field("spoiler_text") spoilerText: String? = null,
        @Field("sensitive") sensitive: Boolean = false,
        @Field("language") language: String? = null,
    ): StatusDto

    @PUT("api/v1/statuses/{id}/emoji_reactions/{emoji}")
    suspend fun addFedibirdReaction(
        @Path("id") id: String,
        @Path("emoji") emoji: String,
    ): StatusDto

    @POST("api/v1/statuses/{id}/emoji_unreaction")
    suspend fun removeFedibirdReaction(@Path("id") id: String): StatusDto
}
