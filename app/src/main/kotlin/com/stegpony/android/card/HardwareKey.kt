package com.stegpony.android.card

import uniffi.stegpony_ffi.DeviceException
import uniffi.stegpony_ffi.KeyDevice
import uniffi.stegpony_ffi.Payload
import uniffi.stegpony_ffi.StegException
import uniffi.stegpony_ffi.recipientFromX25519
import uniffi.stegpony_ffi.revealText as revealInText
import uniffi.stegpony_ffi.reveal as revealImage

/** The card's decryption key, as StegPony uses it. */
data class CardKey(
    /** The key's public half as an age1… recipient. */
    val recipient: String,
    /** OpenPGP fingerprint of the decryption key, as the card stores it. */
    val fingerprint: String?,
    /** For example "Token2 1A2B3C4D". */
    val label: String,
)

/** The card's decryption key is not Curve25519 (RSA by default on many cards). */
class NotCurve25519Exception(val algorithm: String) :
    Exception("The key's decryption key is $algorithm, not Curve25519")

/** The card has no key in its decryption slot. */
class NoDecryptionKeyException : Exception("The key has no decryption key")

object HardwareKey {
    /**
     * Reads the decryption key. Needs no PIN: the public key is readable by
     * anyone holding the card. Anything but cv25519 is refused here, before a
     * PIN is ever sent.
     */
    fun readDecryptionKey(session: OpenPgpCardSession): CardKey {
        session.select()
        val ard = session.getApplicationRelatedData()
        if (ard.decFingerprint == null) throw NoDecryptionKeyException()
        val algorithm = ard.decAlgorithm
        if (algorithm == null || !algorithm.isCv25519) {
            throw NotCurve25519Exception(algorithm?.displayName ?: "unknown")
        }
        val point = session.readPublicKeyMaterial(CardSlot.DECRYPTION).ecPoint
            ?: throw OpenPgpCardException.Malformed("The key returned no public key for decryption")
        // recipientFromX25519 takes the 32-byte key with or without OpenPGP's 0x40 prefix.
        return CardKey(
            recipient = recipientFromX25519(point),
            fingerprint = ard.decFingerprint,
            label = "${ard.manufacturerName} ${ard.serialHex}",
        )
    }

    /**
     * The X25519 output of PSO:DECIPHER. Cards return the 32-byte shared secret;
     * one that adds the 0x40 prefix OpenPGP uses for points is accepted too.
     */
    fun sharedSecret(cardOutput: ByteArray): ByteArray = when {
        cardOutput.size == 32 -> cardOutput
        cardOutput.size == 33 && cardOutput[0] == 0x40.toByte() -> cardOutput.copyOfRange(1, 33)
        else -> throw OpenPgpCardException.Malformed("The key returned ${cardOutput.size} bytes, not a shared secret")
    }

    /**
     * Looks inside [image] with the card in [session]: reads its decryption
     * key, then lets the core ask the card for one X25519 operation per slot
     * it tries. Runs on the card thread (NFC callback or USB executor), and the
     * card must stay connected until it returns. Null means nothing was found
     * for this key. A card error is rethrown as itself, not as the core's
     * wrapper, so the UI can say what happened (wrong PIN, key moved away).
     */
    fun reveal(session: OpenPgpCardSession, pin: ByteArray, image: ByteArray): Payload? =
        withCard(session, pin) { device -> revealImage(image, emptyList(), listOf(device)) }

    /** As [reveal], for pasted text. */
    fun revealText(session: OpenPgpCardSession, pin: ByteArray, text: String): Payload? =
        withCard(session, pin) { device -> revealInText(text, emptyList(), listOf(device)).payload }

    private fun withCard(
        session: OpenPgpCardSession,
        pin: ByteArray,
        look: (CardKeyDevice) -> Payload?,
    ): Payload? {
        val key = readDecryptionKey(session)
        // Check the PIN now, before the slow image work, so a wrong PIN is
        // reported at once, and even for an image the card is never asked
        // about (which would otherwise read as "nothing found").
        session.verify(OpenPgpCard.PW1_OTHER, pin)
        val device = CardKeyDevice(session, pin, key.recipient)
        return try {
            look(device)
        } catch (e: StegException.KeyDevice) {
            throw device.failure ?: e
        }
    }
}

/**
 * The card as the core's [KeyDevice]. The core asks for at most one operation
 * per slot. Before the first one the application is selected and the PIN
 * verified again: the link sat idle while the image was read, and a card
 * whose field was re-activated in that time has forgotten both.
 */
class CardKeyDevice(
    private val session: OpenPgpCardSession,
    private val pin: ByteArray,
    private val recipient: String,
) : KeyDevice {
    /** The card error behind a failed [agree], for the UI. */
    var failure: Exception? = null
        private set
    private var verified = false

    override fun recipient(): String = recipient

    override fun agree(point: ByteArray): ByteArray {
        try {
            if (!verified) {
                session.select()
                session.verify(OpenPgpCard.PW1_OTHER, pin)
                verified = true
            }
            return HardwareKey.sharedSecret(session.decipher(EcdhCipherData.cipherDoForPoint(point)))
        } catch (e: Exception) {
            failure = e
            throw DeviceException.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
