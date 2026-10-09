package io.github.ponpokoo.mastodonclient

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.github.ponpokoo.mastodonclient.data.remote.MastodonApi
import io.github.ponpokoo.mastodonclient.data.remote.dto.InstanceDto
import io.github.ponpokoo.mastodonclient.navigation.Route
import io.github.ponpokoo.mastodonclient.navigation.prepareNotificationTimeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Runtime contracts relevant to shrinking, reflection, and typed notification navigation. */
class R8RuntimeDeviceTest {
    @Test fun apiDefinitionsAndReflectiveJsonSerializersRemainUsable() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        Retrofit.Builder()
            .baseUrl("https://example.test/")
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .validateEagerly(true)
            .build()
            .create(MastodonApi::class.java)

        val instance = json.decodeFromString(
            serializer(InstanceDto::class.java),
            """{"domain":"example.test","configuration":{"statuses":{"max_characters":1000}},"future_field":true}""",
        ) as InstanceDto
        assertEquals("example.test", instance.domain)
        assertEquals(1000, instance.configuration?.statuses?.maxCharacters)
        assertNull(instance.version)
    }

    @Test fun persistedWorkerClassNamesAndConstructorsRemainAvailable() {
        // WorkManager persists these names and creates workers using this constructor.
        listOf("FcmTokenWorker", "FcmReceiveWorker", "PushSyncWorker", "PushRecoveryWorker")
            .forEach { name ->
                val worker = Class.forName("io.github.ponpokoo.mastodonclient.notification.$name")
                assertTrue(ListenableWorker::class.java.isAssignableFrom(worker))
                worker.getConstructor(Context::class.java, WorkerParameters::class.java)
            }
    }

    @Test fun notificationOnTimelinePreservesItsExistingBackStackEntry() = withNavigation { controller ->
        val entryId = controller.currentBackStackEntry!!.id
        controller.prepareNotificationTimeline()
        assertEquals(entryId, controller.currentBackStackEntry!!.id)
        assertTrue(controller.currentBackStackEntry!!.destination.hasRoute<Route.Timeline>())
    }

    @Test fun notificationFromAnotherScreenReturnsToASingleTimelineEntry() = withNavigation { controller ->
        controller.navigate(Route.Settings)
        val entryId = controller.currentBackStackEntry!!.id
        controller.prepareNotificationTimeline()
        assertNotEquals(entryId, controller.currentBackStackEntry!!.id)
        assertTrue(controller.currentBackStackEntry!!.destination.hasRoute<Route.Timeline>())
        assertNull(controller.previousBackStackEntry)
    }

    private fun withNavigation(block: (NavHostController) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val store = ViewModelStore()
            try {
                val controller = NavHostController(instrumentation.targetContext)
                controller.setViewModelStore(store)
                controller.navigatorProvider.addNavigator(ComposeNavigator())
                controller.graph = controller.createGraph(startDestination = Route.Timeline) {
                    composable<Route.Timeline> {}
                    composable<Route.Settings> {}
                }
                block(controller)
            } finally {
                store.clear()
            }
        }
    }
}
