package com.denonmusic.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    /**
     * `BitPerfectPolicy.name` from `:core:avr` - stored as a raw string here rather than the enum
     * itself, since `:core:data` has no dependency on `:core:avr` and shouldn't gain one just for
     * this. The app layer round-trips it with `valueOf`/`.name`.
     */
    val bitPerfectPolicy: String? = null,
    /** Confirmed `SINET` on the AVR-X4500H probed for this project; kept overridable per receiver. */
    val avrInputMnemonic: String = DEFAULT_AVR_INPUT_MNEMONIC,
    val smbHost: String? = null,
    val smbShare: String? = null,
    val smbUsername: String? = null,
    val smbPassword: String? = null,
    /** The SMB file browser's last folder, `/`-joined, so reopening it doesn't reset to the root. */
    val smbBrowsePath: String? = null,
    /** Screen never sleeps while the app is in the foreground - useful mounted next to the AVR. */
    val keepScreenOn: Boolean = false,
    val autoDimEnabled: Boolean = false,
    /** Idle time before the screen dims, once [autoDimEnabled]. */
    val autoDimAfterSeconds: Int = DEFAULT_AUTO_DIM_AFTER_SECONDS,
    /** Brightness to dim to, 1-100 - never 0, so the screen never goes fully black and unreadable. */
    val autoDimBrightnessPercent: Int = DEFAULT_AUTO_DIM_BRIGHTNESS_PERCENT,
    /** Whether the LAN control HTTP API should run. A blank [lanControlPassword] keeps it off regardless. */
    val lanControlEnabled: Boolean = false,
    /** Shared secret a LAN client must send back (`X-Lan-Control-Token` header or `?token=`) to use the API. */
    val lanControlPassword: String? = null,
) {
    companion object {
        const val DEFAULT_AVR_INPUT_MNEMONIC: String = "NET"
        const val DEFAULT_AUTO_DIM_AFTER_SECONDS: Int = 60
        const val DEFAULT_AUTO_DIM_BRIGHTNESS_PERCENT: Int = 10
    }
}

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
            bitPerfectPolicy = prefs[KEY_BIT_PERFECT_POLICY],
            avrInputMnemonic = prefs[KEY_AVR_INPUT_MNEMONIC] ?: AppSettings.DEFAULT_AVR_INPUT_MNEMONIC,
            smbHost = prefs[KEY_SMB_HOST],
            smbShare = prefs[KEY_SMB_SHARE],
            smbUsername = prefs[KEY_SMB_USERNAME],
            smbPassword = prefs[KEY_SMB_PASSWORD],
            smbBrowsePath = prefs[KEY_SMB_BROWSE_PATH],
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: false,
            autoDimEnabled = prefs[KEY_AUTO_DIM_ENABLED] ?: false,
            autoDimAfterSeconds = prefs[KEY_AUTO_DIM_AFTER_SECONDS] ?: AppSettings.DEFAULT_AUTO_DIM_AFTER_SECONDS,
            autoDimBrightnessPercent = prefs[KEY_AUTO_DIM_BRIGHTNESS_PERCENT] ?: AppSettings.DEFAULT_AUTO_DIM_BRIGHTNESS_PERCENT,
            lanControlEnabled = prefs[KEY_LAN_CONTROL_ENABLED] ?: false,
            lanControlPassword = prefs[KEY_LAN_CONTROL_PASSWORD],
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

    suspend fun setBitPerfectPolicy(policyName: String) {
        dataStore.edit { it[KEY_BIT_PERFECT_POLICY] = policyName }
    }

    suspend fun setAvrInputMnemonic(mnemonic: String) {
        dataStore.edit { it[KEY_AVR_INPUT_MNEMONIC] = mnemonic }
    }

    suspend fun setSmbCredentials(host: String, share: String, username: String, password: String) {
        dataStore.edit {
            it[KEY_SMB_HOST] = host
            it[KEY_SMB_SHARE] = share
            it[KEY_SMB_USERNAME] = username
            it[KEY_SMB_PASSWORD] = password
        }
    }

    suspend fun setSmbBrowsePath(path: String) {
        dataStore.edit { it[KEY_SMB_BROWSE_PATH] = path }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setAutoDim(enabled: Boolean, afterSeconds: Int, brightnessPercent: Int) {
        dataStore.edit {
            it[KEY_AUTO_DIM_ENABLED] = enabled
            it[KEY_AUTO_DIM_AFTER_SECONDS] = afterSeconds.coerceAtLeast(1)
            it[KEY_AUTO_DIM_BRIGHTNESS_PERCENT] = brightnessPercent.coerceIn(1, 100)
        }
    }

    suspend fun setLanControl(enabled: Boolean, password: String) {
        dataStore.edit {
            it[KEY_LAN_CONTROL_ENABLED] = enabled
            it[KEY_LAN_CONTROL_PASSWORD] = password
        }
    }

    companion object {
        private val KEY_AVR_HOST = stringPreferencesKey("avr_host")
        private val KEY_SELECTED_SID = stringPreferencesKey("selected_source_sid")
        private val KEY_LAST_ACTIVE_AT = longPreferencesKey("last_active_at")
        private val KEY_BIT_PERFECT_POLICY = stringPreferencesKey("bit_perfect_policy")
        private val KEY_AVR_INPUT_MNEMONIC = stringPreferencesKey("avr_input_mnemonic")
        private val KEY_SMB_HOST = stringPreferencesKey("smb_host")
        private val KEY_SMB_SHARE = stringPreferencesKey("smb_share")
        private val KEY_SMB_USERNAME = stringPreferencesKey("smb_username")
        private val KEY_SMB_PASSWORD = stringPreferencesKey("smb_password")
        private val KEY_SMB_BROWSE_PATH = stringPreferencesKey("smb_browse_path")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_AUTO_DIM_ENABLED = booleanPreferencesKey("auto_dim_enabled")
        private val KEY_AUTO_DIM_AFTER_SECONDS = intPreferencesKey("auto_dim_after_seconds")
        private val KEY_AUTO_DIM_BRIGHTNESS_PERCENT = intPreferencesKey("auto_dim_brightness_percent")
        private val KEY_LAN_CONTROL_ENABLED = booleanPreferencesKey("lan_control_enabled")
        private val KEY_LAN_CONTROL_PASSWORD = stringPreferencesKey("lan_control_password")

        const val PREFERENCES_NAME: String = "denonmusic_settings"
    }
}
