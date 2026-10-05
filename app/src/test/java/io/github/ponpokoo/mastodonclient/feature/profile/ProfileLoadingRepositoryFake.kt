package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.ScreenRepositoryFake
import io.github.ponpokoo.mastodonclient.feature.common.testProfile
import kotlinx.coroutines.CompletableDeferred

internal open class ProfileLoadingRepositoryFake : ScreenRepositoryFake() {
    var posts = CompletableDeferred<Result<TimelinePage>>()
    var pinned = CompletableDeferred<Result<List<TimelineStatus>>>()
    var postsRequested = false
    var pinsRequested = false

    override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
        Result.success(testProfile().copy(statuses = emptyList(), pinnedStatuses = emptyList()))

    override suspend fun getProfileStatuses(session: AccountSession, accountId: String,
        tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> {
        postsRequested = true
        return posts.await()
    }

    override suspend fun getPinnedProfileStatuses(session: AccountSession, accountId: String): Result<List<TimelineStatus>> {
        pinsRequested = true
        return pinned.await()
    }
}
