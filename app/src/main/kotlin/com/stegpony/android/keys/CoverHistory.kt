package com.stegpony.android.keys

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fingerprints of the covers you've hidden in (and of what came out), so
 * Hide can warn before a cover is used twice: two versions of one photo can
 * be compared, and the difference gives the hiding away.
 *
 * Kept on this phone only, encrypted with the same Keystore key as the
 * keyring (a list of fingerprints still says which photos were covers to
 * someone holding them). Each entry is the first 16 bytes of the file's
 * SHA-256. On by default; Settings can turn it off, which also forgets it.
 */
object CoverHistory {
    private const val FILE = "covers.bin"
    private const val PREFS = "stegpony"
    private const val KEY_ENABLED = "cover_history_enabled"
    private const val FINGERPRINT_LEN = 16
    private const val MAX_ENTRIES = 10_000

    private val lock = Mutex()
    private var cache: MutableList<String>? = null

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(FINGERPRINT_LEN)
            .joinToString("") { "%02x".format(it) }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clear(context)
    }

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    /** The list, read once. A file that can't be opened counts as empty; the warning is a help, not a lock. */
    private suspend fun entries(context: Context): MutableList<String> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val f = file(context)
            if (!f.baseFile.exists()) {
                mutableListOf()
            } else {
                runCatching {
                    Vault.decrypt(f.readFully()).toString(Charsets.UTF_8)
                        .split('\n').filter { it.length == FINGERPRINT_LEN * 2 }.toMutableList()
                }.getOrDefault(mutableListOf())
            }
        }
        cache = loaded
        return loaded
    }

    suspend fun contains(context: Context, fingerprint: String): Boolean = lock.withLock {
        isEnabled(context) && fingerprint in entries(context)
    }

    suspend fun add(context: Context, vararg fingerprints: String) = lock.withLock {
        if (!isEnabled(context)) return@withLock
        val list = entries(context)
        fingerprints.forEach { if (it !in list) list.add(it) }
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        withContext(Dispatchers.IO) {
            val sealed = Vault.encrypt(list.joinToString("\n").toByteArray(Charsets.UTF_8))
            val f = file(context)
            val out = f.startWrite()
            try {
                out.write(sealed)
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        }
    }

    suspend fun count(context: Context): Int = lock.withLock { entries(context).size }

    suspend fun clear(context: Context) = lock.withLock {
        cache = mutableListOf()
        withContext(Dispatchers.IO) { file(context).delete() }
    }
}
