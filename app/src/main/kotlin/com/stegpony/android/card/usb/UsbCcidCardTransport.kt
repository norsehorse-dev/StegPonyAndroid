// Ported from PGPony Android (same author, Apache-2.0). Keep in step with it.
// UsbCcidCardTransport.kt
// PGPony Android — USB Phase 1
//
// CardTransport over a USB CCID reader, the wired twin of
// IsoDepCardTransport. `CardTransport.kt` has said since HW Phase 0 that "a
// future USB-CCID transport would also implement this", and OpenPgpCardSession
// depends only on that interface, so nothing in the protocol layer changes.
//
// This file is deliberately thin. Everything that can go subtly wrong in CCID
// (sequence checking, time extensions, messages split across bulk reads) lives
// in CcidExchange behind the CcidPipe interface, where a test can reach it.
// What is left here is genuinely platform plumbing: find the interface, claim
// it, read the class descriptor, locate two endpoints.
//
// HOW THIS DIFFERS FROM NFC, which is most of the design:
//
//   • NFC is a short modal tap the platform drives. USB is a persistent
//     connection that can exist before the user asks for anything, and can be
//     unplugged mid-APDU.
//   • Android has no framework CCID stack, so the host builds the bulk
//     envelopes itself. IsoDep provides that layer for free.
//   • Removal is an explicit event rather than something to poll for, so there
//     is no awaitRemoval twin here. The NFC version exists because a tag left
//     in the field falls through to the platform dispatcher and launches
//     Yubico Authenticator (issue #7); unplugging a USB key cannot do that.
//
// PERMISSION. Nothing here requests it. UsbManager.openDevice returns null
// without a granted permission, and asking is a UI decision that belongs in
// Phase 2 alongside capability gating, not in the transport.

package com.stegpony.android.card.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.stegpony.android.card.CardTransport
import com.stegpony.android.card.CcidDescriptor
import com.stegpony.android.card.CcidExchange
import com.stegpony.android.card.CcidPipe
import com.stegpony.android.card.OpenPgpCardException

class UsbCcidCardTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val exchange: CcidExchange,
    /** Parsed from the reader's own class descriptor, not assumed. */
    val descriptor: CcidDescriptor,
    /** Answer To Reset, from power-on. Kept for diagnostics and card status. */
    val atr: ByteArray,
) : CardTransport {

    @Volatile
    private var closed = false

    override fun transceive(commandApdu: ByteArray): ByteArray {
        if (closed) throw OpenPgpCardException.TagLost(UNPLUGGED)
        return exchange.transceiveApdu(commandApdu)
    }

    /**
     * True while the reader still reports a card in the slot. Cheap, and it
     * costs no card state, so it is safe to call between operations.
     */
    fun isCardPresent(): Boolean = !closed && runCatching {
        exchange.slotStatus().cardPresent
    }.getOrDefault(false)

    /**
     * Power the card down and release the interface. Every step is
     * best-effort: this is called on teardown paths where the device may
     * already be gone, and throwing there would replace a clean exit with a
     * crash for no benefit.
     */
    fun close() {
        if (closed) return
        closed = true
        runCatching { exchange.powerOff() }
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    // ── The pipe. Direct pass-through; bulkTransfer's contract already
    //    matches CcidPipe's, which is why CcidPipe was shaped that way. ──

    private class UsbBulkPipe(
        private val connection: UsbDeviceConnection,
        private val bulkOut: UsbEndpoint,
        private val bulkIn: UsbEndpoint,
        override val readBufferSize: Int,
    ) : CcidPipe {
        override fun write(bytes: ByteArray, timeoutMs: Int): Int =
            connection.bulkTransfer(bulkOut, bytes, bytes.size, timeoutMs)

        override fun read(buffer: ByteArray, timeoutMs: Int): Int =
            connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
    }

    companion object {
        /** USB device class 0x0B, "Smart Card". */
        const val USB_CLASS_SMART_CARD = 0x0B

        const val UNPLUGGED = "The security key was disconnected. Plug it back in and try again."

        private const val MIN_READ_BUFFER = 512
        private const val MAX_READ_BUFFER = 65_536

        /**
         * Every attached device exposing a smart-card interface. Vendor id is
         * deliberately NOT filtered: NFC already serves YubiKey, Token2 and
         * Nitrokey, and filtering USB to one vendor would be a capability
         * regression across transports in the release whose point is hardware
         * key connectivity. That is the same reasoning that ruled out taking
         * yubikit-android for this.
         */
        fun findReaders(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { findCcidInterface(it) != null }

        /** The device's smart-card interface, or null if it has none. */
        fun findCcidInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_SMART_CARD) return iface
            }
            return null
        }

        /**
         * Open a session with [device] and power up the card.
         *
         * Throws rather than returning null, with a message naming the actual
         * obstacle: which of permission, claiming, the descriptor, the
         * exchange level or the endpoints failed. A silent null here would
         * surface later as "no card" and send someone looking in the wrong
         * place.
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbCcidCardTransport {
            val iface = findCcidInterface(device)
                ?: throw OpenPgpCardException.Communication(
                    "${device.productName ?: "This device"} does not expose a " +
                        "smart-card interface"
                )

            // Returns null when permission has not been granted. That is the
            // common first-run case, so it gets its own message.
            val connection = manager.openDevice(device)
                ?: throw OpenPgpCardException.Communication(
                    "StegPony does not have permission to use this USB device yet"
                )

            try {
                if (!connection.claimInterface(iface, true)) {
                    throw OpenPgpCardException.Communication(
                        "Another app is using this security key. Close it and try again."
                    )
                }

                val descriptor = readDescriptor(connection)

                if (!descriptor.exchangeLevel.acceptsApdus) {
                    // TPDU and character level mean the host has to do T=0/T=1
                    // framing itself, which is a different and much larger
                    // piece of work. Saying so is better than hanging.
                    throw OpenPgpCardException.Communication(
                        "This reader works at ${descriptor.exchangeLevel} level, and " +
                            "StegPony only supports readers that accept whole APDUs. " +
                            "Use NFC with this key instead."
                    )
                }

                val (bulkIn, bulkOut) = findBulkEndpoints(iface)

                val bufferSize = descriptor.maxMessageLength
                    .coerceIn(MIN_READ_BUFFER.toLong(), MAX_READ_BUFFER.toLong())
                    .toInt()

                val exchange = CcidExchange(
                    pipe = UsbBulkPipe(connection, bulkOut, bulkIn, bufferSize),
                    descriptor = descriptor,
                )

                val atr = exchange.powerOn()

                return UsbCcidCardTransport(connection, iface, exchange, descriptor, atr)
            } catch (t: Throwable) {
                // Anything after openDevice leaves a claimed interface and an
                // open connection behind if it escapes.
                runCatching { connection.releaseInterface(iface) }
                runCatching { connection.close() }
                throw t
            }
        }

        /**
         * Android exposes no typed accessor for class-specific descriptors, so
         * the raw configuration blob is walked by bLength. CcidDescriptor
         * owns that parsing.
         */
        private fun readDescriptor(connection: UsbDeviceConnection): CcidDescriptor {
            val raw = connection.rawDescriptors
                ?: throw OpenPgpCardException.Communication(
                    "Could not read this device's USB descriptors"
                )
            val offset = CcidDescriptor.findIn(raw)
            if (offset < 0) {
                throw OpenPgpCardException.Malformed(
                    "This device claims to be a smart-card reader but publishes no " +
                        "CCID class descriptor"
                )
            }
            return CcidDescriptor.parse(raw, offset)
        }

        /** Returns (bulkIn, bulkOut). Interrupt endpoints are not used. */
        private fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint> {
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && bulkIn == null) bulkIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) bulkOut = ep
            }
            if (bulkIn == null || bulkOut == null) {
                throw OpenPgpCardException.Communication(
                    "This reader is missing the bulk endpoints CCID requires"
                )
            }
            return bulkIn to bulkOut
        }
    }
}
