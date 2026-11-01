package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.feature.media.mediaTransitionThumbnail
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.AudioPlayer
import io.github.ponpokoo.mastodonclient.feature.media.InlineVideo
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.feature.common.LinkActionsPopup
import io.github.ponpokoo.mastodonclient.feature.common.FilterWarning
import io.github.ponpokoo.mastodonclient.core.preferences.ThumbnailSize
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.domain.model.MediaDisplay
import io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings
import io.github.ponpokoo.mastodonclient.feature.timeline.croppedThumbnailRowSizes
import io.github.ponpokoo.mastodonclient.feature.timeline.fittedThumbnailRowSizes
import io.github.ponpokoo.mastodonclient.feature.timeline.fittedThumbnailSize
import androidx.compose.runtime.MutableState

internal class StatusMediaState(
    filterRevealed: MutableState<Boolean>,
    visibilityOverride: MutableState<Boolean?>,
) {
    var filterRevealed by filterRevealed
    var visibilityOverride by visibilityOverride
}

@Composable
internal fun rememberStatusMediaState(status: TimelineStatus): StatusMediaState {
    val reading = LocalReadingSettings.current
    val filterRevealed = rememberSaveable(status.filterScope, status.statusId, status.filterDisplay.mediaWarnings) {
        mutableStateOf(false)
    }
    val visibilityOverride = rememberSaveable(reading.accountKey, status.filterScope, status.statusId, status.sensitive) {
        mutableStateOf<Boolean?>(null)
    }
    return remember(filterRevealed, visibilityOverride) { StatusMediaState(filterRevealed, visibilityOverride) }
}

@Composable
internal fun StatusMediaSection(
    status: TimelineStatus,
    state: StatusMediaState,
    preferences: StatusDisplayPreferences,
    onMediaClick: ((List<MediaAttachment>, Int) -> Unit)?,
    useTimelineImagePreviews: Boolean,
) {
    if (status.mediaAttachments.isEmpty()) return
    val display = preferences.timelineDisplay
    val mediaPolicy = LocalReadingSettings.current.preferences.media
    val mediaRevealed = state.visibilityOverride ?: mediaPolicy.initiallyVisible(status.sensitive)
    Spacer(Modifier.height(4.dp))
    if (status.filterDisplay.mediaWarnings.isNotEmpty()) FilterWarning(
        status.filterDisplay.mediaWarnings, state.filterRevealed, { state.filterRevealed = !state.filterRevealed }, media = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = display.fontSize.spValue(),
            lineHeight = display.lineHeightSp().sp,
        ),
    )
    if (status.filterDisplay.mediaWarnings.isEmpty() || state.filterRevealed) {
        MediaGrid(
            status.mediaAttachments,
            onMediaClick,
            display.thumbnailSize,
            preferences.gifAutoplay,
            preferences.videoAutoplay,
            audioAuthorAvatarUrl = status.author.avatarUrl,
            useTimelineImagePreviews = useTimelineImagePreviews,
            onHideMedia = if (status.sensitive || mediaPolicy == MediaDisplay.HideAll) {
                { state.visibilityOverride = false }
            } else null,
            onRevealMedia = if (mediaRevealed) null else { { state.visibilityOverride = true } },
            revealMediaLabel = if (status.sensitive) "閲覧注意のメディアを表示" else "メディアを表示",
        )
    }
}

