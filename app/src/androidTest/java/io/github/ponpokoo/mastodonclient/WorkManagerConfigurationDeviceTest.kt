package io.github.ponpokoo.mastodonclient

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.ponpokoo.mastodonclient.notification.NotificationPollingScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkManagerConfigurationDeviceTest {
    @Test fun initializedWorkManagerUsesApplicationRangeWithoutPollingJobCollisions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as NagisaApplication
        // Read the running instance, so leaving the default initializer enabled fails this test.
        val actual = WorkManager.getInstance(context).configuration
        val expected = application.workManagerConfiguration
        assertEquals(expected.minJobSchedulerId, actual.minJobSchedulerId)
        assertEquals(expected.maxJobSchedulerId, actual.maxJobSchedulerId)
        val workIds = actual.minJobSchedulerId..actual.maxJobSchedulerId
        assertFalse(NotificationPollingScheduler.PERIODIC_JOB_ID in workIds)
        assertFalse(NotificationPollingScheduler.IMMEDIATE_JOB_ID in workIds)
    }
}
