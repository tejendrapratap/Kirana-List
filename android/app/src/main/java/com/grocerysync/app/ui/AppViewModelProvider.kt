package com.grocerysync.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.grocerysync.app.AppContainer
import com.grocerysync.app.GroceryApp

/** Builds every ViewModel from the application's [AppContainer] (manual DI). */
object AppViewModelProvider {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val container = container()
            ListViewModel(
                repository = container.repository,
                liveSync = container.liveSync,
                settingsStore = container.settings,
                shopSectionStore = container.shopSections,
                savedStateHandle = createSavedStateHandle(),
            )
        }
        initializer {
            val container = container()
            SettingsViewModel(
                settingsStore = container.settings,
                sheetsClient = container.sheetsClient,
                liveSync = container.liveSync,
                savedStateHandle = createSavedStateHandle(),
            )
        }
    }
}

private fun CreationExtras.container(): AppContainer =
    (checkNotNull(this[APPLICATION_KEY]) { "Application missing from CreationExtras" } as GroceryApp).container
