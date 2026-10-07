package io.github.ponpokoo.mastodonclient.feature.login

import androidx.browser.customtabs.CustomTabsIntent
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InstanceLoginScreen(
    viewModel: LoginViewModel,
    onBack: (() -> Unit)? = null,
    onAuthenticated: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val callback by OAuthCallbackBus.callback.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.session?.sessionId) {
        if (state.session != null) onAuthenticated()
    }

    LaunchedEffect(state.authorizationUrl) {
        state.authorizationUrl?.let { url ->
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
                viewModel.authorizationUrlOpened()
            } catch (_: ActivityNotFoundException) {
                viewModel.authorizationUrlOpenFailed()
            } catch (_: SecurityException) {
                viewModel.authorizationUrlOpenFailed()
            }
        }
    }

    LaunchedEffect(callback) {
        callback?.let { url ->
            OAuthCallbackBus.consume(url)
            viewModel.completeAuthorization(url)
        }
    }

    InstanceLoginContent(state, viewModel::onInstanceChanged, viewModel::discover,
        viewModel::startAuthorization, onBack)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InstanceLoginContent(
    state: LoginUiState,
    onInstanceChanged: (String) -> Unit,
    onDiscover: () -> Unit,
    onStartAuthorization: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    if (state.session != null) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    val primaryAction = {
        focusManager.clearFocus()
        if (state.instance == null) onDiscover() else onStartAuthorization()
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (onBack != null) "アカウントを追加" else stringResource(R.string.app_name)) },
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("login_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("ログイン", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "アカウントのドメインを入力してください。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = state.instanceInput,
                onValueChange = onInstanceChanged,
                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).testTag("instance_input"),
                label = { Text("サーバーのドメイン") },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                enabled = !state.isLoading,
                isError = state.inputErrorMessage != null,
                supportingText = { Text(state.inputErrorMessage ?: "（https:// も入力できます）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!state.isLoading && state.authorizationUrl == null) primaryAction() }),
            )
            state.instance?.let { instance ->
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${instance.title?.takeIf { it.isNotBlank() } ?: instance.host} に接続できました。",
                    modifier = Modifier.testTag("instance_success"),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(instance.baseUrl, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("confirmed_instance_url"))
                TextButton(
                    onClick = { onInstanceChanged(state.instanceInput); inputFocus.requestFocus() },
                    modifier = Modifier.align(Alignment.End).testTag("change_instance"),
                    enabled = !state.isLoading,
                ) { Text("サーバーを変更") }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .testTag(if (state.instance == null) "discover_button" else "authorize_button"),
                enabled = state.instanceInput.isNotBlank() && !state.isLoading && state.authorizationUrl == null,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isLoading) CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(when (state.operation) {
                        LoginOperation.Discover -> "サーバーを確認中…"
                        LoginOperation.PrepareAuthorization -> "ログインを準備中…"
                        LoginOperation.CompleteAuthorization -> "認証を完了中…"
                        LoginOperation.Logout -> "処理中…"
                        null -> if (state.instance == null) "接続を確認" else "ブラウザでログイン"
                    })
                }
            }
            state.errorMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error"))
            }
        }
    }
}
