package io.github.ponpokoo.mastodonclient.core.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class FabPosition(val xFraction: Float = 1f, val yFraction: Float = 1f) {
    fun normalized(): FabPosition? = if (xFraction.isFinite() && yFraction.isFinite()) {
        FabPosition(xFraction.coerceIn(0f, 1f), yFraction.coerceIn(0f, 1f))
    } else null
}

interface FabPositionStore {
    val fabPosition: Flow<FabPosition?>
    suspend fun setFabPosition(position: FabPosition)
    suspend fun resetFabPosition()
}
