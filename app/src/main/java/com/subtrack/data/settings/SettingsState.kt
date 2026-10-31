package com.subtrack.data.settings

import com.subtrack.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/** Loading carries no defaults: consumers cannot accidentally treat an unlocked default as persisted settings. */
sealed interface SettingsState {
    data object Loading : SettingsState
    data class Loaded(val settings: AppSettings) : SettingsState
}

class LoadedSettings {
    private val mutable = MutableStateFlow<SettingsState>(SettingsState.Loading)
    val state: StateFlow<SettingsState> = mutable
    fun publish(settings: AppSettings) { mutable.value = SettingsState.Loaded(settings) }
    fun current(): AppSettings = (state.value as SettingsState.Loaded).settings
    suspend fun await(): AppSettings = state.filterIsInstance<SettingsState.Loaded>().first().settings
}

/** Shared with the Compose gate; cancellation, recreation and backgrounding never grant access. */
fun SettingsState.permitsContent(authenticated: Boolean): Boolean =
    this is SettingsState.Loaded && (!settings.appLockEnabled || authenticated)
