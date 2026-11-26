package com.stegpony.android.keys

import java.io.File
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseGeneratorTest {
    private val bundled = File("src/main/res/raw/passphrase_words.txt")
    private val words = PassphraseGenerator.parse(bundled.readText(Charsets.US_ASCII))

    @Test
    fun bundledListIsTheCoreList() {
        val core = File("../third_party/stegponycore/crates/stegpony-core/src/text/en.txt")
        assertArrayEquals(core.readBytes(), bundled.readBytes())
        // ENGLISH_SHA256 in the core's text/dictionary.rs.
        val digest = MessageDigest.getInstance("SHA-256").digest(bundled.readBytes())
        assertEquals(
            "6e01295be4982adec0341d5484c17f442424bb20998bcd46d5ee1484f1e5c447",
            digest.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun listHas4096DistinctLowercaseWords() {
        assertEquals(PassphraseGenerator.LIST_LEN, words.size)
        assertEquals(PassphraseGenerator.LIST_LEN, words.toSet().size)
        assertTrue(words.all { w -> w.isNotEmpty() && w.all { it in 'a'..'z' } })
    }

    @Test
    fun generatesSixWordsFromTheList() {
        val parts = PassphraseGenerator.generate(words).split(' ')
        assertEquals(PassphraseGenerator.WORDS, parts.size)
        assertTrue(parts.all { it in words })
    }

    @Test
    fun followsTheRandomSource() {
        assertEquals(
            PassphraseGenerator.generate(words, Random(1)),
            PassphraseGenerator.generate(words, Random(1)),
        )
        assertNotEquals(
            PassphraseGenerator.generate(words, Random(1)),
            PassphraseGenerator.generate(words, Random(2)),
        )
        assertEquals(3, PassphraseGenerator.generate(words, Random(1), count = 3).split(' ').size)
    }
}
