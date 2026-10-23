package io.github.ponpokoo.mastodonclient.navigation

import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.core.graphics.drawable.toDrawable
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.repository.DefaultInstanceRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.feature.login.InstanceLoginScreen
import io.github.ponpokoo.mastodonclient.feature.login.LoginViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.status.LocalReactionListOpener
import io.github.ponpokoo.mastodonclient.feature.status.LocalFavouriteListOpener
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalCustomReactionEmojiLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistoryLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistorySaver
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalEmojiFavoritesLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalEmojiFavoriteUpdater
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalEmojiFavoritesSessionKey
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelFactory
import io.github.ponpokoo.mastodonclient.feature.common.StatusInteractionsViewModel
import io.github.ponpokoo.mastodonclient.feature.common.StatusAccountsRequest
import io.github.ponpokoo.mastodonclient.feature.common.StatusAccountsUiState
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenRequest
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.feature.detail.StatusAccountsDialog
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import io.github.ponpokoo.mastodonclient.feature.compose.IncomingShareBus
import io.github.ponpokoo.mastodonclient.feature.web.WebLinkLauncher
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesScreen
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.media.MediaTransitionHost
import io.github.ponpokoo.mastodonclient.feature.media.rememberMediaTransitionState
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private data class PendingNotificationNavigation(
    val source: NotificationOpenRequest,
    val target: NotificationOpenRequest,
    val backStackEntryId: String,
)

