package com.denonmusic.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** Manual override; SSDP discovery is not wired up yet, so this is the only way in for now. */
    val avrHost: String? = null,
    /** The music source the browse screen and queue actions latch onto once auto-detected. */
    val selectedSourceSid: String? = null,
    val lastActiveAt: Long? = null,
)

/**
 * Thin wrapper over one [DataStore]<Preferences>. Everything here is a handful of scalars, so one
 * preferences file is simpler than Room for it and matches what `:core:data` documents in the plan.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            avrHost = prefs[KEY_AVR_HOST],
            selectedSourceSid = prefs[KEY_SELECTED_SID],
            lastActiveAt = prefs[KEY_LAST_ACTIVE_AT],
        )
    }

    suspend fun setAvrHost(host: String) {
        dataStore.edit { it[KEY_AVR_HOST] = host }
    }

    suspend fun setSelectedSourceSid(sid: String) {
        dataStore.edit { it[KEY_SELECTED_SID] = sid }
    }

    suspend fun touchLastActiveAt(atMillis: Long) {
        dataStore.edit { it[KEY_LAST_ACTIVE_AT] = atMillis }
    }

    companion object {
        private val KEY_AVR_HOST = stringPreferencesKey("avr_host")
        private val KEY_SELECTED_SID = stringPreferencesKey("selected_source_sid")
        private val KEY_LAST_ACTIVE_AT = longPreferencesKey("last_active_at")

        const val PREFERENCES_NAME: String = "denonmusic_settings"
    }
}
