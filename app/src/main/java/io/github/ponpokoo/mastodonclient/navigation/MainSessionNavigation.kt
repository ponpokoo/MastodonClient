package io.github.ponpokoo.mastodonclient.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel

/** A restored child screen can request this model before the home screen has been composed. */
@Composable
internal fun mainSessionViewModel(owner: ViewModelStoreOwner, factory: ViewModelProvider.Factory): MainSessionViewModel =
    viewModel(viewModelStoreOwner = owner, factory = factory)
