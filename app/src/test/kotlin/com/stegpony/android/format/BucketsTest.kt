package com.stegpony.android.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BucketsTest {
    @Test
    fun sizesFollowTheFormat() {
        // FORMAT.md section 2: 64, 96, 128, 192, 256, 384, ... 16 MiB.
        assertEquals(listOf(64L, 96L, 128L, 192L, 256L, 384L), (0..5).map { Buckets.size(it) })
        assertEquals(16L * 1024 * 1024, Buckets.size(Buckets.COUNT - 1))
    }

    @Test
    fun aPayloadTakesTheSmallestBucketThatHoldsItAndTheTag() {
        assertEquals(0, Buckets.forFramed(1))
        assertEquals(0, Buckets.forFramed(48))
        assertEquals(1, Buckets.forFramed(49))
        assertEquals(1, Buckets.forFramed(80))
        assertEquals(2, Buckets.forFramed(81))
    }

    @Test
    fun nothingHoldsMoreThanTheLargestBucket() {
        assertEquals(Buckets.COUNT - 1, Buckets.forFramed(16L * 1024 * 1024 - 16))
        assertNull(Buckets.forFramed(16L * 1024 * 1024 - 15))
    }

    @Test
    fun aDecoyInTheSameBucketIsNotShort() {
        assertEquals(0L, Buckets.decoyShortBy(payloadFramed = 80, decoyFramed = 49))
        assertEquals(0L, Buckets.decoyShortBy(payloadFramed = 49, decoyFramed = 80))
    }

    @Test
    fun aDecoyInALargerBucketIsNotShort() {
        assertEquals(0L, Buckets.decoyShortBy(payloadFramed = 30, decoyFramed = 5000))
    }

    @Test
    fun aShortDecoyIsShortByWhatReachesThePayloadsBucket() {
        // The payload needs bucket 2 (128 bytes). Bucket 1 holds framed lengths up to 80, so 81 is the least.
        assertEquals(81L - 20L, Buckets.decoyShortBy(payloadFramed = 100, decoyFramed = 20))
        assertEquals(1L, Buckets.decoyShortBy(payloadFramed = 100, decoyFramed = 80))
        // After adding what is missing, the decoy is no longer short.
        val payload = 123_456L
        val decoy = 40L
        val missing = Buckets.decoyShortBy(payload, decoy)
        assertEquals(0L, Buckets.decoyShortBy(payload, decoy + missing))
        assertEquals(1L, Buckets.decoyShortBy(payload, decoy + missing - 1))
    }

    @Test
    fun aPayloadTooLargeForAnyBucketIsLeftToTheFitCheck() {
        assertEquals(0L, Buckets.decoyShortBy(payloadFramed = Long.MAX_VALUE / 2, decoyFramed = 10))
    }
}
