package com.stegpony.android.keys

import android.content.Context
import com.stegpony.android.R
import java.security.SecureRandom
import java.util.Random

/**
 * Makes a passphrase of random words for the Hide tab. The words come from the
 * same 4096-word English list the core uses for dictionary text, bundled as
 * res/raw/passphrase_words.txt (a test keeps the copy identical to the core's).
 * Each word is 12 bits, so [WORDS] words are 72 bits.
 */
object PassphraseGenerator {
    const val WORDS = 6
    const val LIST_LEN = 4096

    @Volatile
    private var cached: List<String>? = null

    /** The list from its file: one lowercase word per line, [LIST_LEN] of them, no repeats. */
    fun parse(text: String): List<String> {
        val words = text.split('\n').filter { it.isNotEmpty() }
        require(words.size == LIST_LEN && words.toSet().size == LIST_LEN) { "bad word list" }
        return words
    }

    fun list(context: Context): List<String> = cached ?: context.applicationContext.resources
        .openRawResource(R.raw.passphrase_words)
        .use { parse(it.readBytes().toString(Charsets.US_ASCII)) }
        .also { cached = it }

    /** [count] words picked independently and evenly, joined by single spaces. */
    fun generate(words: List<String>, random: Random = SecureRandom(), count: Int = WORDS): String =
        (0 until count).joinToString(" ") { words[random.nextInt(words.size)] }
}
