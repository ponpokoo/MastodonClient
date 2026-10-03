package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/** Shrinks the frame itself when the height limit is reached, keeping the whole image visible. */
internal fun fittedThumbnailSize(aspectRatio: Float?, maxWidth: Dp, maxHeight: Dp): DpSize {
    val ratio = thumbnailRatio(aspectRatio)
    val width = minOf(maxWidth, maxHeight * ratio)
    return DpSize(width, width / ratio)
}

/** Keeps the row at full post width, limiting each frame height for a centered crop. */
internal fun croppedThumbnailRowSizes(
    aspectRatios: List<Float?>,
    maxWidth: Dp,
    maxHeight: Dp,
    gap: Dp,
): List<DpSize> = fittedThumbnailRowSizes(aspectRatios, maxWidth, null, gap).map { frame ->
    DpSize(frame.width, minOf(frame.height, maxHeight))
}

/** Fits the row together; a null height limit uses all available width without cropping. */
internal fun fittedThumbnailRowSizes(
    aspectRatios: List<Float?>,
    maxWidth: Dp,
    maxHeight: Dp?,
    gap: Dp,
): List<DpSize> {
    if (aspectRatios.isEmpty()) return emptyList()
    val ratios = aspectRatios.map(::thumbnailRatio)
    val availableWidth = (maxWidth - gap * (ratios.size - 1)).coerceAtLeast(Dp(0f))
    val widthFittedHeight = availableWidth / ratios.sum()
    val height = maxHeight?.let { minOf(it, widthFittedHeight) } ?: widthFittedHeight
    return ratios.map { ratio -> DpSize(height * ratio, height) }
}

private fun thumbnailRatio(ratio: Float?): Float = ratio?.takeIf { it.isFinite() && it > 0f } ?: 1f
