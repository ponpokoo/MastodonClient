package io.github.ponpokoo.mastodonclient.domain.model

enum class ExploreFeed { Posts, Hashtags, News, Followed }

data class ExploreNews(val url: String, val title: String, val description: String,
    val provider: String, val imageUrl: String?)

/** Offset for trends; opaque relationship cursor from Link for followed tags. */
data class ExplorePage(
    val statuses: List<TimelineStatus> = emptyList(),
    val tags: List<SearchTag> = emptyList(),
    val news: List<ExploreNews> = emptyList(),
    val nextCursor: String? = null,
) {
    fun count() = statuses.size + tags.size + news.size
}
