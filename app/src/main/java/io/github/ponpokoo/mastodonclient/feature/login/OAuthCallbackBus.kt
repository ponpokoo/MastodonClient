package io.github.ponpokoo.mastodonclient.feature.login

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OAuthCallbackBus {
    private val _callback = MutableStateFlow<String?>(null)
    val callback = _callback.asStateFlow()

    fun accept(uri: Uri?) {
        if (uri?.scheme == "io.github.ponpokoo.mastodonclient") {
            _callback.value = uri.toString()
        }
    }

    fun consume(value: String) {
        _callback.compareAndSet(value, null)
    }
}
