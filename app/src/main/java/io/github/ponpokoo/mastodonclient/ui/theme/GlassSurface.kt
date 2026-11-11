package io.github.ponpokoo.mastodonclient.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource

internal fun Modifier.glassBackdrop(state: HazeState?): Modifier =
    if (state == null) this else hazeSource(state)

@Composable
internal fun Modifier.glassSurface(
    state: HazeState?,
    shape: RoundedCornerShape,
    surfaceColor: Color,
): Modifier {
    if (state == null) return this
    val style = remember(shape, surfaceColor) {
        GlassStyle.regular.then {
            shape(shape)
            backgroundColor(surfaceColor)
            tint(surfaceColor.copy(alpha = 0.50f))
            optics {
                copy(
                    blurRadius = OpticalSizeValue.Fixed(10.dp),
                    refractionDisplacement = 16.dp,
                    refractionStrength = 0.65f,
                )
            }
            specularIntensity(0.50f)
            chromaticAberrationStrength(0f)
        }
    }
    return hazeGlass(input = HazeInput.Sources(state), style = style)
}
