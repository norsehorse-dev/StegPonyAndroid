package com.stegpony.android.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyringJsonTest {
    private val keys = Keys(
        mine = listOf(
            MyKey("a", "Phone key", "AGE-SECRET-KEY-1XYZ", "age1abc", KeySource.Generated, null, 1L),
            MyKey("b", "Work \"PGP\"", "AGE-SECRET-KEY-1QQQ", "age1def", KeySource.Pgp, "E307B861", 2L),
        ),
        contacts = listOf(
            Contact("c", "Ana, ünïcode", "age1ghi", null, 3L),
            Contact("d", "PGP friend", "age1jkl", "665B7415", 4L),
        ),
    )

    @Test
    fun roundTrip() {
        assertEquals(keys, KeyringJson.decode(KeyringJson.encode(keys)))
        assertEquals(Keys(), KeyringJson.decode(KeyringJson.encode(Keys())))
    }

    @Test
    fun refusesANewerVersion() {
        val newer = KeyringJson.encode(keys).replace("\"version\":1", "\"version\":2")
        assertThrows(IllegalArgumentException::class.java) { KeyringJson.decode(newer) }
    }
}
