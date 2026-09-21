package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Grant before Activity launch so the system permission dialog does not cover Compose. */
class NotificationPermissionRule : ExternalResource() {
    override fun before() {
        if (Build.VERSION.SDK_INT >= 33) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
