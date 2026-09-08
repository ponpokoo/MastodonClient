package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.timeline.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountProfileScreen(
    viewModel: AccountProfileViewModel,
    onBack: () -> Unit,
    onStatusClick: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.profile?.author?.displayName ?: "プロフィール") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        ProfileContent(
            state = TimelineUiState(
                profile = state.profile,
                isLoadingProfile = state.isLoading,
                profileError = state.errorMessage,
            ),
            padding = padding,
            onRetry = viewModel::retry,
            onStatusClick = onStatusClick,
            onOpenLink = onOpenLink,
            onReply = onReply,
            onBoost = viewModel::toggleReblog,
            onFavourite = viewModel::toggleFavourite,
            onReact = viewModel::setReaction,
        )
    }
}
