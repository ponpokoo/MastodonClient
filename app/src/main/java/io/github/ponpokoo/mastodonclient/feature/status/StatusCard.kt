package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.feature.media.mediaTransitionThumbnail

import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.AudioPlayer
import io.github.ponpokoo.mastodonclient.feature.media.InlineVideo
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.mentionedAccountIdFor
import io.github.ponpokoo.mastodonclient.domain.model.replyToAccountName
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.StatusPoll
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.LinkActionsPopup
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.rememberEmojiImages
import io.github.ponpokoo.mastodonclient.feature.common.ContentWarning
import io.github.ponpokoo.mastodonclient.feature.common.FilterWarning
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.FontSizePreset
import io.github.ponpokoo.mastodonclient.core.preferences.LineSpacingPreset
import io.github.ponpokoo.mastodonclient.core.preferences.ActionIconSize
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconSize
import io.github.ponpokoo.mastodonclient.feature.common.toShape
import io.github.ponpokoo.mastodonclient.core.preferences.ThumbnailSize
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.domain.model.MediaDisplay
import io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings
import java.util.Locale

import io.github.ponpokoo.mastodonclient.feature.timeline.relativeTime
import io.github.ponpokoo.mastodonclient.feature.timeline.ReactionPickerSheet
import io.github.ponpokoo.mastodonclient.feature.timeline.ReactionPickerSheetMotion
import io.github.ponpokoo.mastodonclient.feature.timeline.croppedThumbnailRowSizes
import io.github.ponpokoo.mastodonclient.feature.timeline.fittedThumbnailRowSizes
import io.github.ponpokoo.mastodonclient.feature.timeline.fittedThumbnailSize
import io.github.ponpokoo.mastodonclient.ui.theme.reactionSelectedOutline
import io.github.ponpokoo.mastodonclient.ui.theme.onReactionSelectedContainer
import io.github.ponpokoo.mastodonclient.ui.theme.FavouriteIconColor

