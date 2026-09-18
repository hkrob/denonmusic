package com.denonmusic.app.heos

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the invariant the whole class exists for: real HEOS playback starting relinquishes bridge-mode
 * ownership of Now Playing, and does so exactly when playback actually started.
 *
 * Only [HeosPlaybackStarter.starting] is exercised here, because every public method is a thin
 * delegate to it - the wire calls themselves are [com.denonmusic.heos.HeosClient]'s own tested
 * territory, and reaching them would need a real socket fake this module doesn't have.
 */
class HeosPlaybackStarterTest {

    private class RecordingClearer : BridgeQueueClearer {
        var clears = 0
        override fun clear() {
            clears++
        }
    }

    @Test
    fun `clears the bridge queue once real playback has started`() = runBlocking {
        val clearer = RecordingClearer()

        val result = HeosPlaybackStarter(clearer).starting { "started" }

        assertEquals("started", result, "the action's own result must pass through")
        assertEquals(1, clearer.clears)
    }

    @Test
    fun `leaves the bridge queue alone when starting playback failed`() = runBlocking {
        // A failed addToQueue never started anything, so clearing here would hand Now Playing away
        // from the bridge track that is still the only thing actually driving the receiver.
        val clearer = RecordingClearer()

        assertFailsWith<IllegalStateException> {
            HeosPlaybackStarter(clearer).starting { throw IllegalStateException("HEOS said no") }
        }

        assertEquals(0, clearer.clears)
    }

    @Test
    fun `clears after the action, never before it`() = runBlocking {
        // Ordering matters: clear() cancels the bridge's in-flight play job, and doing that before
        // the new stream is accepted would leave a window with nothing playing at all.
        val clearer = RecordingClearer()
        var clearedDuringAction = true

        HeosPlaybackStarter(clearer).starting { clearedDuringAction = clearer.clears > 0 }

        assertFalse(clearedDuringAction, "the bridge queue was cleared before playback started")
        assertTrue(clearer.clears == 1)
    }
}