@Composable
fun AppNavigation(
    preferences: UserPreferencesStore,
    notificationOpenRequest: NotificationOpenRequest? = null,
    onNotificationOpenHandled: (NotificationOpenRequest) -> Unit = {},
    onMediaVisibilityChanged: (Boolean) -> Unit = {},
) {
    val appLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val apiClientFactory = remember { ApiClientFactory() }
    val authStore = remember { SecureAuthStore(context) }
    val serverSuggestionsRepository = remember {
        io.github.ponpokoo.mastodonclient.data.repository.DefaultServerSuggestionsRepository(
            io.github.ponpokoo.mastodonclient.data.remote.ServerCatalogRemoteDataSource(),
            io.github.ponpokoo.mastodonclient.data.local.FileServerSuggestionsLocalDataSource(context),
        )
    }
    val notificationLocalDataSource = remember {
        io.github.ponpokoo.mastodonclient.data.local.RoomNotificationLocalDataSource(
            io.github.ponpokoo.mastodonclient.data.local.BrowsingDatabase.get(context),
            isAccountPresent = { session -> authStore.getSessions().any {
                it.sessionId == session.sessionId && it.instanceUrl.trimEnd('/') == session.instanceUrl.trimEnd('/')
            } },
        )
    }
    val pushRuntime = remember { io.github.ponpokoo.mastodonclient.notification.PushRuntime.get(context) }
    val homeTimelineLocalDataSource = remember {
        io.github.ponpokoo.mastodonclient.data.local.RoomHomeTimelineLocalDataSource(
            io.github.ponpokoo.mastodonclient.data.local.BrowsingDatabase.get(context),
            isAccountPresent = { session -> authStore.getSessions().any {
                it.sessionId == session.sessionId && it.instanceUrl.trimEnd('/') == session.instanceUrl.trimEnd('/')
            } },
        )
    }
    val networkAvailability = remember { io.github.ponpokoo.mastodonclient.data.local.NetworkAvailability(context) }
    val accountDisplay = remember { io.github.ponpokoo.mastodonclient.data.repository.AccountDisplaySynchronizer(authStore) }
    val authRepository = remember { DefaultAuthRepository(apiClientFactory, authStore, pushRuntime.control, notificationLocalDataSource, homeTimelineLocalDataSource, accountDisplay,
        io.github.ponpokoo.mastodonclient.data.local.DefaultAccountDataLocalDataSource(preferences,
            io.github.ponpokoo.mastodonclient.data.local.DraftMediaDataSource(context),
            io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource(context))) }
    val pushSettings: io.github.ponpokoo.mastodonclient.feature.settings.PushSettingsViewModel = viewModel(
        factory = ScreenViewModelFactory { io.github.ponpokoo.mastodonclient.feature.settings.PushSettingsViewModel(pushRuntime.control, authRepository) },
    )
    androidx.compose.runtime.DisposableEffect(appLifecycle, pushSettings) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            pushSettings.setForeground(appLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        }
        appLifecycle.addObserver(observer)
        pushSettings.setForeground(appLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        onDispose { appLifecycle.removeObserver(observer); pushSettings.setForeground(false) }
    }
    val pushCallback by io.github.ponpokoo.mastodonclient.feature.login.OAuthCallbackBus.callback.collectAsStateWithLifecycle()
    LaunchedEffect(pushCallback) {
        pushCallback?.let { callback ->
            if (pushSettings.handlesCallback()) {
                io.github.ponpokoo.mastodonclient.feature.login.OAuthCallbackBus.consume(callback)
                pushSettings.complete(callback)
            }
        }
    }
    val authorizationUrl by pushSettings.authorizationUrl.collectAsStateWithLifecycle()
    val browserContext = LocalContext.current
    val webLinkLauncher = remember(browserContext) { WebLinkLauncher(browserContext) }
    LaunchedEffect(authorizationUrl) {
        authorizationUrl?.let { url ->
            try {
                androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(browserContext, url.toUri())
                pushSettings.urlOpened()
            } catch (_: ActivityNotFoundException) { pushSettings.browserFailed() }
        }
    }
    val startupRoute by produceState<Route?>(initialValue = null, authRepository) {
        value = if (runCatching { authRepository.restoreSession() }.getOrNull() == null) Route.Login else Route.Timeline
    }
    var readyNotificationNavigation by remember { mutableStateOf<PendingNotificationNavigation?>(null) }
    LaunchedEffect(notificationOpenRequest, startupRoute) {
        val request = notificationOpenRequest ?: run {
            readyNotificationNavigation = null
            return@LaunchedEffect
        }
        if (startupRoute == null) return@LaunchedEffect
        readyNotificationNavigation = null
        val sessionId = request.sessionId.ifBlank {
            runCatching { authRepository.restoreSession()?.sessionId }.getOrNull().orEmpty()
        }
        val accountExists = runCatching { authRepository.getSessions().any { it.sessionId == sessionId } }
            .getOrDefault(false)
        if (!accountExists) {
            onNotificationOpenHandled(request)
            return@LaunchedEffect
        }
        navController.prepareNotificationTimeline()
        val targetEntryId = navController.currentBackStackEntry?.id ?: return@LaunchedEffect
        readyNotificationNavigation = PendingNotificationNavigation(
            request, request.copy(sessionId = sessionId), targetEntryId,
        )
    }
    val authenticationChange by pushSettings.authenticated.collectAsStateWithLifecycle()
    LaunchedEffect(authenticationChange, startupRoute) {
        if (authenticationChange > 0 && startupRoute != null) {
            val destination = if (pushSettings.requiresLogin) Route.Login else Route.Timeline
            navController.navigate(destination) { popUpTo(navController.graph.id) { inclusive = true }; launchSingleTop = true }
            pushSettings.navigationHandled()
        }
    }
    val wordMuteRepository = remember(preferences) {
        io.github.ponpokoo.mastodonclient.data.repository.DefaultWordMuteRepository(preferences, scope)
    }
    val timelineRepository = remember { DefaultTimelineRepository(apiClientFactory,
        capabilitiesRepository = io.github.ponpokoo.mastodonclient.notification.PushRuntime.get(context).capabilities,
        wordMuteRepository = wordMuteRepository,
        settingsStore = preferences, settingsScope = scope,
        accountDisplay = accountDisplay,
        notificationLocalDataSource = notificationLocalDataSource,
        homeTimelineLocalDataSource = homeTimelineLocalDataSource,
        networkAvailable = networkAvailability::isAvailable,
        onReactionSucceeded = { session, emoji -> preferences.recordReaction(session.sessionId, emoji) }) }
    val profileImages = remember {
        io.github.ponpokoo.mastodonclient.data.repository.DefaultProfileImageRepository(
            io.github.ponpokoo.mastodonclient.data.local.ProfileImageDataSource(context))
    }
    val mainSessionFactory = remember(authRepository, timelineRepository, preferences, appLifecycle) {
        ScreenViewModelFactory {
                MainSessionViewModel(authRepository, timelineRepository, preferences,
                    pushSync = pushRuntime.sync,
                    systemNotifications = io.github.ponpokoo.mastodonclient.data.repository.DefaultSystemNotificationRepository(
                        io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource(context),
                    ), networkIsWifi = {
                    val manager = context.getSystemService(ConnectivityManager::class.java)
                    manager.getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }).also { it.bindForegroundLifecycle(appLifecycle) }
        }
    }
    val timelineEntry = remember(currentBackStackEntry) {
        currentBackStackEntry?.let { runCatching { navController.getBackStackEntry<Route.Timeline>() }.getOrNull() }
    }
    val mainSessionModel = timelineEntry?.let { entry ->
        viewModel<MainSessionViewModel>(viewModelStoreOwner = entry, factory = mainSessionFactory)
    }
    val readingSession = mainSessionModel?.uiState?.collectAsStateWithLifecycle()?.value?.session
    val mediaSnapshot = mainSessionModel?.browsing?.snapshot?.collectAsStateWithLifecycle()?.value
    val mediaSessionKey = mediaSnapshot?.account?.let {
        "${it.sessionId}|${it.instanceUrl}|${mediaSnapshot.generation}"
    }
    val mediaMotion = rememberMediaTransitionState()
    SideEffect {
        onMediaVisibilityChanged(mediaMotion.active || currentBackStackEntry?.destination?.hasRoute<Route.MediaViewer>() == true)
    }
    val interactions = timelineEntry?.let { entry ->
        viewModel<StatusInteractionsViewModel>(viewModelStoreOwner = entry, factory = ScreenViewModelFactory {
            StatusInteractionsViewModel(timelineRepository, requireNotNull(mainSessionModel).browsing, preferences)
        })
    }
    val accountsState = interactions?.accounts?.collectAsStateWithLifecycle()?.value ?: StatusAccountsUiState()
    val capabilitiesModel = timelineEntry?.let { entry ->
        viewModel<io.github.ponpokoo.mastodonclient.feature.common.ServerCapabilitiesViewModel>(
            viewModelStoreOwner = entry, factory = ScreenViewModelFactory {
                io.github.ponpokoo.mastodonclient.feature.common.ServerCapabilitiesViewModel(
                    timelineRepository, requireNotNull(mainSessionModel).browsing)
            })
    }
    val capabilitiesState = capabilitiesModel?.state?.collectAsStateWithLifecycle()?.value
    val serverCapabilities = capabilitiesState?.takeIf { it.snapshot == mediaSnapshot }?.capabilities
        ?: io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        capabilitiesModel?.refresh()
    }
    LaunchedEffect(capabilitiesModel, currentBackStackEntry?.id) { capabilitiesModel?.refresh() }
    val statusActionManager = remember(timelineRepository) { StatusActionManager(timelineRepository) }
    val navigationJson = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val openLinksInApp by preferences.openLinksInApp.collectAsStateWithLifecycle(initialValue = true)
    val appPreferences by preferences.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
    val incomingShare by IncomingShareBus.share.collectAsStateWithLifecycle()
    val composerModels = remember { mutableMapOf<String, ComposePostViewModel>() }
    LaunchedEffect(incomingShare, startupRoute, currentBackStackEntry?.destination?.route) {
        val shared = incomingShare ?: return@LaunchedEffect
        if (startupRoute == null || authRepository.restoreSession() == null) return@LaunchedEffect
        val navigate = { navController.openSharedComposer(shared) }
        val currentComposer = navController.currentBackStackEntry?.id?.let(composerModels::get)
        if (currentComposer == null) {
            navigate()
        } else if (!currentComposer.retainInputThen(navigate)) {
            return@LaunchedEffect
        }
        IncomingShareBus.consume(shared.requestId)
    }
    val openLink: (String) -> Unit = { url ->
        val uri = url.toUri()
        if (uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            val tagIndex = uri.pathSegments.indexOf("tags")
            val hashtag = uri.pathSegments.getOrNull(tagIndex + 1)
            if (uri.isYouTubeUrl()) {
                val openYoutube = Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.google.android.youtube")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(openYoutube)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } else if (tagIndex >= 0 && !hashtag.isNullOrBlank()) {
                navController.openHashtag(hashtag)
            } else {
                if (!webLinkLauncher.open(uri, inApp = openLinksInApp)) {
                    Toast.makeText(browserContext, "リンクを開けるブラウザーが見つかりません", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val openMedia: (List<MediaAttachment>, Int) -> Unit = { media, initialIndex ->
        val selectedId = media.getOrNull(initialIndex)?.id
        val availableMedia = media.filter { it.url != null || it.previewUrl != null }
        if (availableMedia.isNotEmpty()) {
            val resolvedIndex = availableMedia.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            mediaSessionKey?.let { session ->
                currentBackStackEntry?.id?.let { owner -> mediaMotion.open(availableMedia, resolvedIndex, session, owner) }
            }
        }
    }
    val openQuote: (TimelineStatus, QuoteMode) -> Unit = { status, mode ->
        navController.navigate(Route.ComposePost(
            quoteStatusId = status.statusId,
            quoteStatusUrl = status.url,
            nativeQuote = mode == QuoteMode.Native,
        ))
    }
    val loadCustomReactionEmojis: suspend () -> Result<List<CustomEmoji>> = {
        interactions?.loadCustomEmojis() ?: Result.failure(IllegalStateException("ログインし直してください"))
    }
    val loadReactionHistory: suspend () -> List<String> = { interactions?.loadReactionHistory().orEmpty() }
    val saveReactionHistory: suspend (List<String>) -> Unit = { interactions?.saveReactionHistory(it); Unit }
    val favoritesSessionId = readingSession?.sessionId.orEmpty()
    val loadEmojiFavorites: suspend () -> List<String> = remember(interactions, mediaSessionKey, favoritesSessionId) {
        { interactions?.loadEmojiFavorites(favoritesSessionId).orEmpty() }
    }
    val updateEmojiFavorite: suspend (String, Boolean) -> List<String> = remember(interactions, mediaSessionKey, favoritesSessionId) {
        { emoji, favorite -> interactions?.setEmojiFavorite(favoritesSessionId, emoji, favorite)
            ?: error("お気に入りを保存できませんでした") }
    }
    MediaTransitionHost(mediaMotion, mediaSessionKey, currentBackStackEntry?.id,
        sessionReady = (mediaSnapshot?.generation ?: 0) > 0 || currentBackStackEntry?.destination?.hasRoute<Route.Login>() == true) {
    CompositionLocalProvider(
        io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities provides serverCapabilities,
        io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings provides
            io.github.ponpokoo.mastodonclient.feature.common.ReadingSettings(
                readingSession?.let { it.sessionId + it.instanceUrl } ?: "",
                appPreferences.readingForAccount(readingSession),
                transientSessionKey = mediaSessionKey.orEmpty()),
        LocalFavouriteListOpener provides { statusId -> interactions?.openFavourites(statusId); Unit },
        LocalReactionListOpener provides { statusId, reaction -> interactions?.openReaction(statusId, reaction); Unit },
        LocalCustomReactionEmojiLoader provides loadCustomReactionEmojis,
        LocalReactionHistoryLoader provides loadReactionHistory,
        LocalReactionHistorySaver provides saveReactionHistory,
        LocalEmojiFavoritesLoader provides loadEmojiFavorites,
        LocalEmojiFavoriteUpdater provides updateEmojiFavorite,
        LocalEmojiFavoritesSessionKey provides mediaSessionKey.orEmpty(),
    ) {
    val initialRoute = startupRoute
    if (initialRoute == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else ScreenNavHost(
        navController = navController,
        startDestination = initialRoute,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        animatedScreen<Route.Login> {
            val loginViewModel: LoginViewModel = viewModel(
                factory = LoginViewModel.Factory(
                    DefaultInstanceRepository(apiClientFactory),
                    authRepository,
                    serverSuggestionsRepository = serverSuggestionsRepository,
                ),
            )
            InstanceLoginScreen(loginViewModel) {
                navController.navigate(Route.Timeline) {
                    popUpTo(Route.Login) { inclusive = true }
                }
            }
        }
        animatedScreen<Route.AddAccount> {
            val loginViewModel: LoginViewModel = viewModel(
                factory = LoginViewModel.Factory(
                    DefaultInstanceRepository(apiClientFactory),
                    authRepository,
                    restoreExistingSession = false,
                    serverSuggestionsRepository = serverSuggestionsRepository,
                ),
            )
            InstanceLoginScreen(loginViewModel, onBack = { navController.popBackStack() }) {
                navController.navigate(Route.Timeline) {
                    popUpTo(Route.Timeline) { inclusive = true }
                }
            }
        }
        animatedScreen<Route.Timeline> { backStackEntry ->
            val mainViewModel: MainSessionViewModel = viewModel(factory = mainSessionFactory)
            SideEffect { mainViewModel.bindForegroundLifecycle(appLifecycle) }
            val browsing = mainViewModel.browsing
            val timelineViewModel: TimelineViewModel = viewModel(
                factory = TimelineViewModel.Factory(timelineRepository, browsing),
            )
            val searchViewModel: SearchViewModel = viewModel(factory = ScreenViewModelFactory {
                SearchViewModel(timelineRepository, browsing)
            })
            val notificationsViewModel: NotificationsViewModel = viewModel(factory = ScreenViewModelFactory {
                NotificationsViewModel(timelineRepository, browsing,
                    io.github.ponpokoo.mastodonclient.data.repository.DefaultSystemNotificationRepository(
                        io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource(context),
                    ),
                    pushSync = pushRuntime.sync,
                )
            })
            val profileViewModel: ProfileViewModel = viewModel(factory = ScreenViewModelFactory {
                ProfileViewModel(timelineRepository, browsing, authRepository, profileImages, statusActionManager = statusActionManager)
            })
            val actionsViewModel: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory {
                StatusActionsViewModel(timelineRepository, browsing, statusActionManager)
            })
            HomeTimelineScreen(
                fabPositionStore = preferences,
                viewModel = timelineViewModel,
                mainViewModel = mainViewModel,
                searchViewModel = searchViewModel,
                notificationsViewModel = notificationsViewModel,
                profileViewModel = profileViewModel,
                actionsViewModel = actionsViewModel,
                notificationOpenRequest = readyNotificationNavigation
                    ?.takeIf { it.backStackEntryId == backStackEntry.id }?.target,
                onNotificationOpenHandled = { handled ->
                    val pending = readyNotificationNavigation
                    if (pending?.target == handled && pending.backStackEntryId == backStackEntry.id) {
                        readyNotificationNavigation = null
                        onNotificationOpenHandled(pending.source)
                    }
                },
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
                onOpenTag = { name, trend -> navController.openHashtag(name, trend) },
                onOpenLinksInAppChange = { enabled ->
                    scope.launch { preferences.setOpenLinksInApp(enabled) }
                },
                onAccountClick = { accountId ->
                    navController.navigate(Route.AccountProfile(accountId)) { launchSingleTop = true }
                },
                onMediaClick = openMedia,
                onSettings = { navController.navigate(Route.Settings) },
                onFollowers = { accountId -> navController.navigate(Route.AccountList(accountId, followers = true)) },
                onFollowing = { accountId -> navController.navigate(Route.AccountList(accountId, followers = false)) },
                onEditStatus = { statusId -> navController.navigate(Route.ComposePost(editStatusId = statusId)) },
                onOpenLists = { navController.navigate(Route.Lists) },
                onOpenBookmarks = { navController.navigate(Route.SavedTimeline("bookmarks", title = "ブックマーク")) },
                onOpenFavourites = { navController.navigate(Route.SavedTimeline("favourites", title = "お気に入り")) },
                onOpenReactions = { navController.navigate(Route.SavedTimeline("reactions", title = "リアクションした投稿")) },
            )
        }
        animatedScreen<Route.StatusDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.StatusDetail>()
            val detailViewModel: StatusDetailViewModel = viewModel(
                key = "status-detail-${route.statusId}",
                factory = StatusDetailViewModel.Factory(
                    route.statusId,
                    timelineRepository,
                    authRepository,
                    statusActionManager,
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
                onShowBoosters = { interactions?.openBoosters(it) },
                onShowFavourites = { interactions?.openFavourites(it) },
                onShowReaction = { statusId, reaction -> interactions?.openReaction(statusId, reaction) },
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
            val darkTheme = when (appPreferences.themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.System -> isSystemInDarkTheme()
            }
            SideEffect {
                dialogWindow?.setDimAmount(0f)
                dialogWindow?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
                dialogWindow?.let { window ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                dialogWindow?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                )
                dialogWindow?.let { window ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
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
                    initialSharedText = route.sharedText,
                    initialSharedMediaUris = (route.sharedMediaUris + listOfNotNull(route.sharedImageUri)).distinct(),
                ),
            )
            androidx.compose.runtime.DisposableEffect(backStackEntry.id, composeViewModel) {
                composerModels[backStackEntry.id] = composeViewModel
                onDispose { composerModels.remove(backStackEntry.id) }
            }
            ComposePostScreen(
                viewModel = composeViewModel,
                isEditing = route.editStatusId != null,
                onClose = { navController.popBackStack() },
                onPosted = { navController.popBackStack() },
            )
        }
        animatedScreen<Route.Settings> { backStackEntry ->
            val mainEntry = remember(backStackEntry) { navController.getBackStackEntry<Route.Timeline>() }
            val mainModel: MainSessionViewModel = viewModel(viewModelStoreOwner = mainEntry)
            val accountState by mainModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                licenses = viewModel<io.github.ponpokoo.mastodonclient.feature.settings.LicensesViewModel>(
                    factory = ScreenViewModelFactory {
                        io.github.ponpokoo.mastodonclient.feature.settings.LicensesViewModel(
                            io.github.ponpokoo.mastodonclient.data.repository.DefaultAppLicensesRepository(
                                io.github.ponpokoo.mastodonclient.data.local.AppLicensesLocalDataSource(context),
                            ),
                        )
                    },
                ),
                maintenance = viewModel<io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel>(
                    factory = ScreenViewModelFactory {
                        io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel(
                            io.github.ponpokoo.mastodonclient.data.repository.DefaultAppMaintenanceRepository(
                                io.github.ponpokoo.mastodonclient.data.local.AppMaintenanceDataSource(context),
                            ),
                        )
                    },
                ),
                store = preferences,
                activeSession = accountState.session,
                sessions = accountState.sessions,
                pushSettings = pushSettings,
                posting = viewModel<io.github.ponpokoo.mastodonclient.feature.settings.PostingSettingsViewModel>(
                    factory = ScreenViewModelFactory {
                        io.github.ponpokoo.mastodonclient.feature.settings.PostingSettingsViewModel(timelineRepository, mainModel.browsing)
                    },
                ),
                moderation = viewModel<io.github.ponpokoo.mastodonclient.feature.settings.ModerationManagementViewModel>(
                    factory = ScreenViewModelFactory {
                        io.github.ponpokoo.mastodonclient.feature.settings.ModerationManagementViewModel(timelineRepository, authRepository, wordRepository = wordMuteRepository)
                    },
                ),
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(Route.AddAccount) },
                onLogout = pushSettings::logout,
                onMoveAccount = mainModel::moveAccount,
                changingAccountOrder = accountState.changingAccountOrder,
                accountOrderError = accountState.accountOrderError,
            )
        }
        // Older versions may restore a WebView entry. Replace it with a browser tab once.
        animatedScreen<Route.WebPage> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.WebPage>()
            LaunchedEffect(backStackEntry.id) {
                navController.popBackStack()
                if (!webLinkLauncher.open(route.url.toUri(), inApp = true)) {
                    Toast.makeText(browserContext, "リンクを開けるブラウザーが見つかりません", Toast.LENGTH_SHORT).show()
                }
            }
        }
        animatedScreen<Route.AccountProfile> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.AccountProfile>()
            val mainEntry = remember(backStackEntry) { navController.getBackStackEntry<Route.Timeline>() }
            val mainModel: MainSessionViewModel = viewModel(viewModelStoreOwner = mainEntry, factory = mainSessionFactory)
            val profileViewModel: ProfileViewModel = viewModel(factory = ScreenViewModelFactory {
                ProfileViewModel(timelineRepository, mainModel.browsing, authRepository, profileImages,
                    accountId = route.accountId, statusActionManager = statusActionManager)
            })
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
                onOpenReactions = { navController.navigate(Route.SavedTimeline("reactions", title = "リアクションした投稿")) },
                onEditStatus = { navController.navigate(Route.ComposePost(editStatusId = it)) },
            )
        }
        animatedScreen<Route.AccountList> { backStackEntry ->
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
        animatedScreen<Route.HashtagTimeline> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.HashtagTimeline>()
            val mainEntry = remember(backStackEntry) { navController.getBackStackEntry(Route.Timeline) }
            val mainModel: MainSessionViewModel = viewModel(viewModelStoreOwner = mainEntry)
            val mainState by mainModel.uiState.collectAsStateWithLifecycle()
            val actionsViewModel: StatusActionsViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
                factory = ScreenViewModelFactory {
                    StatusActionsViewModel(timelineRepository, mainModel.browsing, statusActionManager)
                },
            )
            val hashtagViewModel: HashtagTimelineViewModel = viewModel(
                factory = HashtagTimelineViewModel.Factory(
                    route.hashtag,
                    timelineRepository,
                    mainModel.browsing,
                    statusActionManager,
                ),
            )
            HashtagTimelineScreen(
                hashtag = route.hashtag,
                fromTrend = route.fromTrend,
                viewModel = hashtagViewModel,
                actionsViewModel = actionsViewModel,
                currentAccountId = mainState.session?.accountId,
                preferences = appPreferences,
                onBack = { navController.popBackStack() },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onQuote = openQuote,
                onOpenLink = openLink,
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onEditStatus = { navController.navigate(Route.ComposePost(editStatusId = it)) },
            )
        }
        animatedScreen<Route.Lists> { backStackEntry ->
            val mainEntry = remember(backStackEntry) { navController.getBackStackEntry(Route.Timeline) }
            val mainModel: MainSessionViewModel = viewModel(viewModelStoreOwner = mainEntry)
            val mainState by mainModel.uiState.collectAsStateWithLifecycle()
            val actionsViewModel: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory {
                StatusActionsViewModel(timelineRepository, mainModel.browsing, statusActionManager)
            })
            val savedViewModel: SavedTimelinesViewModel = viewModel(
                factory = ScreenViewModelFactory {
                    SavedTimelinesViewModel(null, null, timelineRepository, mainModel.browsing)
                },
            )
            SavedTimelinesScreen(
                title = "リスト",
                viewModel = savedViewModel,
                actionsViewModel = actionsViewModel,
                currentAccountId = mainState.session?.accountId,
                preferences = appPreferences,
                showLists = true,
                onBack = { navController.popBackStack() },
                onListClick = { list -> navController.navigate(Route.SavedTimeline("list", list.id, list.title)) },
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onOpenLink = openLink,
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onQuote = openQuote,
                onEditStatus = { navController.navigate(Route.ComposePost(editStatusId = it)) },
            )
        }
        animatedScreen<Route.SavedTimeline> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.SavedTimeline>()
            val kind = when (route.kind) {
                "list" -> SavedTimelineKind.List
                "bookmarks" -> SavedTimelineKind.Bookmarks
                "reactions" -> SavedTimelineKind.Reactions
                else -> SavedTimelineKind.Favourites
            }
            val mainEntry = remember(backStackEntry) { navController.getBackStackEntry(Route.Timeline) }
            val mainModel: MainSessionViewModel = viewModel(viewModelStoreOwner = mainEntry)
            val mainState by mainModel.uiState.collectAsStateWithLifecycle()
            val actionsViewModel: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory {
                StatusActionsViewModel(timelineRepository, mainModel.browsing, statusActionManager)
            })
            val savedViewModel: SavedTimelinesViewModel = viewModel(
                factory = ScreenViewModelFactory {
                    SavedTimelinesViewModel(kind, route.listId, timelineRepository, mainModel.browsing)
                },
            )
            SavedTimelinesScreen(
                title = route.title,
                viewModel = savedViewModel,
                actionsViewModel = actionsViewModel,
                currentAccountId = mainState.session?.accountId,
                preferences = appPreferences,
                showLists = false,
                onBack = { navController.popBackStack() },
                onListClick = {},
                onStatusClick = { navController.navigate(Route.StatusDetail(it)) },
                onAccountClick = { navController.navigate(Route.AccountProfile(it)) },
                onMediaClick = openMedia,
                onOpenLink = openLink,
                onReply = { navController.navigate(Route.ComposePost(it)) },
                onQuote = openQuote,
                onEditStatus = { navController.navigate(Route.ComposePost(editStatusId = it)) },
            )
        }
        // Compatibility for a previously saved media dialog navigation entry.
        // New openings use the overlay and retain their source navigation entry.
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
                dialogWindow?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
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
    accountsState.request?.let { request ->
        androidx.compose.runtime.key(request) {
            StatusAccountsDialog(
                title = when (request) {
                    is StatusAccountsRequest.Favourites -> "お気に入りしたアカウント"
                    is StatusAccountsRequest.Boosters -> "ブーストしたアカウント"
                    is StatusAccountsRequest.Reaction -> if (request.reaction.accountIds.isEmpty()) "リアクションした人"
                        else "${request.reaction.name} を付けた人"
                },
                accounts = accountsState.accounts,
                isLoading = accountsState.isLoading,
                errorMessage = accountsState.error,
                nextMaxId = accountsState.nextMaxId,
                onLoadMore = { interactions?.loadMoreAccounts() },
                onRetry = { interactions?.retryAccounts() },
                onDismiss = { interactions?.dismissAccounts() },
                onAccountClick = { accountId ->
                    interactions?.dismissAccounts()
                    navController.navigate(Route.AccountProfile(accountId))
                },
            )
        }
    }
    }
}

internal fun NavController.prepareNotificationTimeline() {
    if (currentBackStackEntry?.destination?.hasRoute<Route.Timeline>() != true) {
        navigate(Route.Timeline) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

private fun android.net.Uri.isYouTubeUrl(): Boolean {
    val normalizedHost = host?.lowercase() ?: return false
    return normalizedHost == "youtu.be" ||
        normalizedHost == "youtube.com" ||
        normalizedHost.endsWith(".youtube.com") ||
        normalizedHost == "youtube-nocookie.com" ||
        normalizedHost.endsWith(".youtube-nocookie.com")
}
