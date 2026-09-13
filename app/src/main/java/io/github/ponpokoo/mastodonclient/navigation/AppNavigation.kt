package io.github.ponpokoo.mastodonclient.navigation

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
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
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelFactory
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
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
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesScreen
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun AppNavigation(preferences: UserPreferencesStore) {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val apiClientFactory = remember { ApiClientFactory() }
    val authStore = remember { SecureAuthStore(context) }
    val authRepository = remember { DefaultAuthRepository(apiClientFactory, authStore) }
    val timelineRepository = remember { DefaultTimelineRepository(apiClientFactory) }
    val navigationJson = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
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
    val openMedia: (List<MediaAttachment>, Int) -> Unit = { media, initialIndex ->
        val selectedId = media.getOrNull(initialIndex)?.id
        val availableMedia = media.filter { it.url != null || it.previewUrl != null }
        if (availableMedia.isNotEmpty()) {
            val resolvedIndex = availableMedia.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            navController.navigate(Route.MediaViewer(navigationJson.encodeToString(availableMedia), resolvedIndex)) {
                launchSingleTop = true
            }
        }
    }
    val openQuote: (TimelineStatus) -> Unit = { status ->
        navController.navigate(Route.ComposePost(
            quoteStatusId = status.statusId,
            quoteStatusUrl = status.url,
            nativeQuote = status.quoteApproval in setOf("automatic", "manual"),
        ))
    }
    NavHost(
        navController = navController,
        startDestination = Route.Login,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220))
        },
        exitTransition = {
            ExitTransition.None
        },
        popEnterTransition = {
            EnterTransition.None
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220))
        },
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
            val mainViewModel: MainSessionViewModel = viewModel(factory = ScreenViewModelFactory {
                MainSessionViewModel(authRepository, timelineRepository, preferences, networkIsWifi = {
                    val manager = context.getSystemService(ConnectivityManager::class.java)
                    manager.getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                })
            })
            val browsing = mainViewModel.browsing
            val timelineViewModel: TimelineViewModel = viewModel(factory = ScreenViewModelFactory {
                TimelineViewModel(timelineRepository, browsing)
            })
            val searchViewModel: SearchViewModel = viewModel(factory = ScreenViewModelFactory {
                SearchViewModel(timelineRepository, browsing)
            })
            val notificationsViewModel: NotificationsViewModel = viewModel(factory = ScreenViewModelFactory {
                NotificationsViewModel(timelineRepository, browsing)
            })
            val profileViewModel: OwnProfileViewModel = viewModel(factory = ScreenViewModelFactory {
                OwnProfileViewModel(timelineRepository, browsing)
            })
            val actionsViewModel: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory {
                StatusActionsViewModel(timelineRepository, browsing)
            })
            HomeTimelineScreen(
                viewModel = timelineViewModel,
                mainViewModel = mainViewModel,
                searchViewModel = searchViewModel,
                notificationsViewModel = notificationsViewModel,
                profileViewModel = profileViewModel,
                actionsViewModel = actionsViewModel,
                onLoggedOut = {
                    navController.navigate(Route.Login) {
                        popUpTo(Route.Timeline) { inclusive = true }
                    }
                },
                onStatusClick = { statusId ->
                    navController.navigate(Route.StatusDetail(statusId)) { launchSingleTop = true }
                },
                onCompose = { replyToId -> navController.navigate(Route.ComposePost(replyToId)) },
                onQuote = openQuote,
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
                onEditStatus = { statusId -> navController.navigate(Route.ComposePost(editStatusId = statusId)) },
                onOpenLists = { navController.navigate(Route.Lists) },
                onOpenBookmarks = { navController.navigate(Route.SavedTimeline("bookmarks", title = "ブックマーク")) },
                onOpenFavourites = { navController.navigate(Route.SavedTimeline("favourites", title = "お気に入り")) },
            )
        }
        composable<Route.StatusDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.StatusDetail>()
            val detailViewModel: StatusDetailViewModel = viewModel(
                key = "status-detail-${route.statusId}",
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
                onQuote = openQuote,
                onEditStatus = { navController.navigate(Route.ComposePost(editStatusId = it)) },
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
            )
        }
        dialog<Route.ComposePost>(
            dialogProperties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) { backStackEntry ->
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.setDimAmount(0f)
                dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                dialogWindow?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                )
            }
            val route = backStackEntry.toRoute<Route.ComposePost>()
            val composeViewModel: ComposePostViewModel = viewModel(
                factory = ComposePostViewModel.Factory(
                    route.replyToId,
                    route.editStatusId,
                    timelineRepository,
                    authRepository,
                    preferences,
                    draftMediaRepository = io.github.ponpokoo.mastodonclient.data.repository.DefaultDraftMediaRepository(
                        io.github.ponpokoo.mastodonclient.data.local.DraftMediaDataSource(context),
                    ),
                    deleteDraftFile = { uri ->
                        uri.toUri().path?.let { java.io.File(it).delete() }
                    },
                    quoteStatusId = route.quoteStatusId,
                    quoteStatusUrl = route.quoteStatusUrl,
                    nativeQuote = route.nativeQuote,
                ),
            )
            ComposePostScreen(
                viewModel = composeViewModel,
                isReply = route.replyToId != null,
                isEditing = route.editStatusId != null,
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
                onQuote = openQuote,
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onFollowers = { navController.navigate(Route.AccountList(it, followers = true)) },
                onFollowing = { navController.navigate(Route.AccountList(it, followers = false)) },
                onOpenLists = { navController.navigate(Route.Lists) },
                onOpenBookmarks = { navController.navigate(Route.SavedTimeline("bookmarks", title = "ブックマーク")) },
                onOpenFavourites = { navController.navigate(Route.SavedTimeline("favourites", title = "お気に入り")) },
                onEditStatus = { navController.navigate(Route.ComposePost(editStatusId = it)) },
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
                onQuote = openQuote,
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
            )
        }
        composable<Route.Lists> {
            val savedViewModel: SavedTimelinesViewModel = viewModel(
                factory = SavedTimelinesViewModel.Factory(null, null, timelineRepository, authRepository),
            )
            SavedTimelinesScreen(
                title = "リスト",
                viewModel = savedViewModel,
                preferences = appPreferences,
                showLists = true,
                onBack = { navController.popBackStack() },
                onListClick = { list -> navController.navigate(Route.SavedTimeline("list", list.id, list.title)) },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onOpenLink = openLink,
                onQuote = openQuote,
            )
        }
        composable<Route.SavedTimeline> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.SavedTimeline>()
            val kind = when (route.kind) {
                "list" -> SavedTimelineKind.List
                "bookmarks" -> SavedTimelineKind.Bookmarks
                else -> SavedTimelineKind.Favourites
            }
            val savedViewModel: SavedTimelinesViewModel = viewModel(
                factory = SavedTimelinesViewModel.Factory(kind, route.listId, timelineRepository, authRepository),
            )
            SavedTimelinesScreen(
                title = route.title,
                viewModel = savedViewModel,
                preferences = appPreferences,
                showLists = false,
                onBack = { navController.popBackStack() },
                onListClick = {},
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onOpenLink = openLink,
                onQuote = openQuote,
            )
        }
        dialog<Route.MediaViewer>(
            dialogProperties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) { backStackEntry ->
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.setDimAmount(0f)
                dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            val route = backStackEntry.toRoute<Route.MediaViewer>()
            val media = remember(route.mediaJson) {
                runCatching { navigationJson.decodeFromString<List<MediaAttachment>>(route.mediaJson) }
                    .getOrDefault(emptyList())
            }
            MediaViewerScreen(
                media = media,
                initialIndex = route.initialIndex,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
