package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.compositionLocalOf
import io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities

internal val LocalServerCapabilities = compositionLocalOf { ServerCapabilities() }
