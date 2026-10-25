package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import io.github.ponpokoo.mastodonclient.core.image.AvatarImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppMaintenanceDataSource(context: Context) {
    private val appContext = context.applicationContext
    @Suppress("DEPRECATION")
    private val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    val versionName: String = packageInfo.versionName ?: "不明"
    val versionCode: Long = PackageInfoCompat.getLongVersionCode(packageInfo)

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        // Only Coil-owned caches: preserve drafts, credentials and editor temporary files.
        AvatarImages.get(appContext).clear()
        Unit
    }
}
