package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.runtime.saveable.listSaver

internal data class MainTabHistory(
    val sessionId: String? = null,
    private val visits: List<MainDestination> = listOf(MainDestination.Home),
) {
    val selected: MainDestination get() = visits.last()
    val canGoBack: Boolean get() = visits.size > 1

    fun forSession(sessionId: String): MainTabHistory =
        if (this.sessionId == sessionId) this else MainTabHistory(sessionId)

    fun select(tab: MainDestination): MainTabHistory =
        if (selected == tab) this else copy(visits = visits + tab)

    fun goBack(): MainTabHistory =
        if (canGoBack) copy(visits = visits.dropLast(1)) else this

    companion object {
        fun fromNotification(sessionId: String) =
            MainTabHistory(sessionId, listOf(MainDestination.Notifications))

        val Saver = listSaver<MainTabHistory, String>(
            save = { listOf(it.sessionId.orEmpty()) + it.visits.map(MainDestination::name) },
            restore = { saved ->
                val visits = saved.drop(1).map { name ->
                    MainDestination.entries.firstOrNull { it.name == name } ?: return@listSaver null
                }
                if (visits.isEmpty()) null
                else MainTabHistory(saved.first().ifEmpty { null }, visits)
            },
        )
    }
}
