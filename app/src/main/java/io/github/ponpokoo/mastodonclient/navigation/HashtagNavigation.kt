package io.github.ponpokoo.mastodonclient.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute

internal fun NavController.openHashtag(hashtag: String, fromTrend: Boolean = false) {
    val current = currentBackStackEntry
        ?.takeIf { it.destination.hasRoute<Route.HashtagTimeline>() }
        ?.toRoute<Route.HashtagTimeline>()
    if (current?.hashtag.equals(hashtag, ignoreCase = true)) return

    // Different tags need separate entries: SingleTop would retain the previous tag's ViewModel.
    navigate(Route.HashtagTimeline(hashtag, fromTrend))
}
