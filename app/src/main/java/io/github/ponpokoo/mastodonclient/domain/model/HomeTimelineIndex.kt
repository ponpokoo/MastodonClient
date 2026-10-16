package io.github.ponpokoo.mastodonclient.domain.model

import kotlinx.serialization.Serializable

/** IDs include hidden/deleted rows: display filtering must not destroy pagination evidence. */
@Serializable
data class HomeTimelineIndex(
    val ids: List<String>,
    val gaps: List<HomeTimelineGap> = emptyList(),
    val partial: Boolean = false,
    val streamBoundary: Boolean = true,
)

@Serializable
data class HomeTimelineGap(
    val beforeId: String,
    val maxId: String,
    val unavailable: Boolean = false,
)

/** Small persisted index alongside the existing status rows. */
@Serializable
data class HomeTimelineWindowRecord(
    val index: HomeTimelineIndex,
    val nextMaxId: String?,
    val endReached: Boolean,
    val anchorId: String? = null,
)
