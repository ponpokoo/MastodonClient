package io.github.ponpokoo.mastodonclient.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment

@Composable
internal fun MediaImageOverview(
    media: List<MediaAttachment>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    // Each opening starts at the current image; ordinary scrolling stays local to the grid.
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex / 2 * 2)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), state = gridState,
        modifier = modifier.background(Color.Black).statusBarsPadding().navigationBarsPadding()
            .padding(top = 64.dp).testTag("media_image_overview"),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(media, key = { _, item -> item.id }) { index, item ->
            val isSelected = index == selectedIndex
            val shape = RoundedCornerShape(8.dp)
            val selectionColor = Color(0xFFAAC7FF)
            var previewFailed by rememberSaveable(item.id, item.previewUrl, item.url) { mutableStateOf(false) }
            val imageUrl = if (previewFailed) item.url else item.previewUrl ?: item.url
            var imageFailed by rememberSaveable(item.id, imageUrl) { mutableStateOf(false) }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Color(0xFF141414))
                    .clickable(role = Role.Button, onClickLabel = "画像を開く") { onSelect(index) }
                    .semantics { selected = isSelected }
                    .then(if (isSelected) Modifier.border(3.dp, selectionColor, shape) else Modifier)
                    .testTag("media_overview_image_$index"),
            ) {
                AsyncImage(
                    model = imageUrl, contentDescription = "${index + 1}枚目：${item.description ?: "添付画像"}",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    onSuccess = { imageFailed = false },
                    onError = {
                        if (!previewFailed && item.previewUrl != null && item.url != null && item.previewUrl != item.url) {
                            previewFailed = true
                        } else imageFailed = true
                    },
                )
                if (imageFailed) {
                    Text("画像が取得できませんでした", color = Color.White,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp))
                }
                Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = "閲覧中",
                        tint = Color(0xFF11274C),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                            .background(selectionColor, CircleShape).padding(4.dp))
                }
            }
        }
    }
}
