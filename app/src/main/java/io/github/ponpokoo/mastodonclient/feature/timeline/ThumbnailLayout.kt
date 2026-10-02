package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/** Shrinks the frame itself when the height limit is reached, keeping the whole image visible. */
internal fun fittedThumbnailSize(aspectRatio: Float?, maxWidth: Dp, maxHeight: Dp): DpSize {
    val ratio = thumbnailRatio(aspectRatio)
    val width = minOf(maxWidth, maxHeight * ratio)
    return DpSize(width, width / ratio)
}

/** Fits the entire row together so portrait images sit beside each other without empty cells. */
internal fun fittedThumbnailRowSizes(
    aspectRatios: List<Float?>,
    maxWidth: Dp,
    maxHeight: Dp,
    gap: Dp,
): List<DpSize> {
    if (aspectRatios.isEmpty()) return emptyList()
    val ratios = aspectRatios.map(::thumbnailRatio)
    val availableWidth = (maxWidth - gap * (ratios.size - 1)).coerceAtLeast(Dp(0f))
    val height = minOf(maxHeight, availableWidth / ratios.sum())
    return ratios.map { ratio -> DpSize(height * ratio, height) }
}

private fun thumbnailRatio(ratio: Float?): Float = ratio?.takeIf { it.isFinite() && it > 0f } ?: 1f
