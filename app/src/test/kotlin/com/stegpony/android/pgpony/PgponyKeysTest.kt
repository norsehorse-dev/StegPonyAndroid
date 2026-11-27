package com.stegpony.android.pgpony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PgponyKeysTest {
    private val fpA = "A".repeat(40)
    private val fpB = "B".repeat(64)
    private fun point(b: Int) = ByteArray(32) { b.toByte() }

    @Test
    fun readsTheParallelArrays() {
        val keys = PgponyKeys.parse(
            subkeyFingerprints = arrayOf(fpA, fpB),
            primaryFingerprints = arrayOf("C".repeat(40), "D".repeat(64)),
            points = point(1) + point(2),
            labels = arrayOf("First <first@example.org>", "Second"),
            onCard = booleanArrayOf(false, true),
        )

        assertEquals(2, keys.size)
        assertEquals(fpA, keys[0].subkeyFingerprint)
        assertEquals("C".repeat(40), keys[0].primaryFingerprint)
        assertEquals("First <first@example.org>", keys[0].label)
        assertFalse(keys[0].onCard)
        assertArrayEquals(point(1), keys[0].point)
        assertEquals(fpB, keys[1].subkeyFingerprint)
        assertTrue(keys[1].onCard)
        assertArrayEquals(point(2), keys[1].point)
    }

    @Test
    fun anEmptyListIsNoKeys() {
        assertTrue(PgponyKeys.parse(emptyArray(), emptyArray(), ByteArray(0), emptyArray(), BooleanArray(0)).isEmpty())
        assertTrue(PgponyKeys.parse(null, null, null, null, null).isEmpty())
    }

    @Test
    fun pointsThatDontMatchTheFingerprintsAreRefused() {
        assertThrows(PgponyException.BadReply::class.java) {
            PgponyKeys.parse(arrayOf(fpA, fpB), null, point(1), null, null)
        }
        assertThrows(PgponyException.BadReply::class.java) {
            PgponyKeys.parse(arrayOf(fpA), null, ByteArray(31), null, null)
        }
        assertThrows(PgponyException.BadReply::class.java) {
            PgponyKeys.parse(null, null, point(1), null, null)
        }
    }

    @Test
    fun aBlankFingerprintIsRefused() {
        assertThrows(PgponyException.BadReply::class.java) {
            PgponyKeys.parse(arrayOf(" "), null, point(1), null, null)
        }
    }

    @Test
    fun missingDescriptionsAreLeftEmpty() {
        val keys = PgponyKeys.parse(arrayOf(fpA, fpB), arrayOf("C".repeat(40)), point(1) + point(2), null, booleanArrayOf(true))
        assertEquals("", keys[0].label)
        assertTrue(keys[0].onCard)
        assertEquals("", keys[1].primaryFingerprint)
        assertFalse(keys[1].onCard)
    }
}
