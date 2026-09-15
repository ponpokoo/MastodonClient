package io.github.ponpokoo.mastodonclient.feature.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppWebScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var findDialogVisible by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    val navigateBack = {
        if (canGoBack && webView?.canGoBack() == true) webView?.goBack() else onBack()
        Unit
    }
    BackHandler(onBack = navigateBack)
    val safeUrl = url.takeIf {
        val uri = it.toUri()
        uri.scheme == "https" && !uri.host.isNullOrBlank()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(safeUrl?.toUri()?.host.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { safeUrl?.let { shareUrl(context, it) } }) {
                        Icon(Icons.Outlined.Share, contentDescription = "共有")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "その他の操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("ブラウザで開く") },
                            onClick = {
                                menuExpanded = false
                                safeUrl?.toUri()?.let { openExternalUrl(context, it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("アプリで開く") },
                            onClick = {
                                menuExpanded = false
                                safeUrl?.toUri()?.let { openWithAppChooser(context, it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("URLをコピー") },
                            onClick = {
                                menuExpanded = false
                                safeUrl?.let { copyUrl(context, it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("再読み込み") },
                            onClick = {
                                menuExpanded = false
                                webView?.reload()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("ページ内検索") },
                            onClick = {
                                menuExpanded = false
                                findDialogVisible = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (safeUrl == null) {
            Text("安全でないURLのため表示できません", Modifier.padding(padding).padding(24.dp))
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                        }
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                val destination = request.url.toString()
                                val destinationUri = destination.toUri()
                                return when (destinationUri.scheme) {
                                    "https" -> false
                                    "http" -> {
                                        // WebView does not permit cleartext traffic. Delegate HTTP-only sites
                                        // to the user's browser instead of weakening the app-wide network policy.
                                        openExternalUrl(context, destinationUri)
                                        true
                                    }
                                    "intent" -> {
                                        openIntentUrl(context, view, destination)
                                        true
                                    }
                                    else -> {
                                        openExternalUrl(context, destinationUri)
                                        true
                                    }
                                }
                            }

                            override fun onPageFinished(view: WebView, loadedUrl: String?) {
                                canGoBack = view.canGoBack()
                            }
                        }
                        loadUrl(safeUrl)
                        webView = this
                    }
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.destroy()
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    if (findDialogVisible) {
        AlertDialog(
            onDismissRequest = { findDialogVisible = false },
            title = { Text("ページ内検索") },
            text = {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        webView?.findAllAsync(it)
                    },
                    singleLine = true,
                    label = { Text("検索語句") },
                )
            },
            confirmButton = {
                TextButton(onClick = { webView?.findNext(true) }) {
                    Text("次へ")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    webView?.clearMatches()
                    findDialogVisible = false
                }) {
                    Text("閉じる")
                }
            },
        )
    }
}

private fun openIntentUrl(context: android.content.Context, view: WebView, url: String) {
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
    val fallbackUrl = intent?.getStringExtra("browser_fallback_url")?.toUri()
    if (intent != null) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Use the site's HTTPS fallback when the target app is unavailable.
        } catch (_: SecurityException) {
            // A page must not be able to launch a non-exported activity.
        }
    }
    when (fallbackUrl?.scheme) {
        "https" -> view.loadUrl(fallbackUrl.toString())
        "http" -> openExternalUrl(context, fallbackUrl)
    }
}

private fun openExternalUrl(context: android.content.Context, uri: android.net.Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun shareUrl(context: android.content.Context, url: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url),
            "共有",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun openWithAppChooser(context: android.content.Context, uri: android.net.Uri) {
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "アプリで開く")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun copyUrl(context: android.content.Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("URL", url))
}
