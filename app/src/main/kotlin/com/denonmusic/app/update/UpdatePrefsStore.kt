package com.denonmusic.app.update

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "update_prefs"
private const val KEY_FREQUENCY = "check_frequency"
private const val KEY_LATEST_VERSION = "latest_version"
private const val KEY_LATEST_APK_URL = "latest_apk_url"
private const val KEY_LATEST_NOTES = "latest_notes"
private const val KEY_LATEST_SIZE = "latest_size"

/** Plain `SharedPreferences`, not the app's own DataStore-backed [com.denonmusic.data.settings.SettingsRepository]
 *  - this is disposable cache (a background check's result, a UI preference), not durable configuration. */
class UpdatePrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var frequency: UpdateCheckFrequency
        get() = UpdateCheckFrequency.fromName(prefs.getString(KEY_FREQUENCY, null) ?: "")
        set(value) = prefs.edit { putString(KEY_FREQUENCY, value.name) }

    fun saveLatestRelease(release: ReleaseInfo) {
        prefs.edit {
            putString(KEY_LATEST_VERSION, release.versionName)
            putString(KEY_LATEST_APK_URL, release.apkUrl)
            putString(KEY_LATEST_NOTES, release.notes)
            putLong(KEY_LATEST_SIZE, release.apkSizeBytes)
        }
    }

    fun loadLatestRelease(): ReleaseInfo? {
        val version = prefs.getString(KEY_LATEST_VERSION, null) ?: return null
        val url = prefs.getString(KEY_LATEST_APK_URL, null) ?: return null
        return ReleaseInfo(
            versionName = version,
            apkUrl = url,
            notes = prefs.getString(KEY_LATEST_NOTES, "") ?: "",
            apkSizeBytes = prefs.getLong(KEY_LATEST_SIZE, 0L),
        )
    }

    fun clearLatestRelease() {
        prefs.edit {
            remove(KEY_LATEST_VERSION)
            remove(KEY_LATEST_APK_URL)
            remove(KEY_LATEST_NOTES)
            remove(KEY_LATEST_SIZE)
        }
    }
}
