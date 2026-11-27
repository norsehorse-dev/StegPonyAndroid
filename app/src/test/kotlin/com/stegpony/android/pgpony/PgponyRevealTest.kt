package com.stegpony.android.pgpony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.stegpony_ffi.DeviceException

class PgponyRevealTest {
    private fun point(b: Int) = ByteArray(32) { b.toByte() }

    @Test
    fun recordingNotesEachPointOnceInOrder() {
        val device = RecordingKeyDevice("age1test")
        device.agree(point(1))
        device.agree(point(2))
        device.agree(point(1))

        assertEquals("age1test", device.recipient())
        assertEquals(2, device.points.size)
        assertArrayEquals(point(1), device.points[0])
        assertArrayEquals(point(2), device.points[1])
    }

    @Test
    fun recordingKeepsItsOwnCopyOfAPoint() {
        val device = RecordingKeyDevice("age1test")
        val p = point(5)
        device.agree(p)
        p.fill(0)
        assertArrayEquals(point(5), device.points[0])
    }

    @Test
    fun recordingAnswersWithRandomBytesNotAnAgreement() {
        val device = RecordingKeyDevice("age1test")
        val a = device.agree(point(1))
        val b = device.agree(point(1))
        assertEquals(32, a.size)
        // Two random answers for the same point: nothing was derived from it.
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun recordingNeverAnswersAllZeros() {
        // All zeros is how the core reads "no agreement", which would end the pass early.
        val device = RecordingKeyDevice("age1test", fill = { it.fill(0) })
        val answer = device.agree(point(1))
        assertTrue(answer.any { it != 0.toByte() })
    }

    @Test
    fun agreedAnswersEachPointFromTheBatch() {
        val points = listOf(point(1), point(2))
        val secrets = listOf(point(0x11), point(0x22))
        val device = AgreedKeyDevice("age1test", points, secrets)

        assertArrayEquals(point(0x22), device.agree(point(2)))
        assertArrayEquals(point(0x11), device.agree(point(1)))
        assertEquals("age1test", device.recipient())
    }

    @Test
    fun agreedFailsOnAPointThatWasNotSent() {
        val device = AgreedKeyDevice("age1test", listOf(point(1)), listOf(point(0x11)))
        assertThrows(DeviceException.Failed::class.java) { device.agree(point(9)) }
    }

    @Test
    fun agreedWipesItsSecrets() {
        val secret = point(0x11)
        val device = AgreedKeyDevice("age1test", listOf(point(1)), listOf(secret))
        val handedOut = device.agree(point(1))
        device.wipe()

        assertArrayEquals(ByteArray(32), secret)
        // The copy the core got is the core's to wipe.
        assertArrayEquals(point(0x11), handedOut)
    }

    @Test
    fun agreedNeedsOneSecretPerPoint() {
        assertThrows(IllegalArgumentException::class.java) {
            AgreedKeyDevice("age1test", listOf(point(1), point(2)), listOf(point(0x11)))
        }
    }

    @Test
    fun twoPointsGoInOneCall() {
        val chunks = PgponyBatch.chunks(listOf(point(1), point(2)), 32)
        assertEquals(1, chunks.size)
        assertArrayEquals(point(1) + point(2), chunks[0])
    }

    @Test
    fun morePointsThanACallTakesAreSplit() {
        val points = (1..70).map { point(it) }
        val chunks = PgponyBatch.chunks(points, 32)
        assertEquals(listOf(32 * 32, 32 * 32, 6 * 32), chunks.map { it.size })
        assertArrayEquals(point(33), chunks[1].copyOfRange(0, 32))
        assertArrayEquals(point(70), chunks[2].copyOfRange(5 * 32, 6 * 32))
    }

    @Test
    fun noPointsMeansNoCall() {
        assertTrue(PgponyBatch.chunks(emptyList(), 32).isEmpty())
    }

    @Test
    fun chunksRefuseAPointOfTheWrongLength() {
        assertThrows(IllegalArgumentException::class.java) { PgponyBatch.chunks(listOf(ByteArray(31)), 32) }
    }

    @Test
    fun secretsSplitInOrder() {
        val secrets = PgponyBatch.split(point(0x11) + point(0x22), 2)
        assertArrayEquals(point(0x11), secrets[0])
        assertArrayEquals(point(0x22), secrets[1])
    }

    @Test
    fun secretsOfTheWrongCountAreRefused() {
        assertThrows(PgponyException.BadReply::class.java) { PgponyBatch.split(point(0x11), 2) }
        assertThrows(PgponyException.BadReply::class.java) { PgponyBatch.split(ByteArray(33), 1) }
        assertThrows(PgponyException.BadReply::class.java) { PgponyBatch.split(null, 1) }
    }
}
