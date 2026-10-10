package io.github.ponpokoo.transitionprototype

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Baseline: app/ui/theme/Theme.kt and Type.kt. Keep this copy independent of app.
private val PrototypeColors = darkColorScheme(
    primary = Color(0xFFB5C9E2), onPrimary = Color(0xFF203247),
    primaryContainer = Color(0xFF34465C), onPrimaryContainer = Color(0xFFD7E6F8),
    inversePrimary = Color(0xFF4C637D),
    secondary = Color(0xFFBAC5D2), onSecondary = Color(0xFF29333F),
    secondaryContainer = Color(0xFF343E4B), onSecondaryContainer = Color(0xFFDCE4EE),
    tertiary = Color(0xFFB0CFD4), onTertiary = Color(0xFF20363A),
    tertiaryContainer = Color(0xFF344C51), onTertiaryContainer = Color(0xFFD0EBEF),
    background = Color(0xFF1A1920), onBackground = Color(0xFFE7E6EC),
    surface = Color(0xFF1E1E24), onSurface = Color(0xFFE7E6EC),
    surfaceDim = Color(0xFF1A1920), surfaceBright = Color(0xFF393840),
    surfaceContainerLowest = Color(0xFF18171D), surfaceContainerLow = Color(0xFF202027),
    surfaceContainer = Color(0xFF25252D), surfaceContainerHigh = Color(0xFF2B2B34),
    surfaceContainerHighest = Color(0xFF33333D), surfaceVariant = Color(0xFF33333D),
    onSurfaceVariant = Color(0xFFBDBDC9), surfaceTint = Color(0xFFB5C9E2),
    outline = Color(0xFF8D8D9A), outlineVariant = Color(0xFF41414D),
    inverseSurface = Color(0xFFE7E6EC), inverseOnSurface = Color(0xFF303038),
)

@Composable
internal fun PrototypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PrototypeColors,
        typography = Typography(
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
