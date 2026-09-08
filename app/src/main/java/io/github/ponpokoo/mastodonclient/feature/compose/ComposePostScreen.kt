package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposePostScreen(
    viewModel: ComposePostViewModel,
    isReply: Boolean,
    onClose: () -> Unit,
    onPosted: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.posted) { if (state.posted) onPosted() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isReply) "返信" else "新規投稿") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "閉じる") }
                },
                actions = {
                    Button(onClick = viewModel::post, enabled = state.text.isNotBlank() && !state.isPosting) {
                        if (state.isPosting) CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                        else Text(if (isReply) "返信" else "投稿")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("compose_post")) {
            TextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text(if (isReply) "返信を入力" else "いまどうしてる？") },
                supportingText = { Text("${state.text.length}/500") },
            )
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
