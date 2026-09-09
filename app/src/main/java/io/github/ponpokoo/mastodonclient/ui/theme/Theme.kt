package io.github.ponpokoo.mastodonclient.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MastodonPurpleDark,
    secondary = MastodonSecondaryDark,
    secondaryContainer = Color(0xFF343467),
    onSecondaryContainer = Color(0xFFE5E4FF),
    background = Color(0xFF111217),
    surface = Color(0xFF111217),
    surfaceContainer = Color(0xFF1D1E26),
    surfaceContainerLow = Color(0xFF17181F),
    surfaceContainerHigh = Color(0xFF272832),
    surfaceVariant = Color(0xFF30313D),
    onSurface = Color(0xFFE9E9F2),
    onSurfaceVariant = Color(0xFFB5B6C7),
    primaryContainer = Color(0xFF343467),
    onPrimaryContainer = Color(0xFFE5E4FF),
    outlineVariant = Color(0xFF333440),
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

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
