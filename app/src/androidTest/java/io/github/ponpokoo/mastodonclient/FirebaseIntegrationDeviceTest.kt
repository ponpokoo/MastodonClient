package io.github.ponpokoo.mastodonclient

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushControlStore
import io.github.ponpokoo.mastodonclient.notification.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

class FirebaseIntegrationDeviceTest {
    @Test fun configuredFirebaseObtainsTokenAndStoresItEncrypted() = runBlocking {
        assumeTrue("Local Firebase configuration is absent", BuildConfig.FIREBASE_CONFIGURED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(FirebaseApp.getInstance().options.applicationId.isNotBlank())
        val token = withTimeout(60_000) { currentFcmToken() }
        assertTrue("Firebase returned an empty token", token.isNotBlank())
        PushRuntime.get(context).onTokenChanged(token)
        assertTrue("Token must be available for subscription reconciliation", EncryptedPushControlStore(context).read().token == token)
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, NagisaMessagingService::class.java), 0)
        assertFalse(service.exported)
    }

    @Test fun durableReceiveWorkerDropsUnknownRegistrationWithoutDisplayingNotification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun randomId() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val registration = randomId(); val message = randomId()
        val data = mapOf("version" to "1", "registrationId" to registration, "messageId" to message,
            "transport" to "inline", "encoding" to "aes128gcm", "body" to "unused-ciphertext")
        val manager = WorkManager.getInstance(context)
        FcmWorkScheduler.receive(context, data, System.currentTimeMillis(), 60, false)!!.result.get(5, TimeUnit.SECONDS)
        val info = withTimeout(30_000) {
            var latest: WorkInfo? = null
            while (latest?.state?.isFinished != true) {
                latest = manager.getWorkInfosForUniqueWork("nagisa-push-$registration-$message").get(5, TimeUnit.SECONDS).firstOrNull()
                if (latest?.state?.isFinished != true) delay(100)
            }
            latest
        }
        assertEquals(WorkInfo.State.SUCCEEDED, info?.state)
        assertNull(FcmWorkScheduler.receive(context, data, System.currentTimeMillis() - 120_000, 60, false))
    }
}
