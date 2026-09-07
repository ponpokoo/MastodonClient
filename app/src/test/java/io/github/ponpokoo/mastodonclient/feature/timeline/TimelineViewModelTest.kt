package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restoresSessionThenLoadsAndAppendsTimelinePages() = runTest(dispatcher) {
        val repository = FakeTimelineRepository()
        val viewModel = TimelineViewModel(repository, FakeAuthRepository(SESSION))

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertEquals(listOf("first"), viewModel.uiState.value.statuses.map { it.timelineId })

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null, "first"), repository.requestedMaxIds)
        assertEquals(
            listOf("first", "second"),
            viewModel.uiState.value.statuses.map { it.timelineId },
        )
    }

    private class FakeTimelineRepository : TimelineRepository {
        val requestedMaxIds = mutableListOf<String?>()

        override suspend fun getHomeTimeline(
            session: AccountSession,
            maxId: String?,
            limit: Int,
        ): Result<TimelinePage> {
            requestedMaxIds += maxId
            return if (maxId == null) {
                Result.success(TimelinePage(listOf(status("first")), "first", endReached = false))
            } else {
                Result.success(TimelinePage(listOf(status("second")), "second", endReached = true))
            }
        }
    }

    private class FakeAuthRepository(
        private val session: AccountSession?,
    ) : AuthRepository {
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(
            UnsupportedOperationException(),
        )

        override suspend fun completeAuthorization(callbackUrl: String) =
            Result.failure<AccountSession>(UnsupportedOperationException())

        override suspend fun restoreSession(): AccountSession? = session
        override suspend fun logout() = Unit
    }

    private companion object {
        val SESSION = AccountSession(
            sessionId = "session",
            instanceUrl = "https://example.social",
            accountId = "me",
            username = "me",
            displayName = "Me",
            avatarUrl = "",
            accessToken = "token",
        )

        fun status(id: String) = TimelineStatus(
            timelineId = id,
            statusId = id,
            createdAt = "2026-09-08T00:00:00Z",
            author = StatusAuthor("author", "Author", "author@example.social", ""),
            boostedBy = null,
            contentHtml = "<p>Hello</p>",
            spoilerText = "",
            sensitive = false,
            visibility = "public",
            url = null,
            repliesCount = 0,
            boostsCount = 0,
            favouritesCount = 0,
            mediaAttachments = emptyList(),
        )
    }
}
