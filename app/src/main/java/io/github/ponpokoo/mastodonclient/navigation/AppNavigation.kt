package io.github.ponpokoo.mastodonclient.navigation

import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.repository.DefaultInstanceRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.feature.login.InstanceLoginScreen
import io.github.ponpokoo.mastodonclient.feature.login.LoginViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import io.github.ponpokoo.mastodonclient.feature.web.InAppWebScreen
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import kotlinx.coroutines.launch

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val apiClientFactory = remember { ApiClientFactory() }
    val authStore = remember { SecureAuthStore(context) }
    val authRepository = remember { DefaultAuthRepository(apiClientFactory, authStore) }
    val timelineRepository = remember { DefaultTimelineRepository(apiClientFactory) }
    val preferences = remember { UserPreferencesStore(context) }
    val openLinksInApp by preferences.openLinksInApp.collectAsStateWithLifecycle(initialValue = true)
    val openLink: (String) -> Unit = { url ->
        val uri = url.toUri()
        if (uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            val tagIndex = uri.pathSegments.indexOf("tags")
            val hashtag = uri.pathSegments.getOrNull(tagIndex + 1)
            if (tagIndex >= 0 && !hashtag.isNullOrBlank()) {
                navController.navigate(Route.HashtagTimeline(hashtag)) { launchSingleTop = true }
            } else if (openLinksInApp) {
                navController.navigate(Route.WebPage(url)) { launchSingleTop = true }
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
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
        composable<Route.Timeline>(
            popEnterTransition = { EnterTransition.None },
        ) {
            val timelineViewModel: TimelineViewModel = viewModel(
                factory = TimelineViewModel.Factory(timelineRepository, authRepository),
            )
            HomeTimelineScreen(
                viewModel = timelineViewModel,
                onLoggedOut = {
                    navController.navigate(Route.Login) {
                        popUpTo(Route.Timeline) { inclusive = true }
                    }
                },
                onStatusClick = { statusId ->
                    navController.navigate(Route.StatusDetail(statusId)) { launchSingleTop = true }
                },
                onCompose = { replyToId -> navController.navigate(Route.ComposePost(replyToId)) },
                onOpenLink = openLink,
                openLinksInApp = openLinksInApp,
                onOpenLinksInAppChange = { enabled ->
                    scope.launch { preferences.setOpenLinksInApp(enabled) }
                },
                onAccountClick = { accountId ->
                    navController.navigate(Route.AccountProfile(accountId)) { launchSingleTop = true }
                },
            )
        }
        composable<Route.StatusDetail>(
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(240)) + fadeIn(tween(180))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220))
            },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.StatusDetail>()
            val detailViewModel: StatusDetailViewModel = viewModel(
                factory = StatusDetailViewModel.Factory(
                    route.statusId,
                    timelineRepository,
                    authRepository,
                ),
            )
            StatusDetailScreen(
                detailViewModel,
                onBack = { navController.popBackStack() },
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onOpenLink = openLink,
            )
        }
        composable<Route.ComposePost> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.ComposePost>()
            val composeViewModel: ComposePostViewModel = viewModel(
                factory = ComposePostViewModel.Factory(
                    route.replyToId,
                    timelineRepository,
                    authRepository,
                ),
            )
            ComposePostScreen(
                viewModel = composeViewModel,
                isReply = route.replyToId != null,
                onClose = { navController.popBackStack() },
                onPosted = { navController.popBackStack() },
            )
        }
        composable<Route.WebPage>(
            enterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(140)) },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.WebPage>()
            InAppWebScreen(route.url, onBack = { navController.popBackStack() })
        }
        composable<Route.AccountProfile>(
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(200))
            },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.AccountProfile>()
            val profileViewModel: AccountProfileViewModel = viewModel(
                factory = AccountProfileViewModel.Factory(
                    route.accountId,
                    timelineRepository,
                    authRepository,
                ),
            )
            AccountProfileScreen(
                viewModel = profileViewModel,
                onBack = { navController.popBackStack() },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onReply = { navController.navigate(Route.ComposePost(it.statusId)) },
                onOpenLink = openLink,
            )
        }
        composable<Route.HashtagTimeline>(
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(200))
            },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.HashtagTimeline>()
            val hashtagViewModel: HashtagTimelineViewModel = viewModel(
                factory = HashtagTimelineViewModel.Factory(
                    route.hashtag,
                    timelineRepository,
                    authRepository,
                ),
            )
            HashtagTimelineScreen(
                hashtag = route.hashtag,
                viewModel = hashtagViewModel,
                onBack = { navController.popBackStack() },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onOpenLink = openLink,
            )
        }
    }
}
