package io.github.ponpokoo.mastodonclient.feature.web

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppWebScreen(url: String, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val navigateBack = {
        if (canGoBack && webView?.canGoBack() == true) webView?.goBack() else onBack()
        Unit
    }
    BackHandler(onBack = navigateBack)
    val safeUrl = url.takeIf {
        val uri = it.toUri()
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
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
}
