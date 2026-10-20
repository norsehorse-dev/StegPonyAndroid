// Ported from PGPony Android (same author, Apache-2.0). Keep in step with it.
// CardAlgorithmAttributes.kt
// PGPony Android — HW Phase 0
//
// Parses an OpenPGP card algorithm-attributes data object (C1 = signature,
// C2 = decryption, C3 = authentication) into a friendly description and,
// whether it is the Curve25519 ECDH key StegPony can use.
//
// Layout (OpenPGP card spec §4.4.3.x):
//   RSA:  01 | modulus-bit-length(2) | exponent-bit-length(2) | format(1)
//   ECC:  {12|13|16} | curve-OID-bytes(var) | [optional format byte]
//
// The trailing ECC format byte is ambiguous against the OID, so we
// match curve OIDs by prefix against a known table rather than by exact
// length. No Android dependencies — unit-testable.

package com.stegpony.android.card

data class CardAlgorithmAttributes(
    val rawAlgoId: Int,
    val displayName: String,
    /** True for an ECDH key on Curve25519, the only kind StegPony can use. */
    val isCv25519: Boolean = false,
    val modulusBits: Int? = null,
    val curveName: String? = null
) {
    companion object {
        const val CV25519 = "Cv25519"

        // OID *body* bytes (no leading 0x06 tag/length), as they appear
        // in the card attribute DO.
        private val OID_ED25519 = byteArrayOf(
            0x2B, 0x06, 0x01, 0x04, 0x01, 0xDA.toByte(), 0x47, 0x0F, 0x01
        )
        private val OID_CV25519 = byteArrayOf(
            0x2B, 0x06, 0x01, 0x04, 0x01, 0x97.toByte(), 0x55, 0x01, 0x05, 0x01
        )
        private val OID_NIST_P256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
        private val OID_NIST_P384 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x22)
        private val OID_NIST_P521 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x23)
        private val OID_SECP256K1 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x0A)
        private val OID_BRAINPOOL_P256 = byteArrayOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x07)
        private val OID_BRAINPOOL_P384 = byteArrayOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0B)
        private val OID_BRAINPOOL_P512 = byteArrayOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0D)

        /** Parse the raw attribute bytes; null if empty/unparseable. */
        fun parse(attr: ByteArray): CardAlgorithmAttributes? {
            if (attr.isEmpty()) return null
            val algoId = attr[0].toInt() and 0xFF
            val rest = attr.copyOfRange(1, attr.size)

            return when (algoId) {
                OpenPgpCard.ALGO_RSA -> {
                    if (rest.size < 4) {
                        CardAlgorithmAttributes(algoId, "RSA")
                    } else {
                        val modBits = ((rest[0].toInt() and 0xFF) shl 8) or (rest[1].toInt() and 0xFF)
                        CardAlgorithmAttributes(
                            rawAlgoId = algoId,
                            displayName = "RSA-$modBits",
                            modulusBits = modBits
                        )
                    }
                }
                OpenPgpCard.ALGO_ECDH, OpenPgpCard.ALGO_ECDSA, OpenPgpCard.ALGO_EDDSA -> {
                    val curve = identifyCurve(rest)
                    CardAlgorithmAttributes(
                        rawAlgoId = algoId,
                        displayName = curve,
                        isCv25519 = algoId == OpenPgpCard.ALGO_ECDH && curve == CV25519,
                        curveName = curve
                    )
                }
                else -> CardAlgorithmAttributes(
                    rawAlgoId = algoId,
                    displayName = "Algorithm 0x%02X".format(algoId)
                )
            }
        }

        private fun identifyCurve(oidPlusMaybeFormat: ByteArray): String {
            fun startsWith(prefix: ByteArray): Boolean {
                if (oidPlusMaybeFormat.size < prefix.size) return false
                for (i in prefix.indices) if (oidPlusMaybeFormat[i] != prefix[i]) return false
                return true
            }
            return when {
                startsWith(OID_ED25519) -> "Ed25519"
                startsWith(OID_CV25519) -> CV25519
                startsWith(OID_NIST_P256) -> "NIST P-256"
                startsWith(OID_NIST_P384) -> "NIST P-384"
                startsWith(OID_NIST_P521) -> "NIST P-521"
                startsWith(OID_SECP256K1) -> "secp256k1"
                startsWith(OID_BRAINPOOL_P256) -> "brainpoolP256r1"
                startsWith(OID_BRAINPOOL_P384) -> "brainpoolP384r1"
                startsWith(OID_BRAINPOOL_P512) -> "brainpoolP512r1"
                else -> "EC (unrecognized curve)"
            }
        }
    }
}
