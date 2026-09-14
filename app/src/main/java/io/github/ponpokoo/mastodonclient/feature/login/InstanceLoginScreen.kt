package io.github.ponpokoo.mastodonclient.feature.login

import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InstanceLoginScreen(
    viewModel: LoginViewModel,
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
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
            viewModel.authorizationUrlOpened()
        }
    }

    LaunchedEffect(callback) {
        callback?.let { url ->
            OAuthCallbackBus.consume(url)
            viewModel.completeAuthorization(url)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            state.session?.let { session ->
                AuthenticatedAccount(
                    displayName = session.displayName,
                    username = session.username,
                    instanceUrl = session.instanceUrl,
                    avatarUrl = session.avatarUrl,
                    onLogout = viewModel::logout,
                )
                return@Column
            }

            Text("利用するインスタンス", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "例: mastodon.social",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = state.instanceInput,
                onValueChange = viewModel::onInstanceChanged,
                modifier = Modifier.fillMaxWidth().testTag("instance_input"),
                label = { Text("インスタンスのドメイン") },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                enabled = !state.isLoading,
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { message -> ({ Text(message) }) },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::discover,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discover_button"),
                enabled = state.instanceInput.isNotBlank() && !state.isLoading,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("接続を確認")
                }
            }
            state.instance?.let { instance ->
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${instance.title ?: instance.host} に接続できました。",
                    modifier = Modifier.testTag("instance_success"),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::startAuthorization,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                ) {
                    Text("ブラウザでログイン")
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedAccount(
    displayName: String,
    username: String,
    instanceUrl: String,
    avatarUrl: String,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(88.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text("ログイン完了", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(displayName.ifBlank { username }, style = MaterialTheme.typography.titleLarge)
        Text("@$username", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(instanceUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLogout) { Text("ログアウト") }
    }
}
