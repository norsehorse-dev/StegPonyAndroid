package com.stegpony.android.card

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HardwareKeyTest {
    private val ok = byteArrayOf(0x90.toByte(), 0x00)

    /** Answers VERIFY and PSO:DECIPHER, recording what was sent. */
    private class Card(private val shared: ByteArray) : CardTransport {
        val sent = mutableListOf<ByteArray>()
        override fun transceive(commandApdu: ByteArray): ByteArray {
            sent.add(commandApdu)
            return when (commandApdu[1].toInt() and 0xFF) {
                OpenPgpCard.INS_SELECT, OpenPgpCard.INS_VERIFY -> byteArrayOf(0x90.toByte(), 0x00)
                OpenPgpCard.INS_PERFORM_SECURITY_OPERATION -> shared + byteArrayOf(0x90.toByte(), 0x00)
                OpenPgpCard.INS_GET_DATA -> byteArrayOf(0x6A.toByte(), 0x88.toByte()) // no KDF DO
                else -> byteArrayOf(0x6D.toByte(), 0x00)
            }
        }
    }

    @Test
    fun selectsAndVerifiesOnceThenSendsTheRawPoint() {
        val shared = ByteArray(32) { (it + 1).toByte() }
        val card = Card(shared)
        val device = CardKeyDevice(OpenPgpCardSession(card), "123456".toByteArray(), "age1test")
        val point = ByteArray(32) { 0x11 }

        assertArrayEquals(shared, device.agree(point))
        assertArrayEquals(shared, device.agree(point))
        val ins = card.sent.map { it[1].toInt() and 0xFF }
        // Select and verify once, before the first decipher only.
        assertEquals(1, ins.count { it == OpenPgpCard.INS_SELECT })
        assertEquals(1, ins.count { it == OpenPgpCard.INS_VERIFY })
        assertEquals(2, ins.count { it == OpenPgpCard.INS_PERFORM_SECURITY_OPERATION })

        val verify = card.sent.first { (it[1].toInt() and 0xFF) == OpenPgpCard.INS_VERIFY }
        assertEquals(OpenPgpCard.PW1_OTHER, verify[3].toInt() and 0xFF)

        // A6 25 7F49 22 86 20 <32-byte point>: the u-coordinate, no 0x40 prefix.
        val pso = card.sent.first { (it[1].toInt() and 0xFF) == OpenPgpCard.INS_PERFORM_SECURITY_OPERATION }
        val data = pso.copyOfRange(5, 5 + (pso[4].toInt() and 0xFF))
        assertArrayEquals(EcdhCipherData.cipherDoForPoint(point), data)
        assertEquals(0x20, data[6].toInt() and 0xFF)
        // No KDF DO on this card, so the PIN goes as typed.
        assertArrayEquals("123456".toByteArray(), verify.copyOfRange(5, 11))
    }

    @Test
    fun sharedSecretAcceptsThePrefixedForm() {
        val raw = ByteArray(32) { 7 }
        assertArrayEquals(raw, HardwareKey.sharedSecret(raw))
        assertArrayEquals(raw, HardwareKey.sharedSecret(byteArrayOf(0x40) + raw))
        assertThrows(OpenPgpCardException.Malformed::class.java) { HardwareKey.sharedSecret(ByteArray(31)) }
    }

    @Test
    fun rsaCardIsRefusedBeforeAnyPin() {
        val aid = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x01, 0x24, 0x01, 0x03, 0x04,
            0x00, 0x11, 0x12, 0x34, 0x56, 0x78, 0x00, 0x00,
        )
        fun tlv(tag: Int, v: ByteArray) = byteArrayOf(tag.toByte(), v.size.toByte()) + v
        val c2 = byteArrayOf(0x01, 0x08, 0x00, 0x00, 0x11, 0x00) // RSA 2048
        val c5 = ByteArray(20) + ByteArray(20) { 0xBB.toByte() } + ByteArray(20)
        val disc = tlv(0xC2, c2) + tlv(0xC5, c5)
        val ard = tlv(0x4F, aid) + tlv(0x73, disc)
        val sent = mutableListOf<Int>()
        val card = object : CardTransport {
            override fun transceive(commandApdu: ByteArray): ByteArray {
                val ins = commandApdu[1].toInt() and 0xFF
                sent.add(ins)
                return when (ins) {
                    OpenPgpCard.INS_SELECT -> ok
                    OpenPgpCard.INS_GET_DATA -> ard + ok
                    else -> byteArrayOf(0x6D.toByte(), 0x00)
                }
            }
        }
        val e = assertThrows(NotCurve25519Exception::class.java) {
            HardwareKey.readDecryptionKey(OpenPgpCardSession(card))
        }
        assertEquals("RSA-2048", e.algorithm)
        assertEquals(listOf(OpenPgpCard.INS_SELECT, OpenPgpCard.INS_GET_DATA), sent)
    }
}
