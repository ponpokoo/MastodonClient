package io.github.ponpokoo.mastodonclient

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import io.github.ponpokoo.mastodonclient.core.image.AvatarDownloadFetcherFactory
import io.github.ponpokoo.mastodonclient.core.image.AvatarImages
import io.github.ponpokoo.mastodonclient.notification.FcmWorkScheduler
import io.github.ponpokoo.mastodonclient.notification.NotificationPollingScheduler
import io.github.ponpokoo.mastodonclient.notification.PushVisibility

class NagisaApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Polling jobs use fixed IDs above this range; WorkManager must never replace them.
            .setJobSchedulerJobIdRange(0, NotificationPollingScheduler.PERIODIC_JOB_ID - 1)
            .build()

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components {
                add(AvatarImages.get(context).interceptor { SingletonImageLoader.get(context) })
                add(AvatarDownloadFetcherFactory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                else add(GifDecoder.Factory())
            }.build()
        }
        registerActivityLifecycleCallbacks(PushVisibility)
        if (BuildConfig.FIREBASE_CONFIGURED) FcmWorkScheduler.syncToken(this)
    }
}
