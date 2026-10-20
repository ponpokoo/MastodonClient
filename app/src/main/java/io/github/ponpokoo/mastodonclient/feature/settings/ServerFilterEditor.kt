package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal val filterContexts = linkedMapOf("home" to "ホーム・リスト", "notifications" to "通知欄", "public" to "公開タイムライン",
    "thread" to "投稿詳細", "account" to "プロフィール")

internal fun formatFilterExpiry(expiresAt: String?): String {
    if (expiresAt == null) return "期限なし"
    return try {
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.parse(expiresAt))
    } catch (_: DateTimeParseException) {
        expiresAt
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ServerFilterEditor(
    editor: FilterEditorState, busy: Boolean, error: String?, onChange: (FilterEdit) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit,
) {
    val edit = editor.edit
    val enabled = !busy && !editor.awaitingConfirmation
    val canSave = enabled && edit.title.isNotBlank() && edit.context.isNotEmpty() && edit.keywords.none { it.keyword.isBlank() }
    val keyboard = LocalSoftwareKeyboardController.current
    val keywordFocus = remember(edit.keywords.size) { List(edit.keywords.size) { FocusRequester() } }
    var focusNewKeyword by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(edit.keywords.size, focusNewKeyword) {
        focusNewKeyword?.takeIf { it in keywordFocus.indices }?.let {
            keywordFocus[it].requestFocus()
            keyboard?.show()
            focusNewKeyword = null
        }
    }
    BackHandler { if (!busy) onDismiss() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (editor.original == null) "フィルターを追加" else "フィルターを編集", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
            actions = {
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.padding(end = 8.dp).sizeIn(minWidth = 88.dp, minHeight = 48.dp).testTag("filter_save"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (busy) "保存中…" else if (editor.original == null) "追加" else "保存",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp).testTag("filter_editor"),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (editor.awaitingConfirmation) Text("作成結果の確認待ちです。次の同期で確認します。", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = edit.title, onValueChange = { onChange(edit.copy(title = it)) },
                modifier = Modifier.fillMaxWidth().testTag("filter_title"), label = { Text("フィルター名") },
                singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterSectionTitle("キーワード")
                edit.keywords.forEachIndexed { index, keyword ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = keyword.keyword,
                                onValueChange = { text -> onChange(edit.copy(keywords = edit.keywords.mapIndexed { i, old -> if (i == index) old.copy(keyword = text) else old })) },
                                modifier = Modifier.weight(1f).padding(start = 12.dp).focusRequester(keywordFocus[index])
                                    .testTag("filter_keyword_$index").semantics { contentDescription = "キーワード ${index + 1}" },
                                placeholder = { Text("キーワード ${index + 1}", style = MaterialTheme.typography.bodyMedium) },
                                textStyle = MaterialTheme.typography.bodyMedium,
                                singleLine = true, enabled = enabled,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                            )
                            IconButton(onClick = { onChange(edit.copy(keywords = edit.keywords.filterIndexed { i, _ -> i != index })) }, enabled = enabled) {
                                Icon(Icons.Outlined.Close, "キーワード ${index + 1}を削除")
                            }
                        }
                        FilterCheckbox("単語全体に一致", keyword.wholeWord, enabled) { whole ->
                            onChange(edit.copy(keywords = edit.keywords.mapIndexed { i, old -> if (i == index) old.copy(wholeWord = whole) else old }))
                        }
                    }
                }
                TextButton(onClick = {
                    focusNewKeyword = edit.keywords.size
                    onChange(edit.copy(keywords = edit.keywords + FilterKeyword(keyword = "")))
                }, enabled = enabled) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("キーワードを追加")
                }
            }
            Column {
                FilterSectionTitle("適用画面")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filterContexts.forEach { (value, label) ->
                        FilterCheckbox(label, value in edit.context, enabled, Modifier.widthIn(min = 148.dp)) { selected ->
                            onChange(edit.copy(context = if (selected) edit.context + value else edit.context - value))
                        }
                    }
                }
            }
            Column {
                FilterSectionTitle("一致した投稿の表示")
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("warn" to "内容警告(CW)", "hide" to "非表示") + if (editor.original?.action == "blur") listOf("blur" to "メディアを隠す") else emptyList()).forEach { (value, label) ->
                        Row(
                            Modifier.heightIn(min = 48.dp).selectable(edit.action == value, enabled = enabled, role = Role.RadioButton,
                                onClick = { onChange(edit.copy(action = value)) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(edit.action == value, onClick = null, enabled = enabled)
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterExpiryField(editor, enabled, onChange)
                editor.original?.let {
                    Text("現在の期限：${formatFilterExpiry(it.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            editor.original?.statuses?.takeIf { it.isNotEmpty() }?.let {
                Text("指定された投稿 ${it.size}件も保持します", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FilterCheckbox(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterExpiryField(editor: FilterEditorState, enabled: Boolean, onChange: (FilterEdit) -> Unit) {
    val edit = editor.edit
    val choices = listOf(null to "変更しない", 0L to "期限なし", 300L to "5分", 3600L to "1時間", 86400L to "1日", 604800L to "7日")
        .filter { it.first != null || editor.original != null }
    val selected = if (!edit.changeExpiry && editor.original != null) null else edit.expiresIn ?: 0L
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == selected }?.second ?: "${selected}秒", onValueChange = {},
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled).testTag("filter_expiry"),
            label = { Text("有効期限") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            readOnly = true, enabled = enabled,
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { (seconds, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onChange(edit.copy(changeExpiry = seconds != null, expiresIn = seconds?.takeIf { it > 0 }))
                    expanded = false
                }, leadingIcon = { RadioButton(selected == seconds, onClick = null) })
            }
        }
    }
}
