package io.github.ponpokoo.mastodonclient.feature.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
abstract class ScreenViewModelTestBase {
    protected val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()
    protected fun <T : ViewModel> own(viewModel: T): T = viewModel.also { viewModels += it }
    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }
}

val testAccount = AccountSession("one", "https://one.example", "me", "me", "Me", "", "test-token")
val secondAccount = testAccount.copy(sessionId = "two", instanceUrl = "https://two.example")
fun testStatus(id: String = "post") = TimelineStatus(
    timelineId = id, statusId = id, createdAt = "2026-09-08T00:00:00Z",
    author = StatusAuthor("me", "Me", "me@one.example", ""), boostedBy = null,
    contentHtml = "Hello", spoilerText = "", sensitive = false, visibility = "public", url = null,
    repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
)
fun testNotification(id: String = "notification") = TimelineNotification(id, "mention", "2026-09-08T00:00:00Z", testStatus().author, testStatus())
fun testProfile() = UserProfile(testStatus().author, "", "", 1, 1, 1, listOf(testStatus()), nextMaxId = "next", isOwnProfile = true)
fun testResults(id: String) = SearchResults(emptyList(), listOf(testStatus(id)), emptyList())

open class ScreenRepositoryFake : TimelineRepository {
    val markers = mutableListOf<Pair<String, String>>()
    override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
        Result.success(TimelinePage(listOf(testStatus().copy(timelineId = "boost-wrapper")), "next", false))
    override suspend fun search(session: AccountSession, query: String) = Result.success(testResults(query))
    override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
        Result.success(NotificationPage(listOf(testNotification()), "next", false))
    override suspend fun saveNotificationMarker(session: AccountSession, lastReadId: String): Result<Unit> {
        markers += session.sessionId to lastReadId
        return Result.success(Unit)
    }
    override suspend fun getProfile(session: AccountSession, accountId: String) = Result.success(testProfile())
    override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
        Result.success(TimelinePage(listOf(testStatus(tab.name)), null, true))
    override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
        Result.success(testStatus(statusId).copy(favourited = favourite, favouritesCount = if (favourite) 1 else 0))
    override suspend fun deleteStatus(session: AccountSession, statusId: String) = Result.success(Unit)
}
