package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.pollCharacterCount
import io.github.ponpokoo.mastodonclient.feature.status.pollDurationLabel

@Composable
internal fun ComposerPollEditor(state: ComposePostUiState, viewModel: ComposePostViewModel) {
    val limits = state.configuration.polls
    val enabled = !state.isLoading && !state.isPosting && !state.isSavingDraft
    var durationMenu by remember { mutableStateOf(false) }
    val durations = remember(limits) {
        (listOf(300L, 1_800L, 3_600L, 21_600L, 86_400L, 259_200L, 604_800L) +
            listOf(limits.minExpiration, limits.maxExpiration))
            .filter { it in limits.minExpiration..limits.maxExpiration }.distinct().sorted()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("compose_poll"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("アンケート", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("選択肢 ${state.pollOptions.size} / ${limits.maxOptions}", style = MaterialTheme.typography.labelSmall)
            }
            state.pollOptions.forEachIndexed { index, option ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PollOptionInput(
                        value = option,
                        onValueChange = { viewModel.setPollOption(index, it) },
                        index = index,
                        maxCharacters = limits.maxCharactersPerOption,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).testTag("compose_poll_option_$index"),
                    )
                    IconButton(
                        onClick = { viewModel.removePollOption(index) },
                        enabled = enabled && state.pollOptions.size > 2,
                        modifier = Modifier.size(48.dp).testTag("compose_poll_remove_$index"),
                    ) { Icon(Icons.Outlined.Close, "選択肢 ${index + 1}を削除") }
                }
            }
            TextButton(onClick = viewModel::addPollOption, enabled = enabled && state.pollOptions.size < limits.maxOptions) {
                Text("＋ 選択肢を追加")
            }
            Box {
                OutlinedButton(
                    onClick = { durationMenu = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("compose_poll_duration"),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("投票期間", Modifier.weight(1f))
                    Text(pollDurationLabel(state.pollExpiresInSeconds, limits.maxExpiration))
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(expanded = durationMenu && enabled, onDismissRequest = { durationMenu = false }) {
                    durations.forEach { seconds ->
                        DropdownMenuItem(text = { Text(pollDurationLabel(seconds, limits.maxExpiration)) }, onClick = {
                            viewModel.setPollDuration(seconds)
                            durationMenu = false
                        })
                    }
                }
            }
            Text(
                "${pollDurationLabel(limits.minExpiration)}〜${pollDurationLabel(limits.maxExpiration, limits.maxExpiration)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.pollExpiresInSeconds !in limits.minExpiration..limits.maxExpiration)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!limits.confirmed) Text(
                "制限を取得できない項目には標準値を使用しています",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PollSettingRow("複数選択を許可", state.pollMultiple, enabled, viewModel::setPollMultiple)
            PollSettingRow("終了まで結果を非表示", state.pollHideTotals, enabled, viewModel::setPollHideTotals)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::disablePoll, enabled = enabled) { Text("アンケートを削除") }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PollOptionInput(
    value: String,
    onValueChange: (String) -> Unit,
    index: Int,
    maxCharacters: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val count = value.trim().pollCharacterCount()
    val isError = count > maxCharacters
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(top = 6.dp).heightIn(min = 40.dp).semantics {
            contentDescription = "選択肢 ${index + 1}"
            if (isError) error("${maxCharacters}文字以内にしてください")
        },
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color =
            if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { editor ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = editor,
                enabled = enabled,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                isError = isError,
                label = { Text("選択肢 ${index + 1}", style = MaterialTheme.typography.bodySmall) },
                suffix = {
                    Text("$count / $maxCharacters", style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            )
        },
    )
}

@Composable
private fun PollSettingRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
