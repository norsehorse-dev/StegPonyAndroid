package com.stegpony.android.ui.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTextTest {
    @Test
    fun userIdsShowAsNames() {
        assertEquals("Ana Example", KeysViewModel.displayName("Ana Example <ana@example.org>"))
        assertEquals("<only@example.org>", KeysViewModel.displayName("<only@example.org>"))
        assertEquals("Plain", KeysViewModel.displayName("Plain"))
    }

    @Test
    fun keysAndFingerprintsFitOnALine() {
        val r = "age1qyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpabcdefgh"
        assertEquals("age1qyqszqgpqy…abcdefgh", shortKey(r))
        assertEquals("age1short", shortKey("age1short"))
        assertEquals("E307 B861 3923 A369", groupFingerprint("E307B8613923A369"))
    }
}