internal val LocalReactionListOpener = staticCompositionLocalOf<((String, EmojiReaction) -> Unit)?> { null }
internal val LocalFavouriteListOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun StatusCard(
    status: TimelineStatus,
    onStatusClick: ((String) -> Unit)?,
    onAuthorClick: ((String) -> Unit)? = null,
    onMediaClick: ((List<MediaAttachment>, Int) -> Unit)? = null,
    onOpenLink: (String) -> Unit = {},
    onReply: () -> Unit = {},
    onBoost: () -> Unit = {},
    onQuote: ((QuoteMode) -> Unit)? = null,
    onFavourite: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onReact: ((String?) -> Unit)? = null,
    onReactionLongPress: ((EmojiReaction) -> Unit)? = null,
    onMoreClick: ((TimelineStatus) -> Unit)? = null,
    onUnavailableAction: (String) -> Unit,
    displayPreferences: TimelineDisplayPreferences = TimelineDisplayPreferences(),
    gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
    fullWidthContent: Boolean = false,
    onVotePoll: ((Set<Int>) -> Unit)? = null,
    afterActions: (@Composable () -> Unit)? = null,
    authorAvatarResource: Int? = null,
    useTimelineImagePreviews: Boolean = false,
    collapseLongContent: Boolean = true,
    reactionPickerMotion: ReactionPickerSheetMotion = ReactionPickerSheetMotion.HeaderOnly,
) {
    if (status.filterDisplay.hidden) return
    var filterRevealed by rememberSaveable(status.filterScope, status.statusId, status.filterDisplay.warnings) { mutableStateOf(false) }
    var filterMediaRevealed by rememberSaveable(status.filterScope, status.statusId, status.filterDisplay.mediaWarnings) { mutableStateOf(false) }
    val filterContentVisible = status.filterDisplay.warnings.isEmpty() || filterRevealed
    val reading = LocalReadingSettings.current
    // An untouched card follows local preferences; a manual choice survives setting changes.
    var contentOverride by rememberSaveable(reading.accountKey, status.filterScope, status.statusId, status.spoilerText) { mutableStateOf<Boolean?>(null) }
    val contentExpanded = contentOverride ?: (status.spoilerText.isBlank() || reading.preferences.expandSpoilers)
    var mediaOverride by rememberSaveable(reading.accountKey, status.filterScope, status.statusId, status.sensitive) { mutableStateOf<Boolean?>(null) }
    val mediaPolicy = reading.preferences.media
    val mediaRevealed = mediaOverride ?: mediaPolicy.initiallyVisible(status.sensitive)
    var reactionPickerOpen by rememberSaveable(status.filterScope, status.statusId) { mutableStateOf(false) }
    val reactionListOpener = LocalReactionListOpener.current
    val serverCapabilities = io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities.current
    androidx.compose.runtime.LaunchedEffect(serverCapabilities.supportsReactionActions) {
        if (!serverCapabilities.supportsReactionActions) reactionPickerOpen = false
    }
    val favouriteListOpener = LocalFavouriteListOpener.current
    val context = LocalContext.current
    val avatarSize = when (displayPreferences.avatarIconSize) {
        AvatarIconSize.Small -> 40.dp
        AvatarIconSize.Standard -> 44.dp
        AvatarIconSize.Large -> 56.dp
    }
    val contentStart = avatarSize + 8.dp
    val headerEdgeShift = if (onMoreClick == null) 0.dp else 16.dp

    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (onStatusClick == null || !filterContentVisible) Modifier else Modifier.clickable { onStatusClick(status.statusId) })
            .padding(start = 8.dp, top = 12.dp, end = 16.dp, bottom = 2.dp)
            .testTag("timeline_status"),
    ) {
        status.boostedBy?.let {
            Row(
                modifier = Modifier
                    .padding(start = contentStart, bottom = 6.dp)
                    .then(if (onAuthorClick == null) Modifier else Modifier.clickable { onAuthorClick(it.id) })
                    .testTag("status_booster"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                CustomEmojiText(
                    text = "${it.displayName}さんがブーストしました",
                    emojis = it.customEmojis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = authorAvatarResource ?: io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel(status.author.avatarUrl),
                contentDescription = "${status.author.displayName}のプロフィール画像",
                modifier = Modifier.size(avatarSize).clip(displayPreferences.avatarIconShape.toShape())
                    .testTag("status_author_avatar")
                    .then(
                        if (onAuthorClick == null) Modifier else Modifier.clickable {
                            onAuthorClick(status.author.id)
                        },
                    )
                    .background(MaterialTheme.colorScheme.surface),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                CustomEmojiText(
                    text = status.author.displayName,
                    emojis = status.author.customEmojis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "@${status.author.accountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.height(48.dp).offset(x = headerEdgeShift),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (visibilityIcon, visibilityLabel) = statusVisibility(status.visibility)
                Icon(
                    visibilityIcon,
                    contentDescription = visibilityLabel,
                    modifier = Modifier.size(14.4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    relativeTime(status.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (onMoreClick != null) {
                IconButton(
                    onClick = { onMoreClick(status) },
                    modifier = Modifier.size(48.dp).offset(x = headerEdgeShift),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "投稿メニュー",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(start = if (fullWidthContent) 8.dp else contentStart)) {
            if (status.filterDisplay.warnings.isNotEmpty()) FilterWarning(
                status.filterDisplay.warnings, filterRevealed, { filterRevealed = !filterRevealed },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = displayPreferences.fontSize.spValue(),
                    lineHeight = displayPreferences.lineHeightSp().sp,
                ),
            )
            if (!filterContentVisible) return@Column
            status.replyToAccountName()?.let { accountName ->
                Text(
                    text = "返信先: @$accountName",
                    modifier = Modifier.padding(top = 6.dp)
                        .then(
                            if (onAuthorClick == null || status.inReplyToAccountId == null) Modifier
                            else Modifier.clickable { onAuthorClick(status.inReplyToAccountId) },
                        )
                        .testTag("status_reply_target"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.spoilerText.isNotBlank()) {
                ContentWarning(
                    expanded = contentExpanded,
                    onToggle = { contentOverride = !contentExpanded },
                ) {
                    CustomEmojiText(
                        text = status.spoilerText,
                        emojis = status.customEmojis,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = displayPreferences.fontSize.spValue(),
                            lineHeight = displayPreferences.lineHeightSp().sp,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (contentExpanded && status.contentHtml.isNotBlank()) {
                val body: @Composable (Int, ((TextLayoutResult) -> Unit)?) -> Unit = { maxLines, onLayout ->
                    StatusContentText(
                        contentHtml = status.contentHtml,
                        customEmojis = status.customEmojis,
                        modifier = Modifier.padding(top = if (status.spoilerText.isBlank()) 4.dp else 0.dp),
                        emojiHeight = 24.dp,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = displayPreferences.fontSize.spValue(),
                            lineHeight = displayPreferences.lineHeightSp().sp,
                        ),
                        onLinkClick = { link ->
                            val accountId = status.mentionedAccountIdFor(link)
                            if (accountId != null && onAuthorClick != null) onAuthorClick(accountId)
                            else onOpenLink(link)
                        },
                        onNonLinkClick = onStatusClick?.let { { it(status.statusId) } },
                        maxLines = maxLines,
                        onTextLayout = onLayout,
                    )
                }
                if (collapseLongContent && status.spoilerText.isBlank()) {
                    key(reading.transientSessionKey, status.filterScope, status.timelineId, status.statusId, status.contentHtml) {
                        CollapsibleStatusBody(
                            accountKey = reading.transientSessionKey,
                            filterScope = status.filterScope,
                            timelineId = status.timelineId,
                            statusId = status.statusId,
                            contentHtml = status.contentHtml,
                        ) { maxLines, onLayout -> body(maxLines, onLayout) }
                    }
                } else body(Int.MAX_VALUE, null)
            }
            if (contentExpanded) {
                status.poll?.let { poll ->
                    Spacer(Modifier.height(8.dp))
                    PollCard(poll, onVote = onVotePoll)
                }
            }
            if (status.mediaAttachments.isNotEmpty() && contentExpanded) {
                Spacer(Modifier.height(8.dp))
                if (status.filterDisplay.mediaWarnings.isNotEmpty()) FilterWarning(
                    status.filterDisplay.mediaWarnings, filterMediaRevealed, { filterMediaRevealed = !filterMediaRevealed }, media = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = displayPreferences.fontSize.spValue(),
                        lineHeight = displayPreferences.lineHeightSp().sp,
                    ),
                )
                if (mediaRevealed && (status.filterDisplay.mediaWarnings.isEmpty() || filterMediaRevealed)) {
                    if (status.sensitive || mediaPolicy == MediaDisplay.HideAll) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { mediaOverride = false }) {
                                Text(if (status.sensitive) "閲覧注意に戻す" else "メディアを隠す")
                            }
                        }
                    }
                    MediaGrid(
                        status.mediaAttachments,
                        onMediaClick,
                        displayPreferences.thumbnailSize,
                        gifAutoplay,
                        videoAutoplay,
                        audioAuthorAvatarUrl = status.author.avatarUrl,
                        useTimelineImagePreviews = useTimelineImagePreviews,
                    )
                } else if (status.filterDisplay.mediaWarnings.isEmpty() || filterMediaRevealed) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(144.dp)
                            .clickable { mediaOverride = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (status.sensitive) "閲覧注意のメディアを表示" else "メディアを表示")
                        }
                    }
                }
            }
            if (contentExpanded) {
                status.previewCard?.let { card ->
                    Spacer(Modifier.height(8.dp))
                    PreviewCardView(
                        card = card,
                        thumbnailSize = displayPreferences.thumbnailSize,
                        onClick = { onOpenLink(card.url) },
                    )
                }
            }
            if (displayPreferences.showReactions && status.reactions.isNotEmpty()) {
                val imageUrls = remember(status.reactions) {
                    status.reactions.mapNotNull { it.imageUrl }.distinct()
                }
                val reactionImages = rememberEmojiImages(imageUrls, status.reactions)
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val availableWidth = maxWidth
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        status.reactions.forEach { reaction ->
                            val maxImageWidth = (availableWidth - (22 + reaction.count.toString().length * 10).dp)
                                .coerceAtLeast(48.dp)
                            Surface(
                                modifier = Modifier.testTag("displayed_reaction").then(
                                    if (!serverCapabilities.supportsReactionActions || onReact == null && onReactionLongPress == null && reactionListOpener == null) Modifier
                                    else Modifier.combinedClickable(
                                        onClick = {
                                            onReact?.invoke(if (reaction.reactedByMe) null else reaction.apiName)
                                        },
                                        onLongClick = {
                                            if (onReactionLongPress != null) onReactionLongPress(reaction)
                                            else reactionListOpener?.invoke(status.statusId, reaction)
                                        },
                                    ),
                                ),
                                shape = RoundedCornerShape(16.dp),
                                color = if (reaction.reactedByMe) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (reaction.reactedByMe) {
                                    MaterialTheme.colorScheme.onReactionSelectedContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                border = if (reaction.reactedByMe) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.reactionSelectedOutline)
                                } else null,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (reaction.imageUrl != null) {
                                        val imageRatio = reactionImages.ratios.getValue(reaction.imageUrl)
                                        val tall = imageRatio < 0.65f
                                        Image(
                                            painter = reactionImages.painters.getValue(reaction.imageUrl),
                                            contentDescription = reaction.name,
                                            modifier = Modifier.width((24f * imageRatio).dp.coerceIn(8.dp, maxImageWidth))
                                                .height(24.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                        if (tall) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(reaction.name, modifier = Modifier.widthIn(max = 80.dp),
                                                maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        Text(reaction.name)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(reaction.count.toString(), style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (reaction.reactedByMe) FontWeight.Bold
                                            else MaterialTheme.typography.labelMedium.fontWeight)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            StatusActionRow(
                status = status,
                onReply = onReply,
                onBoost = onBoost,
                onQuote = onQuote,
                onFavourite = onFavourite,
                onFavouriteLongClick = favouriteListOpener?.takeIf { status.favouritesCount > 0 }?.let { opener ->
                    { opener(status.statusId) }
                },
                onReaction = onReact?.let { { reactionPickerOpen = true } },
                onShare = {
                    val url = status.url
                    if (url == null) {
                        onUnavailableAction("共有")
                    } else {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                "投稿を共有",
                            ),
                        )
                    }
                },
                onBookmark = onBookmark,
                preferences = displayPreferences,
            )
            afterActions?.let { content ->
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    if (reactionPickerOpen && serverCapabilities.supportsReactionActions) {
        ReactionPickerSheet(
            canUndo = status.reactions.any { it.reactedByMe },
            motion = reactionPickerMotion,
            onDismiss = { reactionPickerOpen = false },
            onSelected = { emoji ->
                reactionPickerOpen = false
                onReact?.invoke(emoji)
            },
        )
    }
}

@Composable
private fun PollCard(poll: StatusPoll, onVote: ((Set<Int>) -> Unit)?) {
    val totalVotes = poll.votesCount.coerceAtLeast(0)
    var selectedChoices by rememberSaveable(poll.id) { mutableStateOf(poll.ownVotes) }
    val canVote = !poll.expired && poll.voted != true && onVote != null
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("status_poll"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "アンケート",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            poll.options.forEachIndexed { index, option ->
                val optionVotes = option.votesCount
                val fraction = optionVotes?.takeIf { totalVotes > 0 }
                    ?.let { (it.toFloat() / totalVotes).coerceIn(0f, 1f) } ?: 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = if (canVote) Modifier.fillMaxWidth().clickable {
                            selectedChoices = if (poll.multiple) {
                                selectedChoices.toMutableSet().apply {
                                    if (!add(index)) remove(index)
                                }
                            } else setOf(index)
                        } else Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(18.dp).clip(CircleShape)
                                .background(
                                    if (index in selectedChoices) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                )
                                .padding(4.dp),
                        ) {
                            if (index in selectedChoices) {
                                Box(
                                    Modifier.fillMaxSize().clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onPrimary),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(option.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        optionVotes?.let { votes ->
                            Text(
                                "${(fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction).fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            if (canVote) {
                Button(
                    onClick = { onVote?.invoke(selectedChoices) },
                    enabled = selectedChoices.isNotEmpty(),
                    modifier = Modifier.align(Alignment.End),
                ) { Text("投票する") }
            }
            val voteLabel = poll.votersCount?.let { "${it}人が投票" } ?: "${totalVotes}票"
            Text(
                "${if (poll.expired) "投票終了" else "投票受付中"}・$voteLabel${if (poll.multiple) "・複数選択可" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
) {
    val mediaSourceGroup = io.github.ponpokoo.mastodonclient.feature.media.rememberMediaSourceGroup()
    val context = LocalContext.current
    val density = LocalDensity.current
    val maxHeight = when (thumbnailSize) {
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { rowItems ->
                if (rowItems.singleOrNull()?.type == "audio") {
                    val media = rowItems.single()
                    key(media.id, media.url) {
                        AudioPlayer(media, authorAvatarUrl = audioAuthorAvatarUrl ?: media.authorAvatarUrl)
                    }
                } else {
                    val cropImageRow = thumbnailSize == ThumbnailSize.Standard && rowItems.all { it.type == "image" }
                    val aspectRatios = rowItems.map { loadedAspectRatios[it.id] ?: it.aspectRatio }
                    val frames = if (cropImageRow) {
                        croppedThumbnailRowSizes(aspectRatios, rowMaxWidth, 400.dp, 8.dp)
                    } else {
                        fittedThumbnailRowSizes(
                            aspectRatios = aspectRatios,
                            maxWidth = rowMaxWidth,
                            maxHeight = maxHeight,
                            gap = 8.dp,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (attachments.size == 1) Arrangement.Center else Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEachIndexed { index, media ->
                            key(media.id, media.url, media.previewUrl) {
                                var previewFailed by remember(media.url, media.previewUrl) { mutableStateOf(false) }
                                val imageUrl = if (media.type == "image") {
                                    if (useTimelineImagePreviews && !previewFailed) with(density) {
                                        media.timelineImageUrl(frames[index].width.toPx(), frames[index].height.toPx(), cropImageRow)
                                    } else media.url ?: media.previewUrl
                                } else {
                                    media.previewUrl ?: media.url
                                }
                                val usingPreview = media.type == "image" && useTimelineImagePreviews && media.url != null &&
                                    imageUrl != media.url && imageUrl == media.previewUrl
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
                                            if (onMediaClick == null || media.url == null) Modifier else {
                                                Modifier.clickable {
                                                    mediaSourceGroup.prepare(media)
                                                    // Older cached posts have no avatar context on attachments.
                                                    val viewerMedia = attachments.map { item ->
                                                        if (item.type == "audio") item.copy(authorAvatarUrl = audioAuthorAvatarUrl ?: item.authorAvatarUrl)
                                                        else item
                                                    }
                                                    onMediaClick(viewerMedia, attachments.indexOf(media))
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
                                                // Preview rounding/cropping must not change the original thumbnail geometry.
                                                if (!usingPreview || media.aspectRatio == null) {
                                                    onDimensionsKnown(it.result.image.width, it.result.image.height)
                                                }
                                                displayedPainter = it.painter
                                            },
                                            onError = {
                                                if (media.type == "image" && usingPreview) {
                                                    previewFailed = true
                                                }
                                            },
                                        )
                                    }
                                    if (!autoplay && (media.type == "video" || media.type == "gifv")) {
                                        Icon(
                                            Icons.Outlined.PlayCircle,
                                            contentDescription = "動画",
                                            modifier = Modifier.size(48.dp),
                                            tint = androidx.compose.ui.graphics.Color.White,
                                        )
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
@OptIn(ExperimentalFoundationApi::class)
private fun PreviewCardView(
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

@Composable
private fun StatusActionRow(
    status: TimelineStatus,
    onReply: () -> Unit,
    onBoost: () -> Unit,
    onQuote: ((QuoteMode) -> Unit)?,
    onFavourite: () -> Unit,
    onFavouriteLongClick: (() -> Unit)? = null,
    onReaction: (() -> Unit)?,
    onShare: () -> Unit,
    onBookmark: () -> Unit = {},
    preferences: TimelineDisplayPreferences = TimelineDisplayPreferences(),
) {
    var boostMenuExpanded by remember(status.statusId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        preferences.actionOrder.filterNot { it in preferences.hiddenActions }.forEach { action ->
            when (action) {
                StatusAction.Reply -> StatusActionButton(Icons.AutoMirrored.Filled.Reply, "返信", status.repliesCount, preferences, onReply)
                StatusAction.Boost -> {
                    val canBoost = status.visibility.lowercase() !in setOf("private", "direct", "followers", "followers_only")
                    Box {
                        StatusActionButton(
                            icon = if (status.reblogged) BoldRepeatIcon else Icons.Outlined.Repeat,
                            label = if (canBoost) "ブースト（長押しで引用を選択）" else "この公開範囲ではブーストできません",
                            count = status.boostsCount,
                            preferences = preferences,
                            onClick = onBoost,
                            onLongClick = onQuote?.let { { boostMenuExpanded = true } },
                            enabled = canBoost,
                            crossedOut = !canBoost,
                            activeTint = if (status.reblogged) MaterialTheme.colorScheme.primary else null,
                        )
                        DropdownMenu(expanded = boostMenuExpanded, onDismissRequest = { boostMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text(if (status.reblogged) "ブースト解除" else "ブースト") }, onClick = {
                                boostMenuExpanded = false
                                onBoost()
                            })
                            if (status.quoteApproval != null) {
                                DropdownMenuItem(
                                    text = { Text(if (status.quoteApproval == "manual") "引用（承認申請）" else "引用") },
                                    enabled = onQuote != null && status.quoteApproval in setOf("automatic", "manual"),
                                    onClick = {
                                        boostMenuExpanded = false
                                        onQuote?.invoke(QuoteMode.Native)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("引用（リンク）") },
                                enabled = onQuote != null && status.url != null,
                                onClick = {
                                    boostMenuExpanded = false
                                    onQuote?.invoke(QuoteMode.Link)
                                },
                            )
                        }
                    }
                }
                StatusAction.Favourite -> StatusActionButton(
                    if (status.favourited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (onFavouriteLongClick == null) "お気に入り" else "お気に入り（長押しで一覧）",
                    status.favouritesCount,
                    preferences,
                    onFavourite,
                    onLongClick = onFavouriteLongClick,
                    activeTint = if (status.favourited) FavouriteIconColor else null,
                )
                StatusAction.Reaction -> if (onReaction != null && io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities.current.supportsReactionActions) {
                    StatusActionButton(Icons.Outlined.SentimentSatisfiedAlt, "リアクション", null, preferences, onReaction)
                }
                StatusAction.Share -> StatusActionButton(Icons.Outlined.Share, "共有", null, preferences, onShare)
                StatusAction.Bookmark -> StatusActionButton(
                    if (status.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    "ブックマーク", null, preferences, onBookmark,
                )
            }
        }
    }
}

private val BoldRepeatIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BoldRepeat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 5f)
            horizontalLineTo(16.5f)
            verticalLineTo(2.25f)
            lineTo(21.75f, 6.75f)
            lineTo(16.5f, 11.25f)
            verticalLineTo(8.25f)
            horizontalLineTo(7f)
            curveTo(5.9f, 8.25f, 5.25f, 8.95f, 5.25f, 10f)
            verticalLineTo(12.5f)
            horizontalLineTo(2.25f)
            verticalLineTo(10f)
            curveTo(2.25f, 7.2f, 4.45f, 5f, 7f, 5f)
            close()

            moveTo(17f, 19f)
            horizontalLineTo(7.5f)
            verticalLineTo(21.75f)
            lineTo(2.25f, 17.25f)
            lineTo(7.5f, 12.75f)
            verticalLineTo(15.75f)
            horizontalLineTo(17f)
            curveTo(18.1f, 15.75f, 18.75f, 15.05f, 18.75f, 14f)
            verticalLineTo(11.5f)
            horizontalLineTo(21.75f)
            verticalLineTo(14f)
            curveTo(21.75f, 16.8f, 19.55f, 19f, 17f, 19f)
            close()
        }
    }.build()
}

@Composable
private fun StatusActionButton(
    icon: ImageVector,
    label: String,
    count: Long?,
    preferences: TimelineDisplayPreferences,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    crossedOut: Boolean = false,
    activeTint: Color? = null,
) {
    val iconSize = when (preferences.actionIconSize) {
        ActionIconSize.Small -> 18.dp
        ActionIconSize.Standard -> 21.dp
        ActionIconSize.Large -> 24.dp
    }
    val displayedCount = count?.takeIf { preferences.showCounts && it > 0 }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    @OptIn(ExperimentalFoundationApi::class)
    Row(
        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable fun ButtonIcon() {
            val tint = if (enabled && activeTint != null) activeTint
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.68f else 0.35f)
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.matchParentSize(), tint = tint)
                if (crossedOut) {
                    Canvas(Modifier.matchParentSize()) {
                        drawLine(
                            color = tint,
                            start = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.1f),
                            end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier.size(iconSize + 4.dp).background(
                color = if (enabled && pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent,
                shape = CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            ButtonIcon()
        }
        if (displayedCount != null) {
            Spacer(Modifier.width(2.dp))
            Text(
                compactCount(displayedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

internal fun FontSizePreset.spValue() = when (this) {
    FontSizePreset.Small -> 14.sp
    FontSizePreset.Standard -> 16.sp
    FontSizePreset.Large -> 18.sp
    FontSizePreset.ExtraLarge -> 20.sp
}

internal fun TimelineDisplayPreferences.lineHeightSp(): Int {
    val base = when (fontSize) {
        FontSizePreset.Small -> 18
        FontSizePreset.Standard -> 22
        FontSizePreset.Large -> 26
        FontSizePreset.ExtraLarge -> 30
    }
    return when (lineSpacing) {
        LineSpacingPreset.Compact -> base - 2
        LineSpacingPreset.Standard -> base
        LineSpacingPreset.Relaxed -> base + 4
    }
}

private fun statusVisibility(visibility: String): Pair<ImageVector, String> = when (visibility) {
    "unlisted" -> Icons.Outlined.Group to "ひかえめな公開"
    "private" -> Icons.Outlined.Lock to "フォロワー限定"
    "direct" -> Icons.Outlined.AlternateEmail to "指定した相手のみ"
    else -> Icons.Outlined.Public to "公開"
}

private fun compactCount(count: Long): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0).replace(".0K", "K")
    else -> String.format(Locale.US, "%.1fM", count / 1_000_000.0).replace(".0M", "M")
}
