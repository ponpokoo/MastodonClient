package io.github.ponpokoo.mastodonclient.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import kotlin.math.roundToInt

private const val ScreenEnterDurationMillis = 220
private const val ScreenPopDurationMillis = 240
private const val ScreenSlideFraction = 0.35f
private const val ScreenFadeStartFraction = 0.55f
private const val ScreenFadeEndFraction = 0.90f
private val ScreenPopFadeDelayMillis = (ScreenPopDurationMillis * ScreenFadeStartFraction).roundToInt()
private val ScreenPopFadeDurationMillis =
    (ScreenPopDurationMillis * ScreenFadeEndFraction).roundToInt() - ScreenPopFadeDelayMillis

@Composable
internal fun ScreenNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val graph = remember(navController, startDestination, builder) {
        // Named routes and serializable route objects use different createGraph overloads.
        if (startDestination is String) {
            navController.createGraph(startDestination = startDestination, builder = builder)
        } else {
            navController.createGraph(startDestination = startDestination, builder = builder)
        }
    }
    NavHost(
        navController = navController,
        graph = graph,
        modifier = modifier,
        enterTransition = { fadeIn(tween(ScreenEnterDurationMillis)) },
        exitTransition = { fadeOut(tween(ScreenEnterDurationMillis)) },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { screenPopExitTransition() },
        predictivePopEnterTransition = { EnterTransition.None },
        // Navigation seeks this same transition and restores it on gesture cancellation.
        predictivePopExitTransition = { screenPopExitTransition() },
    )
}

private fun screenPopExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(ScreenPopDurationMillis, easing = LinearEasing),
        targetOffsetX = { width -> (width * ScreenSlideFraction).roundToInt() },
    ) + fadeOut(tween(
        durationMillis = ScreenPopFadeDurationMillis,
        delayMillis = ScreenPopFadeDelayMillis,
        easing = LinearEasing,
    ))

internal inline fun <reified T : Any> NavGraphBuilder.animatedScreen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T>(content = content)
}

internal fun NavGraphBuilder.animatedScreen(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, content = content)
}
