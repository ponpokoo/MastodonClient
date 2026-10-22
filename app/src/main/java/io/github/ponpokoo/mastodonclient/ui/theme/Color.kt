package io.github.ponpokoo.mastodonclient.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ColorScheme

val MastodonPurple = Color(0xFF6364FF)
val MastodonPurpleDark = Color(0xFFB9C3FF)
val MastodonSecondary = Color(0xFF5B5D72)
val MastodonSecondaryDark = Color(0xFFC4C5DD)

// Follow the app's selected theme, including when it differs from the system theme.
internal val ColorScheme.reactionSelectedOutline: Color
    get() = if (surface.luminance() < 0.5f) Color(0xFF9FB9D8) else Color(0xFF7065BF)

internal val ColorScheme.onReactionSelectedContainer: Color
    get() = if (surface.luminance() < 0.5f) onPrimaryContainer else Color(0xFF4C4290)

internal val ColorScheme.composeSubmitContainer: Color
    get() = if (surface.luminance() < 0.5f) Color(0xFF516D8D) else Color(0xFFC3C5FA)

internal val ColorScheme.onComposeSubmitContainer: Color
    get() = if (surface.luminance() < 0.5f) Color(0xFFEEF4FF) else Color(0xFF2E2D66)

internal val ColorScheme.composeSubmitPressedContainer: Color
    get() = if (surface.luminance() < 0.5f) Color(0xFF2A405B) else Color(0xFFAAAFE8)
