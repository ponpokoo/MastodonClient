package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LoadingContent(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label)
        }
    }
}

@Composable
internal fun ListLoadingContent(modifier: Modifier = Modifier, label: String? = "読み込み中…") {
    Box(modifier.fillMaxSize().testTag("list_initial_loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            if (label != null) {
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ListLoadingMoreIndicator(label: String? = null) {
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp).testTag("list_loading_more"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            if (label != null) {
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MessageContent(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TimelineNotice(message: String, onClick: (() -> Unit)? = null,
    neutral: Boolean = false, buttonAppearance: Boolean = onClick != null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val offset by animateDpAsState(if (pressed && onClick != null) 1.dp else 0.dp, tween(80))
    val background by animateColorAsState(when {
        neutral -> Color(0xFF4A4A50)
        pressed && onClick != null -> Color(0xFF42536A)
        else -> Color(0xFF34465C)
    }, tween(80))
    val shape = RoundedCornerShape(18.dp)
    val offsetPx = with(LocalDensity.current) { offset.toPx() }
    Box(
        Modifier.then(if (onClick != null) Modifier.clickable(interactionSource = interaction,
            indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = if (buttonAppearance) 48.dp else 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.graphicsLayer { translationY = offsetPx },
            shape = shape, color = background,
            border = if (buttonAppearance) BorderStroke(1.dp, Color(0xFFF5F7FC).copy(alpha = 0.2f)) else null,
            shadowElevation = if (buttonAppearance && !pressed) 1.dp else 0.dp,
        ) {
            Box(
                Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(message, style = MaterialTheme.typography.labelLarge,
                    color = if (neutral) Color(0xFFF5F5F7) else Color(0xFFF5F7FC))
            }
        }
    }
}
