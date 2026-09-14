package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import kotlinx.coroutines.launch

/** Null outside the authenticated app, so isolated previews and tests remain offline. */
internal val LocalCustomReactionEmojiLoader =
    staticCompositionLocalOf<(suspend () -> Result<List<CustomEmoji>>)?> { null }
internal val LocalReactionHistoryLoader = staticCompositionLocalOf<(suspend () -> List<String>)?> { null }
internal val LocalReactionHistorySaver = staticCompositionLocalOf<(suspend (List<String>) -> Unit)?> { null }

private data class StandardReaction(val emoji: String, val label: String, val category: String)

private val standardReactions = listOf(
    StandardReaction("👍", "いいね", "よく使う"), StandardReaction("❤️", "ハート", "よく使う"),
    StandardReaction("🎉", "お祝い", "よく使う"), StandardReaction("😂", "笑い", "よく使う"),
    StandardReaction("😮", "驚き", "よく使う"), StandardReaction("🙏", "お願い", "よく使う"),
    StandardReaction("👏", "拍手", "気持ち"), StandardReaction("😊", "笑顔", "気持ち"),
    StandardReaction("🥰", "好き", "気持ち"), StandardReaction("😍", "大好き", "気持ち"),
    StandardReaction("😢", "悲しい", "気持ち"), StandardReaction("😭", "泣く", "気持ち"),
    StandardReaction("😆", "嬉しい", "気持ち"), StandardReaction("😅", "苦笑", "気持ち"),
    StandardReaction("🤔", "考える", "気持ち"), StandardReaction("😎", "かっこいい", "気持ち"),
    StandardReaction("🥳", "パーティー", "気持ち"), StandardReaction("😇", "天使", "気持ち"),
    StandardReaction("😤", "怒る", "気持ち"), StandardReaction("😱", "怖い", "気持ち"),
    StandardReaction("🥺", "お願い", "気持ち"), StandardReaction("🤗", "ハグ", "気持ち"),
    StandardReaction("🔥", "炎", "しぐさ・記号"), StandardReaction("💯", "満点", "しぐさ・記号"),
    StandardReaction("✨", "きらきら", "しぐさ・記号"), StandardReaction("💖", "キラキラハート", "しぐさ・記号"),
    StandardReaction("💕", "ハート", "しぐさ・記号"), StandardReaction("💪", "力こぶ", "しぐさ・記号"),
    StandardReaction("🤝", "握手", "しぐさ・記号"), StandardReaction("👀", "目", "しぐさ・記号"),
    StandardReaction("🙌", "万歳", "しぐさ・記号"), StandardReaction("🍀", "クローバー", "しぐさ・記号"),
    StandardReaction("🌸", "桜", "しぐさ・記号"), StandardReaction("⭐", "星", "しぐさ・記号"),
    StandardReaction("🎂", "ケーキ", "しぐさ・記号"), StandardReaction("✅", "チェック", "しぐさ・記号"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ReactionPickerSheet(
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit,
    title: String = "リアクション",
    customEmojisOverride: List<CustomEmoji>? = null,
) {
    val loadCustomEmojis = LocalCustomReactionEmojiLoader.current
    val loadHistory = LocalReactionHistoryLoader.current
    val saveHistory = LocalReactionHistorySaver.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var previewEmoji by remember { mutableStateOf<CustomEmoji?>(null) }
    var pendingDeletion by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var customEmojis by remember { mutableStateOf(customEmojisOverride.orEmpty()) }
    var loading by remember { mutableStateOf(customEmojisOverride == null && loadCustomEmojis != null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loadCustomEmojis, customEmojisOverride) {
        if (customEmojisOverride != null) {
            customEmojis = customEmojisOverride
            loading = false
            loadError = null
        } else if (loadCustomEmojis != null) {
            loading = true
            loadCustomEmojis().fold(
                onSuccess = { customEmojis = it },
                onFailure = { loadError = "カスタム絵文字を取得できませんでした" },
            )
            loading = false
        }
    }
    LaunchedEffect(loadHistory) { history = loadHistory?.invoke().orEmpty() }

    val searchText = remember(query) { normalizeEmojiSearch(query.trim().trim(':')) }
    val standardGroups = remember(searchText) {
        standardReactions.filter {
            searchText.isBlank() || it.emoji.contains(searchText) || it.label.contains(searchText, ignoreCase = true)
        }.groupBy { it.category }
    }
    val customSearchIndex = remember(customEmojis) {
        customEmojis.map { it to customEmojiSearchKey(it) }
    }
    val customGroups = remember(searchText, customSearchIndex) {
        customSearchIndex.filter { (_, key) -> searchText.isBlank() || searchText in key }
            .map { it.first }
            .groupBy { it.category?.takeIf(String::isNotBlank) ?: "その他" }
    }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val columns = if (screenWidth >= 360) 8 else 7
    val cellSize = ((screenWidth - 32 - (columns - 1) * 2).toFloat() / columns).dp
    val listState = rememberLazyListState()
    LaunchedEffect(searchText) { listState.scrollToItem(0) }
    val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)

    fun removeFromHistory(emoji: String) {
        val save = saveHistory ?: return
        val previous = history
        val updated = previous.filterNot { it == emoji }
        history = updated
        scope.launch {
            save(updated)
            snackbarHostState.currentSnackbarData?.dismiss()
            if (snackbarHostState.showSnackbar("履歴から削除しました", "元に戻す", duration = SnackbarDuration.Short)
                == SnackbarResult.ActionPerformed) {
                history = previous
                save(previous)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .testTag("reaction_search")
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { input ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("絵文字を検索", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                            input()
                        }
                    }
                },
            )
            if (query.isBlank()) {
                val recentStandard = history.mapNotNull { name -> standardReactions.firstOrNull { it.emoji == name } }
                val recentCustom = history.mapNotNull { name -> customEmojis.firstOrNull { it.shortcode == name } }
                if (recentStandard.isNotEmpty() || recentCustom.isNotEmpty()) {
                    ReactionCategory("履歴")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        history.forEach { name ->
                            standardReactions.firstOrNull { it.emoji == name }?.let { reaction ->
                                ReactionEmojiTile(reaction.emoji, cellSize,
                                    onLongClick = { pendingDeletion = name }) { onSelected(reaction.emoji) }
                            } ?: customEmojis.firstOrNull { it.shortcode == name }?.let { emoji ->
                                CustomReactionEmojiTile(emoji, cellSize,
                                    onLongClick = { pendingDeletion = name }) { onSelected(emoji.shortcode) }
                            } ?: run {
                                Box(Modifier.size(cellSize).combinedClickable(
                                    onClick = {}, onLongClick = { pendingDeletion = name }),
                                    contentAlignment = Alignment.Center) {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.58f)
                    .drawWithContent {
                        drawContent()
                        val layout = listState.layoutInfo
                        val visible = layout.visibleItemsInfo.size
                        val total = layout.totalItemsCount
                        if (visible > 0 && total > visible) {
                            val barWidth = 3.dp.toPx()
                            val barHeight = (size.height * visible / total).coerceAtLeast(24.dp.toPx())
                            val progress = listState.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
                            drawRoundRect(
                                color = scrollbarColor,
                                topLeft = Offset(size.width - barWidth, (size.height - barHeight) * progress.coerceIn(0f, 1f)),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2),
                            )
                        }
                    },
                state = listState,
            ) {
                standardGroups.forEach { (category, emojis) ->
                    item(key = "standard-$category") { ReactionCategory(category) }
                    items(emojis.chunked(columns), key = { "standard-${it.first().emoji}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { reaction ->
                                ReactionEmojiTile(reaction.emoji, cellSize) { onSelected(reaction.emoji) }
                            }
                        }
                    }
                }
                if (loading) item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                loadError?.let { message -> item(key = "error") { Text(message, Modifier.padding(12.dp)) } }
                customGroups.forEach { (category, emojis) ->
                    item(key = "custom-header-$category") { ReactionCategory("カスタム · $category") }
                    items(emojis.chunked(columns), key = { "custom-${it.first().shortcode}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { emoji ->
                                CustomReactionEmojiTile(emoji, cellSize,
                                    onLongClick = { previewEmoji = emoji }) { onSelected(emoji.shortcode) }
                            }
                        }
                    }
                }
                if (!loading && standardGroups.isEmpty() && customGroups.isEmpty()) {
                    item(key = "empty") { Text("該当する絵文字がありません", Modifier.padding(12.dp)) }
                }
            }
            if (canUndo) {
                TextButton(onClick = { onSelected(null) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("リアクションを取り消す")
                }
            }
            SnackbarHost(snackbarHostState)
            Spacer(Modifier.height(8.dp))
        }
    }
    previewEmoji?.let { emoji ->
        AlertDialog(
            onDismissRequest = { previewEmoji = null },
            title = { Text(":${emoji.shortcode}:") },
            text = {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = emoji.staticUrl.ifBlank { emoji.url },
                        contentDescription = ":${emoji.shortcode}:",
                        modifier = Modifier.fillMaxWidth().height(168.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { previewEmoji = null; onSelected(emoji.shortcode) }) {
                Text("この絵文字でリアクション")
            } },
            dismissButton = { TextButton(onClick = { previewEmoji = null }) { Text("閉じる") } },
        )
    }
    pendingDeletion?.let { emoji ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("履歴から削除") },
            text = { Text("$emoji を履歴から削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletion = null
                    removeFromHistory(emoji)
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReactionEmojiTile(emoji: String, cellSize: androidx.compose.ui.unit.Dp,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(Modifier.size(cellSize).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 24.sp)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CustomReactionEmojiTile(emoji: CustomEmoji, cellSize: androidx.compose.ui.unit.Dp,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(Modifier.width(cellSize).height(cellSize + 18.dp)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = emoji.staticUrl.ifBlank { emoji.url },
                contentDescription = ":${emoji.shortcode}:",
                modifier = Modifier.size(cellSize - 4.dp),
                contentScale = ContentScale.Fit,
            )
            Text(emoji.shortcode, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReactionCategory(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
