package com.stegpony.android.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextTest {
    private val recipient = "age1r5lvvt8c5hkmrmurkk53gwq6vmu03cyu720l37nvsp0vtdwzqalsmak33z"

    @Test
    fun keysGoToKeys() {
        assertTrue(SharedText.isKey(recipient))
        assertTrue(SharedText.isKey("My key: $recipient\n"))
        assertTrue(SharedText.isKey("AGE-SECRET-KEY-1QQQQ"))
        assertTrue(SharedText.isKey("-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nxjME\n-----END PGP PUBLIC KEY BLOCK-----"))
    }

    @Test
    fun everythingElseGoesToReveal() {
        assertFalse(SharedText.isKey("Sketch tickets completed worry animated, apparent too mouth lake."))
        assertFalse(SharedText.isKey("See​‌ you⁠ at‍ eight"))
        assertFalse(SharedText.isKey("age1 is how age keys start"))
        assertFalse(SharedText.isKey("x${recipient}x"))
    }
}
