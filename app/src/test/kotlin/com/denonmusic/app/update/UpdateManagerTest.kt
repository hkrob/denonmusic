package com.denonmusic.app.update

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UpdateManagerTest {

    @Test
    fun `a higher dotted component wins even when the string compares the other way`() {
        assertTrue(UpdateManager.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateManager.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateManager.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun `a leading v is tolerated on either side`() {
        assertTrue(UpdateManager.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateManager.isNewer("0.2.0", "V0.1.0"))
    }

    @Test
    fun `a non-numeric suffix like this project's debug versionNameSuffix is ignored`() {
        assertFalse(UpdateManager.isNewer("0.1.0", "0.1.0-debug"))
        assertTrue(UpdateManager.isNewer("0.2.0", "0.1.0-debug"))
    }

    @Test
    fun `missing trailing components default to zero`() {
        assertTrue(UpdateManager.isNewer("0.2", "0.1.9"))
        assertFalse(UpdateManager.isNewer("0.1", "0.1.0"))
    }
}
