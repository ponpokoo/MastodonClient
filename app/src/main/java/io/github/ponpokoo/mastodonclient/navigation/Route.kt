package io.github.ponpokoo.mastodonclient.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Login : Route

    @Serializable
    data object Timeline : Route
}
