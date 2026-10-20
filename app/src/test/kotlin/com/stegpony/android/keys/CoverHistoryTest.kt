package com.stegpony.android.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverHistoryTest {
    @Test
    fun fingerprintIsTheFirstSixteenBytesOfSha256() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf8f01cfea414140de5dae2223", CoverHistory.fingerprint("abc".toByteArray()))
        assertNotEquals(CoverHistory.fingerprint(byteArrayOf(1)), CoverHistory.fingerprint(byteArrayOf(2)))
    }
}
