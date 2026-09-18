package com.denonmusic.avr

import kotlin.test.Test
import kotlin.test.assertEquals

class BitPerfectPolicyFromStoredNameTest {

    @Test
    fun `defaults to Off when nothing has ever been stored`() {
        assertEquals(BitPerfectPolicy.Off, BitPerfectPolicy.fromStoredName(null))
    }

    @Test
    fun `defaults to Off on a name that matches no entry`() {
        assertEquals(BitPerfectPolicy.Off, BitPerfectPolicy.fromStoredName("Garbled"))
    }

    @Test
    fun `defaults to Off on a blank stored value`() {
        assertEquals(BitPerfectPolicy.Off, BitPerfectPolicy.fromStoredName(""))
    }

    @Test
    fun `resolves a valid stored name to its entry`() {
        assertEquals(BitPerfectPolicy.AutoPureDirect, BitPerfectPolicy.fromStoredName("AutoPureDirect"))
    }
}
