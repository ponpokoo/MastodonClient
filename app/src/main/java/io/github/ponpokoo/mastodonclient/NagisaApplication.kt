package io.github.ponpokoo.mastodonclient

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import io.github.ponpokoo.mastodonclient.core.image.AvatarDownloadFetcherFactory
import io.github.ponpokoo.mastodonclient.core.image.AvatarImages
import io.github.ponpokoo.mastodonclient.notification.FcmWorkScheduler
import io.github.ponpokoo.mastodonclient.notification.PushVisibility

class NagisaApplication : Application() {
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
