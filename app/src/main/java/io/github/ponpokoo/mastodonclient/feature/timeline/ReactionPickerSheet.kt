package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.feature.common.SearchInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Null outside the authenticated app, so isolated previews and tests remain offline. */
internal val LocalCustomReactionEmojiLoader =
    staticCompositionLocalOf<(suspend () -> Result<List<CustomEmoji>>)?> { null }
internal val LocalReactionHistoryLoader = staticCompositionLocalOf<(suspend () -> List<String>)?> { null }
internal val LocalReactionHistorySaver = staticCompositionLocalOf<(suspend (List<String>) -> Unit)?> { null }
internal val LocalEmojiFavoritesLoader = staticCompositionLocalOf<(suspend () -> List<String>)?> { null }
internal val LocalEmojiFavoriteUpdater = staticCompositionLocalOf<(suspend (String, Boolean) -> List<String>)?> { null }
internal val LocalEmojiFavoritesSessionKey = staticCompositionLocalOf { "" }

private data class StandardReaction(val emoji: String, val label: String, val category: String)
private data class PickerEmoji(val name: String, val label: String, val custom: CustomEmoji? = null)

private data class PickerCategory(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val representative: CustomEmoji? = null,
)

private val standardPickerCategories = listOf(
    PickerCategory("all", "すべて", Icons.Outlined.Apps),
    PickerCategory("history", "履歴", Icons.Outlined.History),
    PickerCategory("favorites", "お気に入り", Icons.Outlined.StarBorder),
    PickerCategory("standard:標準", "標準", Icons.Outlined.Home),
    PickerCategory("standard:表情", "表情", Icons.Outlined.SentimentSatisfiedAlt),
    PickerCategory("standard:しぐさ・記号", "しぐさ・記号", Icons.Outlined.WavingHand),
)

