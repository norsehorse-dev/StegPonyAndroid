// Ported from PGPony Android (same author, Apache-2.0). Keep in step with it.
// IsoDepCardTransport.kt
// PGPony Android — HW Phase 1
//
// CardTransport over android.nfc.tech.IsoDep — the ISO 14443-4 / 7816
// channel an OpenPGP card (YubiKey 5 NFC, Token2 PIN+, Nitrokey 3 NFC)
// exposes over NFC. transceive() must run off the main thread; the NFC
// reader-mode callback already delivers on a binder thread, so callers
// satisfy that for free.

package com.stegpony.android.card.nfc

import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import com.stegpony.android.card.CardTransport
import com.stegpony.android.card.OpenPgpCardException
import java.io.IOException

class IsoDepCardTransport(private val isoDep: IsoDep) : CardTransport {

    init {
        if (!isoDep.isConnected) {
            try {
                isoDep.connect()
            } catch (e: IOException) {
                throw OpenPgpCardException.Communication("Could not connect to card: ${e.message}", e)
            }
        }
        // Generous timeout — on-card RSA-4096 / touch-confirm (UIF) can
        // take a couple of seconds. (UIF prompting itself is Phase 2.)
        runCatching { isoDep.timeout = 20_000 }
    }

    override fun transceive(commandApdu: ByteArray): ByteArray {
        return try {
            isoDep.transceive(commandApdu)
        } catch (e: TagLostException) {
            throw OpenPgpCardException.TagLost(cause = e)
        } catch (e: IOException) {
            throw OpenPgpCardException.Communication("Card I/O failed: ${e.message}", e)
        }
    }

    /**
     * 4.1.0 - block until the card leaves the field, or [maxWaitMs] elapses.
     * Returns true when the card is gone, false when it was still present at
     * the cap.
     *
     * Exists so a caller that must tear down NFC reader mode (the provider
     * card dialog, which has to close to hand its result back to the mail
     * app) can wait for the user to lift the key first. A tag sitting in the
     * field with no reader attached falls through to the platform tag
     * dispatcher, which reads its NDEF record and launches whatever app
     * claims it - on a YubiKey, Yubico Authenticator, popping up right after
     * a decrypt the user asked their mail app for (issue #7).
     *
     * Presence is probed with a real APDU rather than [IsoDep.isConnected]
     * alone: isConnected reports the local connection state, which can stay
     * true after the tag is physically gone. GET DATA for DO 0x5F52
     * (historical bytes) is readable without any PIN and changes no card
     * state; only whether a response comes back at all is used.
     *
     * Runs on the NFC binder thread, like every other call on this transport.
     */
    fun awaitRemoval(maxWaitMs: Long, pollIntervalMs: Long = 150L): Boolean {
        // Drop the generous operation timeout for the duration of the poll,
        // so a removal surfaces in ~1s rather than blocking for 20.
        runCatching { isoDep.timeout = 1_000 }
        val deadlineNanos = System.nanoTime() + maxWaitMs * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (!isCardPresent()) return true
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !isCardPresent()
    }

    private fun isCardPresent(): Boolean = try {
        isoDep.isConnected && isoDep.transceive(PRESENCE_CHECK_APDU).isNotEmpty()
    } catch (e: Exception) {
        // TagLostException, or any IO error once the tag is out of range.
        false
    }

    fun close() {
        runCatching { isoDep.close() }
    }

    private companion object {
        /** GET DATA, DO 0x5F52 (historical bytes) - free, PIN-less, no side effects. */
        private val PRESENCE_CHECK_APDU =
            byteArrayOf(0x00, 0xCA.toByte(), 0x5F, 0x52, 0x00)
    }
}
