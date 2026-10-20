package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.feature.media.mediaContentInsets

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountProfileScreen(
    viewModel: ProfileViewModel,
    openEditor: Boolean,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onStatusClick: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onFollowers: (String) -> Unit,
    onFollowing: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenFavourites: () -> Unit,
    onEditStatus: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(contentWindowInsets = mediaContentInsets(), topBar = {
        TopAppBar(windowInsets = mediaContentInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            title = {
                val author = state.profile?.author
                if (author == null) Text("プロフィール") else CustomEmojiText(
                    text = author.displayName, emojis = author.customEmojis,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
        )
    }, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        ProfileScreen(viewModel, preferences, padding, snackbarHostState, onStatusClick, onReply, onQuote,
            onOpenLink, onAccountClick, onMediaClick, onFollowers, onFollowing, onOpenLists,
            onOpenBookmarks, onOpenFavourites, onEditStatus, openEditor = openEditor)
    }
}
