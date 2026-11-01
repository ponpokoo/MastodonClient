package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus

/** A missing handler means that this surface cannot perform the operation. */
internal data class StatusActions(
    val onReply: ((TimelineStatus) -> Unit)? = null,
    val onBoost: ((TimelineStatus) -> Unit)? = null,
    val onQuote: ((TimelineStatus, QuoteMode) -> Unit)? = null,
    val onFavourite: ((TimelineStatus) -> Unit)? = null,
    val onBookmark: ((TimelineStatus) -> Unit)? = null,
    val onReact: ((TimelineStatus, String?) -> Unit)? = null,
    val onVotePoll: ((TimelineStatus, Set<Int>) -> Unit)? = null,
)
