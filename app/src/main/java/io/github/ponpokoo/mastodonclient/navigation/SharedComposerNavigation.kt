package io.github.ponpokoo.mastodonclient.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import io.github.ponpokoo.mastodonclient.feature.compose.IncomingShare

internal fun NavController.openSharedComposer(shared: IncomingShare) {
    val replacingComposer = currentDestination?.hasRoute<Route.ComposePost>() == true
    navigate(Route.ComposePost(
        sharedText = shared.text,
        sharedMediaUris = shared.mediaUris,
        shareRequestId = shared.requestId,
    )) {
        // SingleTop would reuse the old ViewModel and ignore the new share arguments.
        if (replacingComposer) popUpTo<Route.ComposePost> { inclusive = true }
    }
}
