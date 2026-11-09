package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.feature.timeline.MainDestination.Explore
import io.github.ponpokoo.mastodonclient.feature.timeline.MainDestination.Home
import io.github.ponpokoo.mastodonclient.feature.timeline.MainDestination.Notifications
import io.github.ponpokoo.mastodonclient.feature.timeline.MainDestination.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainTabHistoryTest {
    @Test fun backFollowsVisitOrderIncludingAnEarlierTabThatWasSelectedAgain() {
        val history = MainTabHistory("first").select(Explore).select(Home).select(Notifications)
        val afterNotification = history.goBack()
        val afterHome = afterNotification.goBack()
        val root = afterHome.goBack()

        assertEquals(Home, afterNotification.selected)
        assertEquals(Explore, afterHome.selected)
        assertEquals(Home, root.selected)
        assertFalse(root.canGoBack)
    }

    @Test fun reselectingATabDoesNotAddABackStep() {
        val root = MainTabHistory("first")
        val profile = root.select(Profile)
        assertEquals(root, profile.select(Profile).goBack())
        assertEquals(root, root.goBack())
    }

    @Test fun accountChangeAndNotificationEntryStartIndependentHistories() {
        val firstAccount = MainTabHistory("first").select(Explore)
        assertEquals(firstAccount, firstAccount.forSession("first"))

        val secondAccount = firstAccount.forSession("second")
        assertEquals(Home, secondAccount.selected)
        assertFalse(secondAccount.canGoBack)
        assertFalse(secondAccount.forSession("first").canGoBack)

        val notification = MainTabHistory.fromNotification("second")
        assertEquals(Notifications, notification.selected)
        assertFalse(notification.canGoBack)
        assertEquals(notification, notification.select(Profile).goBack())
    }
}
