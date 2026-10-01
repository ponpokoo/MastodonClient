package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconShape

internal fun AvatarIconShape.toShape(): Shape = when (this) {
    AvatarIconShape.Circle -> CircleShape
    AvatarIconShape.Square -> RectangleShape
}
