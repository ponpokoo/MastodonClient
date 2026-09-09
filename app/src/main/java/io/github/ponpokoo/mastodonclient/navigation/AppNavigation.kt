package io.github.ponpokoo.mastodonclient.navigation

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
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
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
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
    val appPreferences by preferences.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
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
    val openMedia: (MediaAttachment) -> Unit = { media ->
        media.url?.let { url ->
            navController.navigate(Route.MediaViewer(url, media.type, media.description)) {
                launchSingleTop = true
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = Route.Login,
        enterTransition = { fadeIn(tween(140)) },
        exitTransition = { fadeOut(tween(100)) },
        popEnterTransition = { fadeIn(tween(140)) },
        popExitTransition = { fadeOut(tween(100)) },
    ) {
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
        composable<Route.AddAccount> {
            val loginViewModel: LoginViewModel = viewModel(
                factory = LoginViewModel.Factory(
                    DefaultInstanceRepository(apiClientFactory),
                    authRepository,
                    restoreExistingSession = false,
                ),
            )
            InstanceLoginScreen(loginViewModel) {
                navController.navigate(Route.Timeline) {
                    popUpTo(Route.Timeline) { inclusive = true }
                }
            }
        }
        composable<Route.Timeline> {
            val timelineViewModel: TimelineViewModel = viewModel(
                factory = TimelineViewModel.Factory(
                    timelineRepository,
                    authRepository,
                    preferences,
                    networkIsWifi = {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        manager.getNetworkCapabilities(manager.activeNetwork)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    },
                ),
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
                onMediaClick = openMedia,
                onSettings = { navController.navigate(Route.Settings) },
                onEditProfile = { accountId ->
                    navController.navigate(Route.AccountProfile(accountId, openEditor = true)) { launchSingleTop = true }
                },
                onFollowers = { accountId -> navController.navigate(Route.AccountList(accountId, followers = true)) },
                onFollowing = { accountId -> navController.navigate(Route.AccountList(accountId, followers = false)) },
            )
        }
        composable<Route.StatusDetail> { backStackEntry ->
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
                preferences = appPreferences,
                onBack = { navController.popBackStack() },
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
            )
        }
        composable<Route.ComposePost> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.ComposePost>()
            val composeViewModel: ComposePostViewModel = viewModel(
                factory = ComposePostViewModel.Factory(
                    route.replyToId,
                    timelineRepository,
                    authRepository,
                    preferences,
                    deleteDraftFile = { uri ->
                        uri.toUri().path?.let { java.io.File(it).delete() }
                    },
                ),
            )
            ComposePostScreen(
                viewModel = composeViewModel,
                isReply = route.replyToId != null,
                onClose = { navController.popBackStack() },
                onPosted = { navController.popBackStack() },
            )
        }
        composable<Route.Settings> {
            val activeSession by produceState<io.github.ponpokoo.mastodonclient.domain.model.AccountSession?>(null) {
                value = authRepository.restoreSession()
            }
            val sessions by produceState<List<io.github.ponpokoo.mastodonclient.domain.model.AccountSession>>(emptyList()) {
                value = authRepository.getSessions()
            }
            SettingsScreen(
                store = preferences,
                activeSession = activeSession,
                sessions = sessions,
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(Route.AddAccount) },
                onLogout = {
                    scope.launch {
                        authRepository.logout()
                        val destination = if (authRepository.restoreSession() == null) Route.Login else Route.Timeline
                        navController.navigate(destination) { popUpTo(Route.Timeline) { inclusive = true } }
                    }
                },
            )
        }
        composable<Route.WebPage> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.WebPage>()
            InAppWebScreen(route.url, onBack = { navController.popBackStack() })
        }
        composable<Route.AccountProfile> { backStackEntry ->
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
                openEditor = route.openEditor,
                preferences = appPreferences,
                onBack = { navController.popBackStack() },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onReply = { navController.navigate(Route.ComposePost(it.statusId)) },
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onFollowers = { navController.navigate(Route.AccountList(it, followers = true)) },
                onFollowing = { navController.navigate(Route.AccountList(it, followers = false)) },
            )
        }
        composable<Route.AccountList> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.AccountList>()
            val accountListViewModel: AccountListViewModel = viewModel(
                factory = AccountListViewModel.Factory(
                    route.accountId,
                    route.followers,
                    timelineRepository,
                    authRepository,
                ),
            )
            AccountListScreen(
                title = if (route.followers) "フォロワー" else "フォロー中",
                viewModel = accountListViewModel,
                onBack = { navController.popBackStack() },
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
            )
        }
        composable<Route.HashtagTimeline> { backStackEntry ->
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
                preferences = appPreferences,
                onBack = { navController.popBackStack() },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
            )
        }
        composable<Route.MediaViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.MediaViewer>()
            MediaViewerScreen(
                url = route.url,
                type = route.type,
                description = route.description,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
