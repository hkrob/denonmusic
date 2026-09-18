package com.denonmusic.app.avr

import com.denonmusic.avr.AvrClient
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "persist the chosen policy, then apply it live" sequence that used to be spelled out
 * separately in [AvrViewModel], [com.denonmusic.app.lancontrol.LanControlManager] and
 * [com.denonmusic.app.player.PlayerViewModel] - each with its own answer for what a missing or
 * corrupted stored value means: default to [BitPerfectPolicy.Off], silently apply nothing at all, or
 * echo whatever raw string happened to be in the DataStore. [BitPerfectPolicy.fromStoredName] is now
 * the one place that question gets answered, so those three can't drift again.
 */
@Singleton
class BitPerfectPolicyController @Inject constructor(private val settings: SettingsRepository) {

    /** What's currently persisted, resolved through [BitPerfectPolicy.fromStoredName]. */
    suspend fun storedPolicy(): BitPerfectPolicy =
        BitPerfectPolicy.fromStoredName(runCatching { settings.settings.first() }.getOrNull()?.bitPerfectPolicy)

    /**
     * Persists [policy] and, if [client] is available, applies it to the receiver immediately - the
     * user choosing Auto Direct/Pure Direct while something is already playing is a request to switch
     * sound mode now, not just a preference for the next track.
     */
    suspend fun persistAndApply(client: AvrClient?, policy: BitPerfectPolicy) {
        settings.setBitPerfectPolicy(policy.name)
        client?.let { runCatching { it.applyBitPerfectPolicy(policy) } }
    }

    /**
     * Applies whatever is currently stored - [BitPerfectPolicy.Off] if nothing valid ever was - to
     * [client]. [AvrClient.applyBitPerfectPolicy] is itself a no-op for [BitPerfectPolicy.Off], so this
     * never sends anything to the receiver on a fresh install; it exists so "nothing stored yet" and
     * "explicitly set to Off" take the same code path instead of two different ones that happen to
     * agree today.
     */
    suspend fun applyStored(client: AvrClient?) {
        client ?: return
        runCatching { client.applyBitPerfectPolicy(storedPolicy()) }
    }
}
