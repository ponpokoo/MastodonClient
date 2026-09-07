package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository

class DefaultTimelineRepository(
    private val apiClientFactory: ApiClientFactory,
) : TimelineRepository {
    override suspend fun getHomeTimeline(
        session: AccountSession,
        maxId: String?,
        limit: Int,
    ): Result<TimelinePage> = runCatching {
        require(limit in 1..40) { "タイムラインの取得件数が範囲外です" }
        val response = apiClientFactory
            .create(session.instanceUrl, session.accessToken)
            .getHomeTimeline(maxId = maxId, limit = limit)
        TimelinePage(
            statuses = response.map(StatusDto::toDomain),
            nextMaxId = response.lastOrNull()?.id,
            endReached = response.size < limit,
        )
    }
}

private fun StatusDto.toDomain(): TimelineStatus {
    val displayed = reblog ?: this
    return TimelineStatus(
        timelineId = id,
        statusId = displayed.id,
        createdAt = displayed.createdAt,
        author = displayed.account.toDomain(),
        boostedBy = account.takeIf { reblog != null }?.toDomain(),
        contentHtml = displayed.content,
        spoilerText = displayed.spoilerText,
        sensitive = displayed.sensitive,
        visibility = displayed.visibility,
        url = displayed.url,
        repliesCount = displayed.repliesCount,
        boostsCount = displayed.reblogsCount,
        favouritesCount = displayed.favouritesCount,
        mediaAttachments = displayed.mediaAttachments.map {
            MediaAttachment(
                id = it.id,
                type = it.type,
                url = it.url,
                previewUrl = it.previewUrl,
                description = it.description,
            )
        },
    )
}

private fun AccountDto.toDomain() = StatusAuthor(
    id = id,
    displayName = displayName.ifBlank { username },
    accountName = acct,
    avatarUrl = avatar,
)