@Composable
private fun MediaGrid(
    attachments: List<MediaAttachment>,
    onMediaClick: ((List<MediaAttachment>, Int) -> Unit)?,
    thumbnailSize: ThumbnailSize = ThumbnailSize.Standard,
    gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
    audioAuthorAvatarUrl: String? = null,
    useTimelineImagePreviews: Boolean = false,
    onHideMedia: (() -> Unit)? = null,
    onRevealMedia: (() -> Unit)? = null,
    revealMediaLabel: String = "メディアを表示",
) {
    val mediaSourceGroup = io.github.ponpokoo.mastodonclient.feature.media.rememberMediaSourceGroup()
    val sessionKey = LocalReadingSettings.current.transientSessionKey
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbnailMaxHeight = when (thumbnailSize) {
        ThumbnailSize.Compact -> if (attachments.size == 1) 180.dp else 220.dp
        ThumbnailSize.Standard -> null
    }
    val displayedAttachments = attachments.take(4)
    val imageKeys = displayedAttachments.map { Triple(it.id, it.url, it.previewUrl) }
    var loadedAspectRatios by remember(imageKeys) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val rows = buildList {
        val visual = mutableListOf<MediaAttachment>()
        fun flush() { addAll(visual.chunked(2)); visual.clear() }
        displayedAttachments.forEach { media ->
            if (media.type == "audio") { flush(); add(listOf(media)) } else visual.add(media)
        }
        flush()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowMaxWidth = maxWidth
        val rowFrames = rows.map { rowItems ->
            if (rowItems.singleOrNull()?.type == "audio") {
                listOf(DpSize(rowMaxWidth, (rowMaxWidth / 1.67f).coerceAtLeast(220.dp).coerceAtMost(maxHeight)))
            } else {
                val aspectRatios = rowItems.map { loadedAspectRatios[it.id] ?: it.aspectRatio }
                if (thumbnailSize == ThumbnailSize.Standard && rowItems.all { it.type == "image" }) {
                    croppedThumbnailRowSizes(aspectRatios, rowMaxWidth, 400.dp, 8.dp)
                } else {
                    fittedThumbnailRowSizes(aspectRatios, rowMaxWidth, thumbnailMaxHeight, 8.dp)
                }
            }
        }
        if (onRevealMedia != null) {
            // Share the revealed layout without loading hidden images or starting players.
            val hasUnknownDimensions = displayedAttachments.any { media ->
                media.type != "audio" && (loadedAspectRatios[media.id] ?: media.aspectRatio)
                    ?.let { it.isFinite() && it > 0f } != true
            }
            // Keep the legacy placeholder until metadata or a revealed image supplies dimensions.
            val width = if (hasUnknownDimensions) rowMaxWidth else rowFrames.maxOf { frames ->
                frames.fold(0.dp) { total, frame -> total + frame.width } + 8.dp * (frames.size - 1)
            }
            val height = if (hasUnknownDimensions) 144.dp else
                rowFrames.fold(0.dp) { total, frames -> total + frames.maxOf { it.height } } +
                8.dp * (rowFrames.size - 1)
            Surface(
                modifier = Modifier.size(width, height)
                    .align(if (attachments.size == 1) Alignment.TopCenter else Alignment.TopStart)
                    .clickable(onClick = onRevealMedia),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(revealMediaLabel)
                }
            }
            return@BoxWithConstraints
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { rowIndex, rowItems ->
                if (rowItems.singleOrNull()?.type == "audio") {
                    val media = rowItems.single()
                    key(media.id, media.url) {
                        Box {
                            AudioPlayer(media, authorAvatarUrl = audioAuthorAvatarUrl ?: media.authorAvatarUrl)
                            if (rowIndex == 0 && onHideMedia != null) {
                                MediaHideButton(onHideMedia, Modifier.align(Alignment.TopEnd))
                            }
                        }
                    }
                } else {
                    val cropImageRow = thumbnailSize == ThumbnailSize.Standard && rowItems.all { it.type == "image" }
                    val frames = rowFrames[rowIndex]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (attachments.size == 1) Arrangement.Center else Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEachIndexed { index, media ->
                            val mediaIndex = attachments.indexOf(media)
                            val remainingCount = if (mediaIndex == 3) (attachments.size - 4).coerceAtLeast(0) else 0
                            val viewerIndex = if (remainingCount > 0) 4 else mediaIndex
                            val viewerTarget = attachments[viewerIndex]
                            key(sessionKey, media.id, media.url, media.previewUrl) {
                                var previewFailed by rememberSaveable { mutableStateOf(false) }
                                val imageUrl = if (media.type == "image") {
                                    if (useTimelineImagePreviews && !previewFailed) with(density) {
                                        media.timelineImageUrl(frames[index].width.toPx(), frames[index].height.toPx(), cropImageRow)
                                    } else media.url ?: media.previewUrl
                                } else {
                                    media.previewUrl ?: media.url
                                }
                                val usingPreview = media.type == "image" && useTimelineImagePreviews && media.url != null &&
                                    imageUrl != media.url && imageUrl == media.previewUrl
                                var imageFailed by rememberSaveable { mutableStateOf(false) }
                                var displayedPainter by remember(imageUrl) {
                                    mutableStateOf<androidx.compose.ui.graphics.painter.Painter?>(null)
                                }
                                androidx.compose.runtime.SideEffect {
                                    displayedPainter?.let { mediaSourceGroup.displayed(media, it) }
                                }
                                val onDimensionsKnown: (Int, Int) -> Unit = { width, height ->
                                    if (width > 0 && height > 0) {
                                        loadedAspectRatios = loadedAspectRatios + (media.id to width.toFloat() / height)
                                    }
                                }
                                Box(
                                    modifier = Modifier.size(frames[index])
                                        .mediaTransitionThumbnail(media, mediaSourceGroup, cropped = cropImageRow)
                                        .clip(RoundedCornerShape(8.dp))
                                        .testTag("media_attachment")
                                        .then(
                                            if (onMediaClick == null || viewerTarget.url == null) Modifier else {
                                                Modifier.clickable(onClickLabel = if (remainingCount > 0) "残り${remainingCount}枚を表示" else null) {
                                                    mediaSourceGroup.prepare(viewerTarget)
                                                    // Older cached posts have no avatar context on attachments.
                                                    val viewerMedia = attachments.map { item ->
                                                        if (item.type == "audio") item.copy(authorAvatarUrl = audioAuthorAvatarUrl ?: item.authorAvatarUrl)
                                                        else item
                                                    }
                                                    onMediaClick(viewerMedia, viewerIndex)
                                                }
                                            },
                                        )
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val autoplay = when (media.type) {
                                        "gifv" -> shouldAutoplay(context, gifAutoplay)
                                        "video" -> shouldAutoplay(context, videoAutoplay)
                                        else -> false
                                    }
                                    if (autoplay && media.url != null) {
                                        // Keep a preview available for the full-screen transition even
                                        // while the inline player owns the visible thumbnail surface.
                                        AsyncImage(model = media.previewUrl, contentDescription = null,
                                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                            onSuccess = { displayedPainter = it.painter })
                                        InlineVideo(media.url, loop = media.type == "gifv", onDimensionsKnown = onDimensionsKnown)
                                    } else {
                                        AsyncImage(
                                            model = imageUrl,
                                            contentDescription = media.description ?: "添付メディア",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = if (cropImageRow) ContentScale.Crop else ContentScale.Fit,
                                            onSuccess = {
                                                imageFailed = false
                                                // Preview rounding/cropping must not change the original thumbnail geometry.
                                                if (!usingPreview || media.aspectRatio == null) {
                                                    onDimensionsKnown(it.result.image.width, it.result.image.height)
                                                }
                                                displayedPainter = it.painter
                                            },
                                            onError = {
                                                if (media.type == "image" && usingPreview) {
                                                    previewFailed = true
                                                } else {
                                                    imageFailed = true
                                                }
                                            },
                                        )
                                    }
                                    if (!autoplay && imageFailed) {
                                        Text(
                                            if (media.type in setOf("video", "gifv")) "動画が取得できませんでした"
                                            else "画像が取得できませんでした",
                                            modifier = Modifier.padding(8.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                    if (!autoplay && !imageFailed && (media.type == "video" || media.type == "gifv")) {
                                        Icon(
                                            Icons.Outlined.PlayCircle,
                                            contentDescription = "動画",
                                            modifier = Modifier.size(48.dp),
                                            tint = androidx.compose.ui.graphics.Color.White,
                                        )
                                    }
                                    if (remainingCount > 0) {
                                        Box(
                                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("＋${remainingCount}枚", color = Color.White,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (rowIndex == 0 && index == rowItems.lastIndex && onHideMedia != null) {
                                        MediaHideButton(onHideMedia, Modifier.align(Alignment.TopEnd))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaHideButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.padding(4.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        contentPadding = PaddingValues(0.dp),
    ) {
        // Anchor the compact background at the top-right of the 48 dp touch target.
        Box(
            modifier = Modifier.heightIn(min = 48.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            // Let enlarged text grow rather than clipping it to the normal height.
            Box(
                modifier = Modifier.heightIn(min = 32.dp)
                    .background(Color(0xFF1E1E24).copy(alpha = .9f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("非表示")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun StatusLinkPreview(
    card: PreviewCard,
    thumbnailSize: ThumbnailSize = ThumbnailSize.Standard,
    onClick: () -> Unit,
) {
    var menuVisible by remember(card.url) { mutableStateOf(false) }
    val menuAnchor = remember { arrayOf(IntOffset.Zero) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("preview_card")
            .onGloballyPositioned {
                val origin = it.positionInWindow()
                menuAnchor[0] = IntOffset((origin.x + it.size.width / 2f).toInt(), (origin.y + it.size.height / 2f).toInt())
            }
            .combinedClickable(onClick = onClick, onLongClick = { menuVisible = true }),
        shape = RoundedCornerShape(6.dp),
        color = if (menuVisible) MaterialTheme.colorScheme.primary.copy(alpha = .18f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainer) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            card.imageUrl?.let { imageUrl ->
                var loadedAspectRatio by remember(imageUrl) { mutableStateOf<Float?>(null) }
                val maxWidth = when (thumbnailSize) {
                    ThumbnailSize.Compact -> 72.dp
                    ThumbnailSize.Standard -> 96.dp
                }
                val maxHeight = when (thumbnailSize) {
                    ThumbnailSize.Compact -> 68.dp
                    ThumbnailSize.Standard -> 88.dp
                }
                val frame = fittedThumbnailSize(loadedAspectRatio ?: card.aspectRatio, maxWidth, maxHeight)
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(frame).testTag("preview_card_image"),
                    contentScale = ContentScale.Fit,
                    onSuccess = {
                        val image = it.result.image
                        if (image.width > 0 && image.height > 0) {
                            loadedAspectRatio = image.width.toFloat() / image.height
                        }
                    },
                )
            }
            Column(Modifier.weight(1f).padding(start = if (card.imageUrl != null) 10.dp else 0.dp)) {
                if (card.byline.isNotBlank()) {
                    Text(card.byline, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    card.title.ifBlank { card.url },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.description.isNotBlank()) {
                    Text(
                        card.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (menuVisible) {
        LinkActionsPopup(
            url = card.url,
            positionInWindow = menuAnchor[0],
            onDismiss = { menuVisible = false },
            onOpen = { menuVisible = false; onClick() },
        )
    }
}

private fun shouldAutoplay(context: Context, policy: AutoplayPolicy): Boolean = when (policy) {
    AutoplayPolicy.Always -> true
    AutoplayPolicy.Never -> false
    AutoplayPolicy.WifiOnly -> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
