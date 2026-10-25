package io.github.ponpokoo.mastodonclient.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.ponpokoo.mastodonclient.feature.settings.PushSettingsViewModel

/** Reauthorization updates the existing session; only account removal resets navigation here. */
@Composable
internal fun AccountRemovalNavigation(model: PushSettingsViewModel, navController: NavHostController, ready: Boolean) {
    val accountRemoved by model.accountRemoved.collectAsStateWithLifecycle()
    LaunchedEffect(accountRemoved, ready) {
        if (accountRemoved > 0 && ready) {
            val destination = if (model.requiresLogin) Route.Login else Route.Timeline
            navController.navigate(destination) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            model.accountRemovalHandled()
        }
    }
}
