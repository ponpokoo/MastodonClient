package io.github.ponpokoo.transitionprototype

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

private enum class PrototypeDestination(val label: String, val icon: ImageVector) {
    Home("ホーム", Icons.Outlined.Home),
    Explore("探索", Icons.Outlined.Search),
    Notifications("通知", Icons.Outlined.NotificationsNone),
    Profile("プロフィール", Icons.Outlined.PersonOutline),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrototypeApp() {
    var destinationName by rememberSaveable { mutableStateOf(PrototypeDestination.Home.name) }
    val destination = PrototypeDestination.valueOf(destinationName)
    var feed by rememberSaveable { mutableStateOf("ホーム") }
    var feedMenu by remember { mutableStateOf(false) }
    var authorId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewTootId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewIndex by rememberSaveable { mutableIntStateOf(0) }
    var favourites by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var boosts by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val timelineState = rememberLazyListState()
    val profileState = rememberScrollState()
    val savedScreens = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onAction: (String) -> Unit = { label -> scope.launch { snackbar.showSnackbar("${label}をタップしました") } }
    val openedAuthor = PrototypeToots.map { it.author }.firstOrNull { it.id == authorId }
    BackHandler(enabled = openedAuthor != null) { authorId = null }

    // Recreate the scaffold layout when the top bar changes. A transient taller viewport
    // would otherwise clamp the saved position of this short fixture timeline.
    key(destinationName, authorId) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("prototype_app"),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                when {
                    openedAuthor != null -> TopAppBar(
                        title = { Text(openedAuthor.name) },
                        navigationIcon = {
                            IconButton(onClick = { authorId = null }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る")
                            }
                        },
                    )
                    destination == PrototypeDestination.Home -> TopAppBar(
                        title = {
                            Box {
                                Row(Modifier.clickable { feedMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(feed)
                                    Icon(Icons.Outlined.ArrowDropDown, "フィードを切り替える")
                                }
                                DropdownMenu(expanded = feedMenu, onDismissRequest = { feedMenu = false }) {
                                    listOf("ホーム", "ローカル", "連合").forEach { label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            feed = label
                                            feedMenu = false
                                            scope.launch { timelineState.scrollToItem(0) }
                                        })
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { onAction("アカウントを切り替える") }) {
                                Image(painterResource(PrototypeOwnAuthor.avatar), "アカウントを切り替える",
                                    Modifier.size(30.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            }
                            IconButton(onClick = { onAction("サーバーからのお知らせ") }) {
                                Icon(Icons.Outlined.Campaign, "サーバーからのお知らせ")
                            }
                            IconButton(onClick = { onAction("設定") }) { Icon(Icons.Outlined.Settings, "設定") }
                        },
                    )
                    destination != PrototypeDestination.Explore -> TopAppBar(title = { Text(destination.label) })
                }
            },
            bottomBar = {
                if (openedAuthor == null) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        PrototypeDestination.entries.forEach { item ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("main_tab_${item.name.lowercase()}"),
                                selected = item == destination,
                                onClick = {
                                    if (item == destination) {
                                        scope.launch {
                                            if (item == PrototypeDestination.Home) timelineState.animateScrollToItem(0)
                                            if (item == PrototypeDestination.Profile) profileState.animateScrollTo(0)
                                        }
                                    } else destinationName = item.name
                                },
                                icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (openedAuthor == null && destination == PrototypeDestination.Home) {
                    FloatingActionButton(onClick = { onAction("新規投稿") }, containerColor = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(20.dp)) { Icon(Icons.Outlined.Edit, "新規投稿") }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (openedAuthor != null) {
                PrototypeProfile(openedAuthor, Modifier.fillMaxSize().padding(padding).testTag("account_profile")
                    .verticalScroll(rememberScrollState()))
            } else savedScreens.SaveableStateProvider(destination.name) {
                when (destination) {
                    PrototypeDestination.Home -> LazyColumn(
                        state = timelineState,
                        modifier = Modifier.fillMaxSize().padding(padding).testTag("timeline_list"),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(PrototypeToots, key = { it.id }) { toot ->
                            PrototypeTootCard(
                                toot = toot, favourited = toot.id in favourites, boosted = toot.id in boosts,
                                onFavourite = { favourites = favourites.toggle(toot.id) },
                                onBoost = { boosts = boosts.toggle(toot.id) },
                                onAuthor = { authorId = toot.author.id },
                                onMedia = { index -> previewIndex = index; previewTootId = toot.id },
                                onAction = onAction,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                        }
                    }
                    PrototypeDestination.Explore -> PrototypeExplore(Modifier.fillMaxSize().padding(padding).testTag("explore_screen"))
                    PrototypeDestination.Notifications -> PrototypeEmptyTabs(
                        listOf("すべて", "メンション", "リアクション"), "通知はありません",
                        Modifier.fillMaxSize().padding(padding).testTag("notifications_screen"),
                    )
                    PrototypeDestination.Profile -> PrototypeProfile(PrototypeOwnAuthor,
                        Modifier.fillMaxSize().padding(padding).testTag("profile_screen").verticalScroll(profileState))
                }
            }
        }
    }
    PrototypeToots.firstOrNull { it.id == previewTootId }?.let { toot ->
        PrototypeMediaViewer(toot.media, previewIndex, onClose = { previewTootId = null })
    }
}

private fun List<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun PrototypeExplore(modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        TextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("検索") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("search_input"),
        )
        PrototypeEmptyTabs(listOf("投稿", "ハッシュタグ", "ニュース", "購読中"),
            if (query.isBlank()) "表示する項目はありません" else "検索結果はありません", Modifier.weight(1f))
    }
}

@Composable
private fun PrototypeProfile(author: PrototypeAuthor, modifier: Modifier) {
    Column(modifier) {
        Image(painterResource(R.drawable.sample_landscape), null, Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Crop)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(author.avatar), "プロフィール画像", Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Text(author.name, style = MaterialTheme.typography.headlineSmall)
            Text(author.account, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("写真と日々のこと。", style = MaterialTheme.typography.bodyLarge)
            Text("2 投稿   24 フォロー   32 フォロワー", style = MaterialTheme.typography.bodyMedium)
        }
        PrototypeEmptyTabs(listOf("投稿", "投稿と返信", "メディア"), "表示する投稿はありません", Modifier.height(260.dp))
    }
}

@Composable
private fun PrototypeEmptyTabs(labels: List<String>, emptyMessage: String, modifier: Modifier) {
    val pager = rememberPagerState(pageCount = { labels.size })
    val scope = rememberCoroutineScope()
    Column(modifier) {
        PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 0.dp) {
            labels.forEachIndexed { index, label ->
                Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = { Text(label) })
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
