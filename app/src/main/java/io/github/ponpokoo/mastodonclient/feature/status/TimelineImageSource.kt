package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import kotlin.math.abs

/** Select a preview only when it can be drawn without upscaling at the actual display density. */
internal fun MediaAttachment.timelineImageUrl(widthPx: Float, heightPx: Float, cropped: Boolean): String? {
    if (type != "image") return previewUrl ?: url
    val original = url ?: return previewUrl
    val preview = previewUrl?.takeIf(String::isNotBlank) ?: return original
    val width = previewWidth?.takeIf { it > 0 } ?: return original
    val height = previewHeight?.takeIf { it > 0 } ?: return original
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return original
    if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0 || heightPx <= 0) return original
    // Allow integer rounding of preview dimensions, but reject differently cropped previews.
    if (abs(width - height * ratio) > maxOf(1f, ratio)) return original
    val scale = if (cropped) maxOf(widthPx / width, heightPx / height)
        else minOf(widthPx / width, heightPx / height)
    return if (scale <= 1f) preview else original
}
