package io.github.ponpokoo.mastodonclient

import android.app.Application
import io.github.ponpokoo.mastodonclient.notification.FcmWorkScheduler
import io.github.ponpokoo.mastodonclient.notification.PushVisibility

class NagisaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(PushVisibility)
        if (BuildConfig.FIREBASE_CONFIGURED) FcmWorkScheduler.syncToken(this)
    }
}
