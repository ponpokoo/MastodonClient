package io.github.ponpokoo.transitionprototype

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel

internal class PrototypeEnvironment : ViewModel() {
    val auth = FixtureAuthRepository()
    val timeline = FixtureTimelineRepository()
    val statusActions = StatusActionManager(timeline)
}

/** Production screens and ViewModels are wired to local fixture repositories here. */
@Composable
internal fun PrototypeApp() {
    val environment = viewModel { PrototypeEnvironment() }
    val main = viewModel { MainSessionViewModel(environment.auth, environment.timeline) }
    val timeline = viewModel { TimelineViewModel(environment.timeline, main.browsing) }
    val search = viewModel { SearchViewModel(environment.timeline, main.browsing) }
    val notifications = viewModel { NotificationsViewModel(environment.timeline, main.browsing) }
    val profile = viewModel { OwnProfileViewModel(environment.timeline, main.browsing, environment.auth) }
    val actions = viewModel { StatusActionsViewModel(environment.timeline, main.browsing, environment.statusActions) }
    val mainState by main.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(main) { main.restore() }
    var authorId by rememberSaveable { mutableStateOf<String?>(null) }
    var openLinksInApp by rememberSaveable { mutableStateOf(true) }
    val motion = rememberSaveable(saver = PrototypeMediaMotionState.Saver) { PrototypeMediaMotionState() }
    val context = LocalContext.current
    val placeholder: (String) -> Unit = { Toast.makeText(context, "$it は固定データの試作対象外です", Toast.LENGTH_SHORT).show() }
    val openMedia: (List<MediaAttachment>, Int) -> Unit = motion::open
    BackHandler(enabled = authorId != null && !motion.active) { authorId = null }
    // The transition uses this Activity window; freeze underlying insets while bars are hidden.
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val systemInsets = ScaffoldDefaults.contentWindowInsets
    fun currentInsets() = WindowInsets(systemInsets.getLeft(density, direction), systemInsets.getTop(density),
        systemInsets.getRight(density, direction), systemInsets.getBottom(density))
    var appInsets by remember { mutableStateOf(currentInsets()) }
    SideEffect { if (!motion.active) appInsets = currentInsets() }
    CompositionLocalProvider(LocalPrototypeMotion provides motion, LocalPrototypeInsets provides appInsets) {
        Box(Modifier.fillMaxSize().testTag("prototype_app")) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (authorId == null) 1f else 0f }
                .semantics { if (authorId != null || motion.active) hideFromAccessibility() }) {
                HomeTimelineScreen(timeline, main, search, notifications, profile, actions,
                    onLoggedOut = { main.restore() }, onStatusClick = { placeholder("投稿詳細") },
                    onCompose = { placeholder("投稿作成") }, onQuote = { _, _ -> placeholder("引用") },
                    onOpenLink = { placeholder("リンク") }, openLinksInApp = openLinksInApp,
                    onOpenLinksInAppChange = { openLinksInApp = it }, onAccountClick = { authorId = it },
                    onMediaClick = openMedia, onSettings = { placeholder("設定") },
                    onFollowers = { placeholder("フォロワー") }, onFollowing = { placeholder("フォロー") },
                    onEditStatus = { placeholder("投稿編集") }, onOpenLists = { placeholder("リスト") },
                    onOpenBookmarks = { placeholder("ブックマーク") }, onOpenFavourites = { placeholder("お気に入り一覧") })
            }
            authorId?.let { id ->
                val account = viewModel(key = "profile:$id") { AccountProfileViewModel(id, environment.timeline, environment.auth, environment.statusActions) }
                LaunchedEffect(account) { account.retry() }
                CompositionLocalProvider(LocalPrototypeThumbnailOwner provides "account:$id") {
                    Box(Modifier.fillMaxSize().testTag("account_profile").semantics { if (motion.active) hideFromAccessibility() }) {
                        AccountProfileScreen(account, openEditor = false, preferences = mainState.preferences,
                            onBack = { authorId = null }, onStatusClick = { placeholder("投稿詳細") },
                            onReply = { placeholder("返信") }, onQuote = { _, _ -> placeholder("引用") },
                            onOpenLink = { placeholder("リンク") }, onAccountClick = { authorId = it }, onMediaClick = openMedia,
                            onFollowers = { placeholder("フォロワー") }, onFollowing = { placeholder("フォロー") },
                            onOpenLists = { placeholder("リスト") }, onOpenBookmarks = { placeholder("ブックマーク") },
                            onOpenFavourites = { placeholder("お気に入り一覧") }, onEditStatus = { placeholder("投稿編集") })
                    }
                }
            }
            PrototypeMediaOverlay(motion)
        }
    }
}
