package com.denonmusic.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the two real secrets live: the SMB share's password and the LAN control token.
 *
 * Everything else this app stores is a LAN address or a preference, and sits in plain DataStore
 * quite happily. These two are a NAS account's credentials and the key to the control API, so they
 * are kept apart and encrypted with a key held in the device's own keystore - which is the part
 * that matters, because it is hardware-backed where the hardware allows and never leaves it.
 *
 * [changes] exists so the settings flow can re-emit on a write: a `SharedPreferences` is not a
 * flow, and without this a password change would not reach anything already collecting settings.
 */
interface SecretStore {

    val changes: StateFlow<Long>

    fun read(name: String): String?

    fun write(name: String, value: String?)

    companion object {
        const val SMB_PASSWORD: String = "smb_password"
        const val LAN_CONTROL_PASSWORD: String = "lan_control_password"
    }
}

/**
 * The real one, backed by `EncryptedSharedPreferences`.
 *
 * Falls back to plain `SharedPreferences` if the keystore will not produce a key. That is rare -
 * a device whose keystore is broken or a profile mid-migration - but the alternative is throwing
 * during construction and taking the whole app with it over a stored password, which is a much
 * worse failure than the one being guarded against. The fallback is logged, not silent.
 */
class EncryptedSecretStore(context: Context) : SecretStore {

    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse { e ->
        Log.e(TAG, "Keystore unavailable; falling back to unencrypted storage for secrets", e)
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    override fun read(name: String): String? = prefs.getString(name, null)?.takeIf { it.isNotEmpty() }

    override fun write(name: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrEmpty()) remove(name) else putString(name, value)
        }.apply()
        _changes.update { it + 1 }
    }

    private companion object {
        const val TAG = "SecretStore"
        const val FILE_NAME = "denonmusic_secrets"
        const val FALLBACK_FILE_NAME = "denonmusic_secrets_plain"
    }
}

/** For tests and for [SettingsRepository]'s default, so neither needs a `Context`. */
class InMemorySecretStore : SecretStore {

    private val values = mutableMapOf<String, String>()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    override fun read(name: String): String? = values[name]

    override fun write(name: String, value: String?) {
        if (value.isNullOrEmpty()) values.remove(name) else values[name] = value
        _changes.update { it + 1 }
    }
}
