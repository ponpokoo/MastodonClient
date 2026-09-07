package io.github.ponpokoo.mastodonclient.domain.model

data class TimelineStatus(
    val timelineId: String,
    val statusId: String,
    val createdAt: String,
    val author: StatusAuthor,
    val boostedBy: StatusAuthor?,
    val contentHtml: String,
    val spoilerText: String,
    val sensitive: Boolean,
    val visibility: String,
    val url: String?,
    val repliesCount: Long,
    val boostsCount: Long,
    val favouritesCount: Long,
    val mediaAttachments: List<MediaAttachment>,
)

data class StatusAuthor(
    val id: String,
    val displayName: String,
    val accountName: String,
    val avatarUrl: String,
)

data class MediaAttachment(
    val id: String,
    val type: String,
    val url: String?,
    val previewUrl: String?,
    val description: String?,
)

data class TimelinePage(
    val statuses: List<TimelineStatus>,
    val nextMaxId: String?,
    val endReached: Boolean,
)
