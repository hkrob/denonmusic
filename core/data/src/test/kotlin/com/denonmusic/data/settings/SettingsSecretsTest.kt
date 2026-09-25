package com.denonmusic.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two passwords moved out of plain DataStore into [SecretStore]. These pin the part that can
 * go wrong quietly: an upgrade that reads from the new place without moving what was in the old one
 * would log the user out of their own share and look like a forgotten password.
 */
@ExtendWith(RobolectricExtension::class)
class SettingsSecretsTest {

    private val legacySmbKey = stringPreferencesKey("smb_password")
    private val legacyLanKey = stringPreferencesKey("lan_control_password")

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var secrets: InMemorySecretStore
    private lateinit var repository: SettingsRepository

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "settings_test_" + UUID.randomUUID()
        dataStore = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }
        secrets = InMemorySecretStore()
        repository = SettingsRepository(dataStore, secrets)
    }

    @Test
    fun `a password written before this existed is moved into the secret store`() = runBlocking {
        dataStore.edit {
            it[legacySmbKey] = "nas-password"
            it[legacyLanKey] = "lan-token"
        }

        repository.migrateSecretsOutOfPlainStorage()

        assertEquals("nas-password", secrets.read(SecretStore.SMB_PASSWORD))
        assertEquals("lan-token", secrets.read(SecretStore.LAN_CONTROL_PASSWORD))
        val settings = repository.settings.first()
        assertEquals("nas-password", settings.smbPassword, "the share credentials must survive the move")
        assertEquals("lan-token", settings.lanControlPassword)
    }

    @Test
    fun `the plain copy does not survive the move`() = runBlocking {
        // Leaving it behind would make the whole exercise pointless.
        dataStore.edit { it[legacySmbKey] = "nas-password" }

        repository.migrateSecretsOutOfPlainStorage()

        assertNull(dataStore.data.first()[legacySmbKey])
    }

    @Test
    fun `migrating twice does not wipe a password set since the first time`() = runBlocking {
        dataStore.edit { it[legacySmbKey] = "old" }
        repository.migrateSecretsOutOfPlainStorage()
        repository.setSmbCredentials(host = "nas", share = "music", username = "rob", password = "new")

        repository.migrateSecretsOutOfPlainStorage()

        assertEquals("new", repository.settings.first().smbPassword)
    }

    @Test
    fun `setting a password keeps it out of plain storage`() = runBlocking {
        repository.setSmbCredentials(host = "nas", share = "music", username = "rob", password = "secret")
        repository.setLanControl(enabled = true, password = "token")

        val prefs = dataStore.data.first()
        assertNull(prefs[legacySmbKey], "the SMB password must never be written to DataStore")
        assertNull(prefs[legacyLanKey], "the LAN token must never be written to DataStore")
        val settings = repository.settings.first()
        assertEquals("secret", settings.smbPassword)
        assertEquals("token", settings.lanControlPassword)
        assertEquals("nas", settings.smbHost, "the non-secret fields still belong in DataStore")
    }

    @Test
    fun `a password change reaches an already-collecting settings flow`() = runBlocking {
        // SharedPreferences is not a flow; without the change signal the new value would never
        // reach anything already observing settings.
        repository.setSmbCredentials(host = "nas", share = "music", username = "rob", password = "first")
        assertEquals("first", repository.settings.first().smbPassword)

        repository.setSmbCredentials(host = "nas", share = "music", username = "rob", password = "second")

        assertEquals("second", repository.settings.first().smbPassword)
    }
}
