package com.stegpony.android.keys

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where one of your own keys came from. */
enum class KeySource { Generated, AgeImport, Pgp }

/** One of your keys: the secret (an AGE-SECRET-KEY-1… identity) and its public half. */
data class MyKey(
    val id: String,
    val label: String,
    val identity: String,
    val recipient: String,
    val source: KeySource,
    /** For an OpenPGP key: the encryption subkey's fingerprint. */
    val fingerprint: String? = null,
    val createdAt: Long,
)

/** Someone you hide for: a name and their public key as an age1… recipient. */
data class Contact(
    val id: String,
    val name: String,
    val recipient: String,
    /** For an OpenPGP key: the encryption subkey's fingerprint. */
    val fingerprint: String? = null,
    val createdAt: Long,
)

data class Keys(
    val mine: List<MyKey> = emptyList(),
    val contacts: List<Contact> = emptyList(),
)

sealed interface KeyringState {
    data object Loading : KeyringState
    data class Ready(val keys: Keys) : KeyringState

    /** The file exists but could not be opened. Nothing is written until this is resolved. */
    data class Failed(val reason: String) : KeyringState
}

/** The keyring as JSON. Kept apart from storage so it can be tested on the JVM. */
object KeyringJson {
    private const val VERSION = 1

    fun encode(keys: Keys): String {
        val mine = JSONArray()
        keys.mine.forEach { k ->
            mine.put(
                JSONObject()
                    .put("id", k.id)
                    .put("label", k.label)
                    .put("identity", k.identity)
                    .put("recipient", k.recipient)
                    .put("source", k.source.name)
                    .put("fingerprint", k.fingerprint ?: JSONObject.NULL)
                    .put("createdAt", k.createdAt),
            )
        }
        val contacts = JSONArray()
        keys.contacts.forEach { c ->
            contacts.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("recipient", c.recipient)
                    .put("fingerprint", c.fingerprint ?: JSONObject.NULL)
                    .put("createdAt", c.createdAt),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("mine", mine)
            .put("contacts", contacts)
            .toString()
    }

    fun decode(text: String): Keys {
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(version == VERSION) { "unsupported keyring version $version" }
        val mine = root.getJSONArray("mine").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MyKey(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    identity = o.getString("identity"),
                    recipient = o.getString("recipient"),
                    source = KeySource.valueOf(o.getString("source")),
                    fingerprint = o.optStringOrNull("fingerprint"),
                    createdAt = o.getLong("createdAt"),
                )
            }
        }
        val contacts = root.getJSONArray("contacts").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Contact(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    recipient = o.getString("recipient"),
                    fingerprint = o.optStringOrNull("fingerprint"),
                    createdAt = o.getLong("createdAt"),
                )
            }
        }
        return Keys(mine, contacts)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)
}

/** Thrown when adding a key that is already there. */
class DuplicateKeyException(val existingName: String) : Exception("already have $existingName")

/**
 * Your keys and contacts, in one file encrypted by [Vault]. Contacts are
 * encrypted too: who you hide messages for is private as well.
 */
object Keyring {
    private const val FILE = "keyring.bin"

    private val _state = MutableStateFlow<KeyringState>(KeyringState.Loading)
    val state: StateFlow<KeyringState> = _state.asStateFlow()

    private val lock = Mutex()

    /** The keys, or empty while loading or after a failed load. */
    val current: Keys get() = (state.value as? KeyringState.Ready)?.keys ?: Keys()

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    suspend fun load(context: Context) = lock.withLock {
        if (_state.value is KeyringState.Ready) return@withLock
        _state.value = withContext(Dispatchers.IO) {
            val f = file(context)
            if (!f.baseFile.exists()) {
                KeyringState.Ready(Keys())
            } else {
                try {
                    val plain = Vault.decrypt(f.readFully())
                    KeyringState.Ready(KeyringJson.decode(plain.toString(Charsets.UTF_8)))
                } catch (e: Exception) {
                    KeyringState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Tries opening the file again after a failed load. */
    suspend fun retry(context: Context) {
        lock.withLock { if (_state.value is KeyringState.Failed) _state.value = KeyringState.Loading }
        load(context)
    }

    /**
     * Gives up on a keyring that can't be opened: deletes the file and its
     * Keystore key and starts empty. Only offered after a failed load.
     */
    suspend fun reset(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            file(context).delete()
            Vault.deleteKey()
        }
        _state.value = KeyringState.Ready(Keys())
    }

    private suspend fun update(context: Context, change: (Keys) -> Keys) = lock.withLock {
        val ready = _state.value as? KeyringState.Ready ?: error("the keyring is not open")
        val next = change(ready.keys)
        withContext(Dispatchers.IO) {
            val sealed = Vault.encrypt(KeyringJson.encode(next).toByteArray(Charsets.UTF_8))
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
        _state.value = KeyringState.Ready(next)
    }

    private fun newId() = UUID.randomUUID().toString()

    suspend fun addMine(
        context: Context,
        label: String,
        identity: String,
        recipient: String,
        source: KeySource,
        fingerprint: String? = null,
    ) = update(context) { keys ->
        keys.mine.firstOrNull { it.recipient == recipient }?.let { throw DuplicateKeyException(it.label) }
        keys.copy(
            mine = keys.mine + MyKey(newId(), label, identity, recipient, source, fingerprint, System.currentTimeMillis()),
        )
    }

    suspend fun addContact(context: Context, name: String, recipient: String, fingerprint: String? = null) =
        update(context) { keys ->
            keys.contacts.firstOrNull { it.recipient == recipient }?.let { throw DuplicateKeyException(it.name) }
            keys.copy(
                contacts = keys.contacts + Contact(newId(), name, recipient, fingerprint, System.currentTimeMillis()),
            )
        }

    suspend fun removeMine(context: Context, id: String) =
        update(context) { keys -> keys.copy(mine = keys.mine.filterNot { it.id == id }) }

    suspend fun removeContact(context: Context, id: String) =
        update(context) { keys -> keys.copy(contacts = keys.contacts.filterNot { it.id == id }) }
}