private val standardReactions = listOf(
    StandardReaction("👍", "いいね", "標準"), StandardReaction("❤️", "ハート", "標準"),
    StandardReaction("🎉", "お祝い", "標準"), StandardReaction("😂", "笑い", "標準"),
    StandardReaction("😮", "驚き", "標準"), StandardReaction("🙏", "お願い", "標準"),
    StandardReaction("👏", "拍手", "表情"), StandardReaction("😊", "笑顔", "表情"),
    StandardReaction("🥰", "好き", "表情"), StandardReaction("😍", "大好き", "表情"),
    StandardReaction("😢", "悲しい", "表情"), StandardReaction("😭", "泣く", "表情"),
    StandardReaction("😆", "嬉しい", "表情"), StandardReaction("😅", "苦笑", "表情"),
    StandardReaction("🤔", "考える", "表情"), StandardReaction("😎", "かっこいい", "表情"),
    StandardReaction("🥳", "パーティー", "表情"), StandardReaction("😇", "天使", "表情"),
    StandardReaction("😤", "怒る", "表情"), StandardReaction("😱", "怖い", "表情"),
    StandardReaction("🥺", "お願い", "表情"), StandardReaction("🤗", "ハグ", "表情"),
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
    selectionLabel: String = "この絵文字でリアクション",
) {
    key(LocalEmojiFavoritesSessionKey.current) {
        ReactionPickerContent(canUndo, onDismiss, onSelected, title, customEmojisOverride, selectionLabel)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ReactionPickerContent(
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit,
    title: String,
    customEmojisOverride: List<CustomEmoji>?,
    selectionLabel: String,
) {
    val loadCustomEmojis = LocalCustomReactionEmojiLoader.current
    val loadHistory = LocalReactionHistoryLoader.current
    val saveHistory = LocalReactionHistorySaver.current
    val loadFavorites = LocalEmojiFavoritesLoader.current
    val updateFavorite = LocalEmojiFavoriteUpdater.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val headerDismissOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 76.dp.toPx() }
    val headerDragState = rememberDraggableState { delta ->
        scope.launch { headerDismissOffset.snapTo((headerDismissOffset.value + delta).coerceAtLeast(0f)) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var previewEmoji by remember { mutableStateOf<PickerEmoji?>(null) }
    var pendingDeletion by remember { mutableStateOf<String?>(null) }
    var pendingFavoriteDeletion by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf<List<String>>(emptyList()) }
    var favoritesLoaded by remember { mutableStateOf(loadFavorites == null) }
    var favoritesError by remember { mutableStateOf<String?>(null) }
    var favoriteSaving by remember { mutableStateOf(false) }
    var favoritesLoadAttempt by remember { mutableStateOf(0) }
    var searchValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val query = searchValue.text
    var selectedCategoryKey by rememberSaveable { mutableStateOf("all") }
    var categoryMenuOpen by remember { mutableStateOf(false) }
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
    LaunchedEffect(loadFavorites, favoritesLoadAttempt) {
        favoritesLoaded = false
        favoritesError = null
        try {
            favorites = loadFavorites?.invoke().orEmpty().distinct()
            favoritesLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            favoritesError = "お気に入りを読み込めませんでした"
        }
    }

    val categories = remember(customEmojis) {
        standardPickerCategories + customEmojis
            .groupBy { it.category?.takeIf(String::isNotBlank) ?: "その他" }
            .map { (category, emojis) ->
                PickerCategory("custom:$category", "カスタム · $category", Icons.Outlined.EmojiEmotions, emojis.first())
            }
    }
    val selectedCategory = categories.firstOrNull { it.key == selectedCategoryKey } ?: categories.first()
    LaunchedEffect(categories) {
        if (categories.none { it.key == selectedCategoryKey }) selectedCategoryKey = "all"
    }
    val searchText = remember(query) { normalizeEmojiSearch(query.trim().trim(':')) }
    val matchingStandardGroups = remember(searchText) {
        standardReactions.filter {
            searchText.isBlank() || it.emoji.contains(searchText) || it.label.contains(searchText, ignoreCase = true)
        }.groupBy { it.category }
    }
    val customSearchIndex = remember(customEmojis) {
        customEmojis.map { it to customEmojiSearchKey(it) }
    }
    val customQuery = remember(query) { emojiSearchQuery(query) }
    val matchingCustomGroups = remember(customQuery, customSearchIndex) {
        customSearchIndex.filter { (_, key) -> key.matches(customQuery) }
            .map { it.first }
            .groupBy { it.category?.takeIf(String::isNotBlank) ?: "その他" }
    }
    val standardGroups = remember(matchingStandardGroups, selectedCategory.key) {
        matchingStandardGroups.filterKeys { selectedCategory.key == "all" || selectedCategory.key == "standard:$it" }
    }
    val customGroups = remember(matchingCustomGroups, selectedCategory.key) {
        matchingCustomGroups.filterKeys { selectedCategory.key == "all" || selectedCategory.key == "custom:$it" }
    }
    val visibleHistory = remember(history, searchText, matchingStandardGroups, matchingCustomGroups) {
        val matchingNames = matchingStandardGroups.values.flatten().map { it.emoji }.toSet() +
            matchingCustomGroups.values.flatten().map { it.shortcode }.toSet()
        history.filter { searchText.isBlank() || it in matchingNames || normalizeEmojiSearch(it).contains(searchText) }
    }
    val visibleFavorites = remember(favorites, searchText, matchingStandardGroups, matchingCustomGroups) {
        val matchingNames = matchingStandardGroups.values.flatten().map { it.emoji }.toSet() +
            matchingCustomGroups.values.flatten().map { it.shortcode }.toSet()
        favorites.filter { searchText.isBlank() || it in matchingNames || normalizeEmojiSearch(it).contains(searchText) }
    }
    val showHistory = selectedCategory.key == "history" || (selectedCategory.key == "all" && query.isBlank())
    val showFavorites = selectedCategory.key == "favorites"
    val showCustomLoading = selectedCategory.key == "all" || selectedCategory.key == "history" ||
        showFavorites || selectedCategory.key.startsWith("custom:")
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val columns = if (screenWidth >= 360) 8 else 7
    val cellSize = ((screenWidth - 32 - (columns - 1) * 2).toFloat() / columns).dp
    val listState = rememberLazyListState()
    LaunchedEffect(searchText, selectedCategory.key) { listState.scrollToItem(0) }
    val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    val categoryHintStyle = MaterialTheme.typography.bodySmall.toSpanStyle()

    fun openPreview(name: String) {
        val custom = customEmojis.firstOrNull { it.shortcode == name }
        val standard = standardReactions.firstOrNull { it.emoji == name }
        previewEmoji = PickerEmoji(name, custom?.let { ":${it.shortcode}:" } ?: standard?.label ?: name, custom)
    }

    suspend fun persistFavorite(name: String, favorite: Boolean): Boolean {
        val update = updateFavorite ?: return false
        return try {
            val saved = update(name, favorite)
            check((name in saved) == favorite)
            favorites = saved
            favoritesError = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            favoritesError = "お気に入りを保存できませんでした"
            false
        }
    }

    fun changeFavorite(name: String, favorite: Boolean) {
        if (!favoritesLoaded || favoriteSaving || updateFavorite == null) return
        favoriteSaving = true
        scope.launch {
            val saved = try {
                persistFavorite(name, favorite).also { success ->
                    if (success && previewEmoji?.name == name) previewEmoji = null
                }
            } finally { favoriteSaving = false }
            snackbarHostState.currentSnackbarData?.dismiss()
            if (!saved) {
                snackbarHostState.showSnackbar("お気に入りを保存できませんでした")
            } else if (favorite) {
                snackbarHostState.showSnackbar("お気に入りに追加しました", duration = SnackbarDuration.Short)
            } else if (snackbarHostState.showSnackbar("お気に入りから削除しました", "元に戻す",
                    duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                favoriteSaving = true
                try { persistFavorite(name, true) } finally { favoriteSaving = false }
            }
        }
    }

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer { translationY = headerDismissOffset.value },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The parent has already removed the IME area. Keep background visible above the sheet.
        val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        val heightReduction = 48.dp
        val maxSheetHeight = (minOf(
            LocalConfiguration.current.screenHeightDp.dp * 0.82f,
            (maxHeight - statusBarHeight - 48.dp).coerceAtLeast(0.dp),
        ) - heightReduction).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .testTag("reaction_picker_sheet")
            .padding(horizontal = 16.dp).padding(top = 8.dp)) {
            Column(Modifier.fillMaxWidth().testTag("reaction_picker_header")
                .semantics(mergeDescendants = true) {
                    dismiss {
                        scope.launch {
                            sheetState.hide()
                            if (!sheetState.isVisible) onDismiss()
                        }
                        true
                    }
                }
                .draggable(
                    state = headerDragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { headerDismissOffset.stop() },
                    onDragStopped = {
                        if (headerDismissOffset.value >= dismissThresholdPx) {
                            sheetState.hide()
                            if (!sheetState.isVisible) onDismiss()
                        } else {
                            headerDismissOffset.animateTo(0f, animationSpec = tween(140))
                        }
                    },
                )) {
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(32.dp).height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))
                        .testTag("reaction_picker_drag_handle"))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SearchInput(
                    value = searchValue,
                    onValueChange = { searchValue = it },
                    placeholder = "絵文字を検索",
                    onClear = { searchValue = TextFieldValue("") },
                    modifier = Modifier.weight(1f),
                    inputModifier = Modifier.padding(vertical = 12.dp).testTag("reaction_search"),
                    clearButtonModifier = Modifier.testTag("reaction_search_clear"),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
            PickerCategoryStrip(categories, selectedCategory, { selectedCategoryKey = it.key }, { categoryMenuOpen = true })
            Text(buildAnnotatedString {
                append(selectedCategory.label)
                if (showFavorites) withStyle(categoryHintStyle) { append("  (長押しで登録できます)") }
            }, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("reaction_selected_category"),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .weight(1f, fill = false)
                    .height((LocalConfiguration.current.screenHeightDp.dp * 0.58f - heightReduction).coerceAtLeast(0.dp))
                    .testTag("reaction_emoji_list")
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
                val savedEmojis = if (showFavorites) visibleFavorites else if (showHistory) visibleHistory else emptyList()
                if (savedEmojis.isNotEmpty()) {
                    if (selectedCategory.key == "all") item(key = "history-header") { ReactionCategory("履歴") }
                    items(savedEmojis.chunked(columns), key = { "saved-${it.first()}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { name ->
                                standardReactions.firstOrNull { it.emoji == name }?.let { reaction ->
                                    ReactionEmojiTile(reaction.emoji, cellSize,
                                        onLongClick = { if (showFavorites) pendingFavoriteDeletion = name else pendingDeletion = name }) { onSelected(reaction.emoji) }
                                } ?: customEmojis.firstOrNull { it.shortcode == name }?.let { emoji ->
                                    CustomReactionEmojiTile(emoji, cellSize,
                                        onLongClick = { if (showFavorites) pendingFavoriteDeletion = name else pendingDeletion = name }) { onSelected(emoji.shortcode) }
                                } ?: Box(Modifier.size(cellSize).combinedClickable(
                                    onClick = { onSelected(name) },
                                    onLongClick = { if (showFavorites) pendingFavoriteDeletion = name else pendingDeletion = name }),
                                    contentAlignment = Alignment.Center) {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                standardGroups.forEach { (category, emojis) ->
                    if (selectedCategory.key == "all") item(key = "standard-$category") { ReactionCategory(category) }
                    items(emojis.chunked(columns), key = { "standard-${it.first().emoji}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { reaction ->
                                ReactionEmojiTile(reaction.emoji, cellSize,
                                    onLongClick = { openPreview(reaction.emoji) }) { onSelected(reaction.emoji) }
                            }
                        }
                    }
                }
                if (loading && showCustomLoading) item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                if (showCustomLoading) loadError?.let { message -> item(key = "error") { Text(message, Modifier.padding(12.dp)) } }
                customGroups.forEach { (category, emojis) ->
                    if (selectedCategory.key == "all") item(key = "custom-header-$category") { ReactionCategory("カスタム · $category") }
                    items(emojis.chunked(columns), key = { "custom-${it.first().shortcode}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { emoji ->
                                CustomReactionEmojiTile(emoji, cellSize,
                                    onLongClick = { openPreview(emoji.shortcode) }) { onSelected(emoji.shortcode) }
                            }
                        }
                    }
                }
                if (showFavorites && !favoritesLoaded && favoritesError == null) item(key = "favorites-loading") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                if (showFavorites && favoritesError != null) item(key = "favorites-error") {
                    Column {
                        Text(favoritesError!!, Modifier.padding(12.dp))
                        TextButton(onClick = { favoritesLoadAttempt++ }) { Text("再試行") }
                    }
                }
                if (!(loading && showCustomLoading) && (!showFavorites || favoritesLoaded) &&
                    standardGroups.isEmpty() && customGroups.isEmpty() && savedEmojis.isEmpty() &&
                    (!showFavorites || favoritesError == null)) {
                    item(key = "empty") {
                        Text(if (selectedCategory.key == "history" && query.isBlank()) "履歴はまだありません"
                            else if (showFavorites && query.isBlank()) "お気に入りはまだありません"
                            else "該当する絵文字がありません", Modifier.padding(12.dp))
                    }
                }
            }
            if (canUndo) {
                TextButton(onClick = { onSelected(null) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("リアクションを取り消す")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp))
        }
    }
    if (categoryMenuOpen) {
        AlertDialog(
            onDismissRequest = { categoryMenuOpen = false },
            title = { Text("カテゴリ") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.5f)
                    .testTag("reaction_category_menu")
                    .selectableGroup()) {
                    items(categories, key = { it.key }) { category ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selectedCategory.key == category.key, role = Role.RadioButton, onClick = {
                                selectedCategoryKey = category.key
                                categoryMenuOpen = false
                            }).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            PickerCategoryIcon(category)
                            Text(category.label, Modifier.weight(1f))
                            if (selectedCategory.key == category.key) Icon(Icons.Outlined.Check, contentDescription = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { categoryMenuOpen = false }) { Text("閉じる") } },
        )
    }
    previewEmoji?.let { emoji ->
        AlertDialog(
            modifier = Modifier.testTag("reaction_emoji_preview"),
            onDismissRequest = { previewEmoji = null },
            title = { Text(emoji.label) },
            text = {
                Column {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        if (emoji.custom != null) AsyncImage(
                            model = emoji.custom.staticUrl.ifBlank { emoji.custom.url },
                            contentDescription = emoji.label,
                            modifier = Modifier.fillMaxWidth().height(168.dp),
                            contentScale = ContentScale.Fit,
                        ) else Text(emoji.name, fontSize = 80.sp)
                    }
                    favoritesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { previewEmoji = null }) { Text("閉じる") }
                        TextButton(onClick = { changeFavorite(emoji.name, true) },
                            enabled = favoritesLoaded && !favoriteSaving && updateFavorite != null && emoji.name !in favorites) {
                            Text(if (emoji.name in favorites) "お気に入り登録済み" else "お気に入りに追加")
                        }
                    }
                    TextButton(onClick = { previewEmoji = null; onSelected(emoji.name) },
                        modifier = Modifier.align(Alignment.End)) { Text(selectionLabel) }
                }
            },
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
    pendingFavoriteDeletion?.let { emoji ->
        AlertDialog(
            onDismissRequest = { pendingFavoriteDeletion = null },
            title = { Text("お気に入りから削除") },
            text = { Text("$emoji をお気に入りから削除しますか？") },
            confirmButton = {
                TextButton(onClick = { pendingFavoriteDeletion = null; changeFavorite(emoji, false) },
                    enabled = !favoriteSaving) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingFavoriteDeletion = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun PickerCategoryStrip(
    categories: List<PickerCategory>,
    selected: PickerCategory,
    onSelected: (PickerCategory) -> Unit,
    onOpenList: () -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected.key, categories) {
        val index = categories.indexOfFirst { it.key == selected.key }
        val layout = state.layoutInfo
        if (index >= 0 && layout.visibleItemsInfo.none {
            it.index == index && it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
        }) state.animateScrollToItem(index)
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).selectableGroup(), state = state) {
            items(categories, key = { it.key }) { category ->
                val isSelected = selected.key == category.key
                val underline = MaterialTheme.colorScheme.primary
                Box(Modifier.height(48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(isSelected, role = Role.Tab, onClick = { onSelected(category) })
                    .semantics { contentDescription = "カテゴリ：${category.label}" }
                    .drawWithContent {
                        drawContent()
                        if (isSelected) drawRoundRect(underline,
                            topLeft = Offset((size.width - 20.dp.toPx()) / 2, size.height - 4.dp.toPx()),
                            size = Size(20.dp.toPx(), 2.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()))
                    }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    PickerCategoryIcon(category, preserveImageWidth = true)
                }
            }
        }
        IconButton(onClick = onOpenList) { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "カテゴリ一覧") }
    }
}

@Composable
private fun PickerCategoryIcon(category: PickerCategory, preserveImageWidth: Boolean = false) {
    val emoji = category.representative
    if (emoji == null) Icon(category.icon, contentDescription = null, modifier = Modifier.size(24.dp))
    else {
        val fallback = rememberVectorPainter(category.icon)
        val url = emoji.staticUrl.ifBlank { emoji.url }
        val context = LocalContext.current
        var ratio by rememberSaveable(url) { mutableFloatStateOf(1f) }
        val request = remember(context, url, preserveImageWidth) {
            ImageRequest.Builder(context).data(url).apply {
                // A square decode target would shrink wide lettering before its width is known.
                if (preserveImageWidth) size(coil3.size.Size.ORIGINAL)
            }.build()
        }
        AsyncImage(model = request, contentDescription = null,
            placeholder = fallback, error = fallback, fallback = fallback,
            onSuccess = {
                val image = it.result.image
                if (image.width > 0 && image.height > 0) ratio = image.width.toFloat() / image.height
            },
            modifier = Modifier.height(24.dp).width(24.dp * if (preserveImageWidth) ratio else 1f)
                .testTag("reaction_category_image:${category.key}"),
            contentScale = ContentScale.Fit)
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
