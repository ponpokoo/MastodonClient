package io.github.ponpokoo.mastodonclient.notification

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/** Includes settings and detail screens, not just the timeline. */
internal object PushVisibility : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger()
    val foreground: Boolean get() = started.get() > 0
    override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) { started.decrementAndGet() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
    override fun onActivityResumed(activity: Activity) { }
    override fun onActivityPaused(activity: Activity) { }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
    override fun onActivityDestroyed(activity: Activity) { }
}
