package io.github.ponpokoo.mastodonclient.feature.login

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MastodonInstance
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.InstanceRepository
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelTestBase
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest : ScreenViewModelTestBase() {
    private val instance = MastodonInstance("one.example", "https://one.example", "One", null, 500, 4)
    private class Auth : AuthRepository {
        var current: AccountSession? = testAccount
        var restoreCalls = 0
        val preparationCalls = mutableListOf<String>()
        var completionCalls = 0
        var prepare: suspend (String) -> Result<String> = { Result.success("https://one.example/oauth/authorize") }
        var complete: suspend () -> Result<AccountSession> = { Result.success(testAccount) }
        override suspend fun createAuthorizationUrl(instanceUrl: String): Result<String> {
            preparationCalls += instanceUrl
            return prepare(instanceUrl)
        }
        override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> {
            completionCalls++
            return complete()
        }
        override suspend fun restoreSession(): AccountSession? { restoreCalls++; return current }
        override suspend fun logout() { current = null }
    }
    private fun model(auth: Auth = Auth(), discovery: suspend (String) -> Result<MastodonInstance> = { Result.success(instance) }) =
        own(LoginViewModel(object : InstanceRepository {
            override suspend fun discover(input: String) = discovery(input)
        }, auth, restoreExistingSession = false))

    @Test fun editingConfirmedInputInvalidatesDestinationAndRequiresAnotherCheck() = runTest(dispatcher) {
        val auth = Auth()
        val model = model(auth)
        model.onInstanceChanged("https://one.example/")
        model.discover(); runCurrent()
        assertEquals("https://one.example/", model.uiState.value.instanceInput)
        assertEquals(instance, model.uiState.value.instance)
        model.startAuthorization(); runCurrent()
        assertEquals(listOf(instance.baseUrl), auth.preparationCalls)
        assertNotNull(model.uiState.value.authorizationUrl)
        model.onInstanceChanged("two.example")
        assertNull(model.uiState.value.instance)
        assertNull(model.uiState.value.authorizationUrl)
        model.startAuthorization(); runCurrent()
        assertEquals(1, auth.preparationCalls.size)
    }

    @Test fun invalidInputNeverConnectsAndNetworkFailureCanBeRetriedWithoutExposingDetails() = runTest(dispatcher) {
        var requests = 0
        val model = model(discovery = {
            requests++
            if (requests == 1) Result.failure(IOException("internal diagnostic")) else Result.success(instance)
        })
        model.onInstanceChanged("https://one.example/path")
        model.discover(); runCurrent()
        assertEquals(0, requests)
        assertNotNull(model.uiState.value.inputErrorMessage)
        assertNull(model.uiState.value.errorMessage)
        model.onInstanceChanged("one.example")
        model.discover(); runCurrent()
        assertNull(model.uiState.value.inputErrorMessage)
        assertNotNull(model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.errorMessage!!.contains("internal diagnostic"))
        assertFalse(model.uiState.value.isLoading)
        model.discover(); runCurrent()
        assertEquals(instance, model.uiState.value.instance)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun repeatedActionsBeforeDispatchOrWhileWaitingAreSingleFlight() = runTest(dispatcher) {
        val discovery = CompletableDeferred<MastodonInstance>()
        val preparation = CompletableDeferred<String>()
        val completion = CompletableDeferred<AccountSession>()
        var discoveryCalls = 0
        val auth = Auth().apply {
            prepare = { Result.success(preparation.await()) }
            complete = { Result.success(completion.await()) }
        }
        val model = model(auth) { discoveryCalls++; Result.success(discovery.await()) }
        model.onInstanceChanged("one.example")
        model.discover(); model.discover(); runCurrent()
        assertEquals(1, discoveryCalls)
        assertEquals(LoginOperation.Discover, model.uiState.value.operation)
        model.onInstanceChanged("two.example")
        assertEquals("one.example", model.uiState.value.instanceInput)
        discovery.complete(instance); runCurrent()
        model.startAuthorization(); model.startAuthorization(); runCurrent()
        assertEquals(1, auth.preparationCalls.size)
        assertEquals(LoginOperation.PrepareAuthorization, model.uiState.value.operation)
        preparation.complete("https://one.example/oauth/authorize"); runCurrent()
        model.startAuthorization(); runCurrent()
        assertEquals(1, auth.preparationCalls.size)
        model.authorizationUrlOpened()
        model.completeAuthorization("synthetic-callback"); model.completeAuthorization("synthetic-callback"); runCurrent()
        assertEquals(1, auth.completionCalls)
        assertEquals(LoginOperation.CompleteAuthorization, model.uiState.value.operation)
        completion.complete(testAccount); runCurrent()
        assertEquals(testAccount, model.uiState.value.session)
        assertFalse(model.uiState.value.isLoading)
        assertNull(model.uiState.value.operation)
    }

    @Test fun preparationAndBrowserFailuresKeepConfirmedServerForRetry() = runTest(dispatcher) {
        val auth = Auth().apply { prepare = { Result.failure(IOException("internal")) } }
        val model = model(auth)
        model.onInstanceChanged("one.example"); model.discover(); runCurrent()
        model.startAuthorization(); runCurrent()
        assertEquals(instance, model.uiState.value.instance)
        assertNotNull(model.uiState.value.errorMessage)
        assertNull(model.uiState.value.inputErrorMessage)
        auth.prepare = { Result.success("https://one.example/oauth/authorize") }
        model.startAuthorization(); runCurrent()
        assertNull(model.uiState.value.errorMessage)
        model.authorizationUrlOpenFailed()
        assertNull(model.uiState.value.authorizationUrl)
        assertEquals(instance, model.uiState.value.instance)
        assertNotNull(model.uiState.value.errorMessage)
        model.startAuthorization(); runCurrent()
        assertNotNull(model.uiState.value.authorizationUrl)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun failedCompletionCanRestartAuthorizationAndAccountAdditionDoesNotRestoreExistingSession() = runTest(dispatcher) {
        val auth = Auth().apply { complete = { Result.failure(IllegalStateException("cancelled")) } }
        val model = model(auth)
        runCurrent()
        assertEquals(0, auth.restoreCalls)
        assertNull(model.uiState.value.session)
        model.onInstanceChanged("one.example"); model.discover(); runCurrent()
        model.completeAuthorization("synthetic-callback"); runCurrent()
        assertEquals(instance, model.uiState.value.instance)
        assertNotNull(model.uiState.value.errorMessage)
        assertEquals(testAccount, auth.current)
        model.startAuthorization(); runCurrent()
        assertNotNull(model.uiState.value.authorizationUrl)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun leavingDuringDiscoveryCancelsWorkWithoutShowingFailure() = runTest(dispatcher) {
        val response = CompletableDeferred<MastodonInstance>()
        val model = model(discovery = { withContext(NonCancellable) { Result.success(response.await()) } })
        model.onInstanceChanged("one.example"); model.discover(); runCurrent()
        model.viewModelScope.cancel(); runCurrent()
        response.complete(instance); runCurrent()
        assertNull(model.uiState.value.instance)
        assertNull(model.uiState.value.errorMessage)
    }
}
