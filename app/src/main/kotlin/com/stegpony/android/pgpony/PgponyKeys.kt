package com.stegpony.android.pgpony

/** A Curve25519 encryption subkey PGPony holds, as X25519_KEYS lists it. */
class PgponyKey(
    /** Upper-case hex: 40 digits for a v4 key, 64 for v5 and v6. */
    val subkeyFingerprint: String,
    val primaryFingerprint: String,
    /** The key's primary User ID. May be empty. */
    val label: String,
    /** True when the subkey's secret is on a security key. */
    val onCard: Boolean,
    /** The 32-byte public u-coordinate. */
    val point: ByteArray,
)

object PgponyKeys {
    /**
     * The parallel arrays of an X25519_KEYS reply as a list. The fingerprints
     * and the points decide how many keys there are and must agree; a reply
     * where they don't is refused whole. The other arrays only describe a key,
     * so a missing entry there is left empty.
     */
    fun parse(
        subkeyFingerprints: Array<String>?,
        primaryFingerprints: Array<String>?,
        points: ByteArray?,
        labels: Array<String>?,
        onCard: BooleanArray?,
    ): List<PgponyKey> {
        val fingerprints = subkeyFingerprints ?: emptyArray()
        val all = points ?: ByteArray(0)
        if (all.size != fingerprints.size * PgponyContract.POINT_LEN) throw PgponyException.BadReply()
        return fingerprints.mapIndexed { i, fingerprint ->
            if (fingerprint.isBlank()) throw PgponyException.BadReply()
            PgponyKey(
                subkeyFingerprint = fingerprint,
                primaryFingerprint = primaryFingerprints?.getOrNull(i).orEmpty(),
                label = labels?.getOrNull(i).orEmpty(),
                onCard = onCard?.getOrNull(i) ?: false,
                point = all.copyOfRange(i * PgponyContract.POINT_LEN, (i + 1) * PgponyContract.POINT_LEN),
            )
        }
    }
}
