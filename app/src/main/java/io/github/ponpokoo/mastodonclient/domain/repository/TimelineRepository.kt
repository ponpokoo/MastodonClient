package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage

interface TimelineRepository {
    suspend fun getHomeTimeline(
        session: AccountSession,
        maxId: String? = null,
        limit: Int = 20,
    ): Result<TimelinePage>
}
