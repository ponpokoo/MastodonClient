package io.github.ponpokoo.transitionprototype

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Standard avatar/media sizes and post indentation follow production StatusCard.kt.
@Composable
internal fun PrototypeTootCard(
    toot: PrototypeToot,
    favourited: Boolean,
    boosted: Boolean,
    onFavourite: () -> Unit,
    onBoost: () -> Unit,
    onAuthor: () -> Unit,
    onMedia: (Int) -> Unit,
    onAction: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .testTag("toot_${toot.id}").padding(start = 8.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(48.dp).offset(x = (-2).dp).testTag("toot_${toot.id}_author")
                    .clickable(role = Role.Button, onClick = onAuthor),
                contentAlignment = Alignment.TopCenter,
            ) {
                Image(
                    painterResource(toot.author.avatar), "${toot.author.name}のプロフィール",
                    Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(toot.author.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(toot.author.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.height(48.dp).offset(x = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Public, "公開", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(3.dp))
                Text(toot.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onAction("投稿メニュー") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, "投稿メニュー", Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(start = 52.dp)) {
            Text(toot.body, Modifier.padding(top = 8.dp), fontSize = 16.sp, lineHeight = 24.sp)
            PrototypeMediaGrid(toot, onMedia)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                PrototypeAction(Icons.AutoMirrored.Filled.Reply, "返信", "toot_${toot.id}_reply", toot.replies) { onAction("返信") }
                PrototypeAction(Icons.Outlined.Repeat, "ブースト", "toot_${toot.id}_boost",
                    toot.boosts + if (boosted) 1 else 0, boosted, onBoost)
                PrototypeAction(if (favourited) Icons.Filled.Star else Icons.Outlined.StarBorder, "お気に入り", "toot_${toot.id}_favourite",
                    toot.favourites + if (favourited) 1 else 0, favourited, onFavourite)
                PrototypeAction(Icons.Outlined.SentimentSatisfiedAlt, "リアクション", "toot_${toot.id}_reaction") { onAction("リアクション") }
                PrototypeAction(Icons.Outlined.Share, "共有", "toot_${toot.id}_share") { onAction("共有") }
            }
        }
    }
}

@Composable
private fun PrototypeMediaGrid(toot: PrototypeToot, onMedia: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val rowWidth = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            toot.media.chunked(2).forEach { images ->
                // Production standard layout divides width by aspect ratio, with a 400 dp height cap.
                val rowHeight = (rowWidth - 8.dp * (images.size - 1)) / images.sumOf { it.aspectRatio.toDouble() }.toFloat()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { image ->
                        Image(
                            painterResource(image.resource), image.description,
                            Modifier.size(rowHeight * image.aspectRatio, rowHeight.coerceAtMost(400.dp))
                                .clip(RoundedCornerShape(8.dp)).testTag("toot_${toot.id}_media_${toot.media.indexOf(image)}")
                                .clickable(role = Role.Button) { onMedia(toot.media.indexOf(image)) },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrototypeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    count: Int? = null,
    isSelected: Boolean? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick).semantics { if (isSelected != null) selected = isSelected }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = if (isSelected == true) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f))
        count?.let {
            Spacer(Modifier.width(4.dp))
            Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
