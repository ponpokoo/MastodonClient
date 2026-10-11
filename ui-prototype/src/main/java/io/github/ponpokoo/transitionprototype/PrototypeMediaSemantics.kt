package io.github.ponpokoo.transitionprototype

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.geometry.Offset

internal val PrototypeViewerPage = SemanticsPropertyKey<Int>("PrototypeViewerPage")
internal val PrototypeViewerScale = SemanticsPropertyKey<Float>("PrototypeViewerScale")
internal val PrototypeViewerOffset = SemanticsPropertyKey<Offset>("PrototypeViewerOffset")
internal val PrototypeViewerDismissOffset = SemanticsPropertyKey<Float>("PrototypeViewerDismissOffset")
