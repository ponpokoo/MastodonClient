package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction
import io.github.ponpokoo.mastodonclient.feature.settings.ReorderActionList
import io.github.ponpokoo.mastodonclient.feature.settings.settingsIcon
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsReorderDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draggingHandleSavesNewOrder() {
        val order = mutableStateOf(listOf(StatusAction.Reply, StatusAction.Boost, StatusAction.Favourite))
        compose.setContent {
            MaterialTheme {
                ReorderActionList(order.value, { it.name }, { it.settingsIcon() }, { order.value = it })
            }
        }
        val first = compose.onNodeWithContentDescription("Replyの並び替え")
        val last = compose.onNodeWithContentDescription("Favouriteの並び替え")
        val distance = last.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput { swipe(center, center + Offset(0f, distance), 500) }
        compose.runOnIdle {
            assertEquals(listOf(StatusAction.Boost, StatusAction.Favourite, StatusAction.Reply), order.value)
        }
    }
}
