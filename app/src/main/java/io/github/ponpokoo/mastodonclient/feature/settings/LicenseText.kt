package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.AppLicenseNotice
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicenseTextScreen(viewModel: LicensesViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val blocks = remember(state.notices) { licenseTextBlocks(state.notices) }
    LaunchedEffect(viewModel) { viewModel.load() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("ライセンス") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("license_document")) {
            if (state.loading) item { Text("読み込み中…", Modifier.padding(20.dp)) }
            if (state.failed) item {
                Text("ライセンスを読み込めませんでした。", Modifier.padding(horizontal = 20.dp))
                TextButton(onClick = viewModel::load) { Text("再試行") }
            }
            licenseTextItems(blocks)
        }
    }
}

internal fun LazyListScope.licenseTextItems(blocks: List<LicenseTextBlock>) {
    items(blocks, key = { it.id }) { block ->
        Text(
            block.text,
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag(block.id),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal data class LicenseTextBlock(val id: String, val text: String)

// Identical documents are shown once, with every associated component named.
internal fun licenseTextBlocks(notices: List<AppLicenseNotice>): List<LicenseTextBlock> = buildList {
    notices.groupBy { it.text }.values.forEach { group ->
        val id = group.first().id
        val names = group.joinToString("\n") { notice ->
            listOf(notice.title, notice.version, notice.license)
                .filter { it.isNotBlank() }.joinToString(" · ")
        }
        licenseTextChunks("\n$names\n\n").forEachIndexed { index, text ->
            add(LicenseTextBlock("license_names_${id}_$index", text))
        }
        licenseTextChunks(group.first().text).forEachIndexed { index, text ->
            add(LicenseTextBlock("license_text_${id}_$index", text))
        }
    }
}

// Keep every character and Unicode pair while staying within Android's text layout limits.
internal fun licenseTextChunks(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 4000, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start) end = newline + 1
            else if (text[end - 1].isHighSurrogate()) end--
        }
        add(text.substring(start, end))
        start = end
    }
}
