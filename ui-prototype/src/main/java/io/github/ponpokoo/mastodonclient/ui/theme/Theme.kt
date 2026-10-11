package io.github.ponpokoo.mastodonclient.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode

// Muted blue accents over a slightly violet gray. Keep surface levels explicit
// so Material defaults do not introduce unrelated purple container colors.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB5C9E2),
    onPrimary = Color(0xFF203247),
    primaryContainer = Color(0xFF34465C),
    onPrimaryContainer = Color(0xFFD7E6F8),
    inversePrimary = Color(0xFF4C637D),
    secondary = Color(0xFFBAC5D2),
    onSecondary = Color(0xFF29333F),
    secondaryContainer = Color(0xFF343E4B),
    onSecondaryContainer = Color(0xFFDCE4EE),
    tertiary = Color(0xFFB0CFD4),
    onTertiary = Color(0xFF20363A),
    tertiaryContainer = Color(0xFF344C51),
    onTertiaryContainer = Color(0xFFD0EBEF),
    background = Color(0xFF1A1920),
    onBackground = Color(0xFFE7E6EC),
    surface = Color(0xFF1E1E24),
    surfaceDim = Color(0xFF1A1920),
    surfaceBright = Color(0xFF393840),
    surfaceContainerLowest = Color(0xFF18171D),
    surfaceContainerLow = Color(0xFF202027),
    surfaceContainer = Color(0xFF25252D),
    surfaceContainerHigh = Color(0xFF2B2B34),
    surfaceContainerHighest = Color(0xFF33333D),
    surfaceVariant = Color(0xFF33333D),
    onSurface = Color(0xFFE7E6EC),
    onSurfaceVariant = Color(0xFFBDBDC9),
    surfaceTint = Color(0xFFB5C9E2),
    outline = Color(0xFF8D8D9A),
    outlineVariant = Color(0xFF41414D),
    inverseSurface = Color(0xFFE7E6EC),
    inverseOnSurface = Color(0xFF303038),
)
private val LightColorScheme = lightColorScheme(
    primary = MastodonPurple,
    secondary = MastodonSecondary,
    secondaryContainer = Color(0xFFE5E4FF),
    onSecondaryContainer = Color(0xFF343467),
    background = Color(0xFFFCFCFF),
    surface = Color(0xFFFCFCFF),
    surfaceContainer = Color(0xFFF0F0F8),
    surfaceContainerLow = Color(0xFFF6F6FC),
    surfaceContainerHigh = Color(0xFFEAEAF4),
    surfaceVariant = Color(0xFFE8E8F2),
    onSurface = Color(0xFF20212C),
    onSurfaceVariant = Color(0xFF606173),
    primaryContainer = Color(0xFFE5E4FF),
    onPrimaryContainer = Color(0xFF343467),
    outlineVariant = Color(0xFFE0E1EB),
)

@Composable
fun MastodonClientTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
