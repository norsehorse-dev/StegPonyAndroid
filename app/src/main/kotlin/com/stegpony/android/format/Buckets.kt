package com.stegpony.android.format

/**
 * The size classes of the StegPony format (FORMAT.md section 2). A hidden body
 * is padded to one of 37 sizes, and with a decoy both slots use the larger of
 * the two. The app needs them for one thing: telling when a decoy would sit in
 * a bigger size class than it needs, which shows whoever opens it that
 * something larger is hidden beside it.
 *
 * The numbers are the format's and never change within format version 1.
 */
object Buckets {
    const val COUNT = 37

    /** The authentication tag that shares the body with the framed payload. */
    const val TAG_LEN = 16L

    /** Body size of bucket [k] in bytes: 64, 96, 128, 192, 256, 384, and so on up to 16 MiB. */
    fun size(k: Int): Long {
        require(k in 0 until COUNT) { "no bucket $k" }
        val base = 64L shl (k / 2)
        return if (k % 2 == 1) base + base / 2 else base
    }

    /** The smallest bucket that holds a framed payload of [framed] bytes, or null when none does. */
    fun forFramed(framed: Long): Int? = (0 until COUNT).firstOrNull { size(it) >= framed + TAG_LEN }

    /**
     * How many bytes a decoy of [decoyFramed] framed bytes is short of the
     * size class a payload of [payloadFramed] framed bytes needs; 0 when the
     * decoy already fills that class or a larger one.
     */
    fun decoyShortBy(payloadFramed: Long, decoyFramed: Long): Long {
        val payloadBucket = forFramed(payloadFramed) ?: return 0
        val decoyBucket = forFramed(decoyFramed) ?: return 0
        if (decoyBucket >= payloadBucket) return 0
        // The least framed length that no smaller bucket holds.
        val least = size(payloadBucket - 1) - TAG_LEN + 1
        return least - decoyFramed
    }
}
