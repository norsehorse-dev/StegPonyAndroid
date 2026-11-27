package com.stegpony.android.pgpony

import java.security.SecureRandom
import uniffi.stegpony_ffi.DeviceException
import uniffi.stegpony_ffi.KeyDevice

/**
 * First pass of a reveal through PGPony. The core asks a key for one agreement
 * per slot, one at a time, and every call to PGPony is a confirmation, so the
 * points are collected first and sent together.
 *
 * The points don't depend on the secret: the core finds a slot from the
 * recipient alone. This device notes each point and answers with random bytes.
 * The core takes that for a wrong key, finds nothing in that slot and goes on
 * to the next, which is the answer it gives for any key that doesn't open an
 * image. So the pass collects every point and reveals nothing.
 */
class RecordingKeyDevice(
    private val recipient: String,
    private val fill: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) : KeyDevice {
    private val seen = mutableListOf<ByteArray>()

    /** The points asked for, in order, each once. */
    val points: List<ByteArray> get() = synchronized(seen) { seen.map { it.copyOf() } }

    override fun recipient(): String = recipient

    override fun agree(point: ByteArray): ByteArray {
        synchronized(seen) {
            if (seen.none { it.contentEquals(point) }) seen.add(point.copyOf())
        }
        val answer = ByteArray(PgponyContract.POINT_LEN)
        fill(answer)
        // All zeros is the one answer the core reads as "no agreement".
        if (answer.all { it == 0.toByte() }) answer[0] = 1
        return answer
    }
}

/**
 * Second pass: answers the core from the shared secrets PGPony returned. A
 * point that was not in the batch fails the reveal instead of being guessed.
 */
class AgreedKeyDevice(
    private val recipient: String,
    private val points: List<ByteArray>,
    private val secrets: List<ByteArray>,
) : KeyDevice {
    init {
        require(points.size == secrets.size) { "one secret per point" }
    }

    override fun recipient(): String = recipient

    override fun agree(point: ByteArray): ByteArray {
        val i = points.indexOfFirst { it.contentEquals(point) }
        if (i < 0) throw DeviceException.Failed("PGPony was not asked about this point")
        return secrets[i].copyOf()
    }

    /** Overwrites the shared secrets. Call when the reveal is over. */
    fun wipe() {
        secrets.forEach { it.fill(0) }
    }
}

object PgponyBatch {
    /** The points as X25519_POINTS values: concatenated, at most [max] points in each. */
    fun chunks(points: List<ByteArray>, max: Int): List<ByteArray> {
        require(max > 0) { "max must be positive" }
        require(points.all { it.size == PgponyContract.POINT_LEN }) { "points are 32 bytes" }
        return points.chunked(max).map { chunk ->
            ByteArray(chunk.size * PgponyContract.POINT_LEN).also { out ->
                chunk.forEachIndexed { i, p -> p.copyInto(out, i * PgponyContract.POINT_LEN) }
            }
        }
    }

    /** X25519_SECRETS split back into one secret per point. Anything but [count] secrets is refused. */
    fun split(secrets: ByteArray?, count: Int): List<ByteArray> {
        if (secrets == null || secrets.size != count * PgponyContract.POINT_LEN) throw PgponyException.BadReply()
        return List(count) { i ->
            secrets.copyOfRange(i * PgponyContract.POINT_LEN, (i + 1) * PgponyContract.POINT_LEN)
        }
    }
}
