package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconShape

internal fun AvatarIconShape.toShape(): Shape = when (this) {
    AvatarIconShape.Circle -> CircleShape
    AvatarIconShape.Square -> RoundedCornerShape(10.dp)
}
