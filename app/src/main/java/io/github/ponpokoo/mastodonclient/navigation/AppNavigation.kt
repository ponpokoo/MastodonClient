package io.github.ponpokoo.mastodonclient.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.repository.DefaultInstanceRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.feature.login.InstanceLoginScreen
import io.github.ponpokoo.mastodonclient.feature.login.LoginViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext
    val apiClientFactory = remember { ApiClientFactory() }
    val authStore = remember { SecureAuthStore(context) }
    val authRepository = remember { DefaultAuthRepository(apiClientFactory, authStore) }
    val timelineRepository = remember { DefaultTimelineRepository(apiClientFactory) }
    NavHost(navController = navController, startDestination = Route.Login) {
        composable<Route.Login> {
            val loginViewModel: LoginViewModel = viewModel(
                factory = LoginViewModel.Factory(
                    DefaultInstanceRepository(apiClientFactory),
                    authRepository,
                ),
            )
            InstanceLoginScreen(loginViewModel) {
                navController.navigate(Route.Timeline) {
                    popUpTo(Route.Login) { inclusive = true }
                }
            }
        }
        composable<Route.Timeline> {
            val timelineViewModel: TimelineViewModel = viewModel(
                factory = TimelineViewModel.Factory(timelineRepository, authRepository),
            )
            HomeTimelineScreen(timelineViewModel) {
                navController.navigate(Route.Login) {
                    popUpTo(Route.Timeline) { inclusive = true }
                }
            }
        }
    }
}
