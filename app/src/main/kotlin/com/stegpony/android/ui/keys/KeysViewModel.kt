package com.stegpony.android.ui.keys

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.keys.Contact
import com.stegpony.android.keys.DuplicateKeyException
import com.stegpony.android.keys.KeySource
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.MyKey
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.StegException
import uniffi.stegpony_ffi.generateIdentity
import uniffi.stegpony_ffi.parseIdentityFile
import uniffi.stegpony_ffi.parseRecipient
import uniffi.stegpony_ffi.pgpPublicKey
import uniffi.stegpony_ffi.pgpSecretKey

/** A public key read from text or a file, before it is saved as a contact. */
data class LoadedKey(val recipient: String, val fingerprint: String?, val suggestedName: String?)

/** The add-contact dialog's contents. */
data class ContactDraft(
    val name: String = "",
    val keyText: String = "",
    val loaded: LoadedKey? = null,
    val error: String? = null,
)

/** An OpenPGP secret key waiting for its passphrase. */
class PendingPgp(val bytes: ByteArray, val error: String? = null)

/** Something the user asked to delete, waiting for confirmation. */
sealed interface PendingDelete {
    data class Mine(val key: MyKey) : PendingDelete
    data class Other(val contact: Contact) : PendingDelete
}

class KeysViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var busy by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** Non-null while the create-key dialog is open: the label being typed. */
    var newKeyLabel by mutableStateOf<String?>(null)
    var pendingPgp by mutableStateOf<PendingPgp?>(null)
        private set
    var contactDraft by mutableStateOf<ContactDraft?>(null)
    var pendingDelete by mutableStateOf<PendingDelete?>(null)

    private fun launchTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        errorText = null
        notice = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: DuplicateKeyException) {
                errorText = context.getString(R.string.key_duplicate, e.existingName)
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            } finally {
                busy = false
            }
        }
    }

    fun startCreate() {
        newKeyLabel = context.getString(R.string.default_key_label)
    }

    fun create() {
        if (busy) return
        val label = newKeyLabel?.trim().orEmpty().ifEmpty { context.getString(R.string.default_key_label) }
        newKeyLabel = null
        launchTask {
            val pair = withContext(Dispatchers.Default) { generateIdentity() }
            Keyring.addMine(context, label, pair.identity, pair.recipient, KeySource.Generated)
            notice = context.getString(R.string.key_created)
        }
    }

    /** An age identity file, or an OpenPGP secret key (asks for its passphrase if it has one). */
    fun importMine(uri: Uri) = launchTask {
        val bytes = withContext(Dispatchers.IO) { Files.readBytes(context, uri, MAX_KEY_FILE) }
        val text = bytes.toString(Charsets.UTF_8)
        if (text.startsWith("age-encryption.org/") || text.contains("-----BEGIN AGE ENCRYPTED FILE-----")) {
            errorText = context.getString(R.string.error_age_passphrase_identity)
            return@launchTask
        }
        if (text.contains("AGE-SECRET-KEY-1", ignoreCase = true)) {
            val pairs = withContext(Dispatchers.Default) { parseIdentityFile(text) }
            pairs.forEachIndexed { i, p ->
                val label = context.getString(R.string.imported_key_label) + if (pairs.size > 1) " ${i + 1}" else ""
                Keyring.addMine(context, label, p.identity, p.recipient, KeySource.AgeImport)
            }
            notice = context.getString(R.string.key_imported)
            return@launchTask
        }
        try {
            savePgpSecret(bytes, "")
        } catch (e: StegException.WrongPassphrase) {
            pendingPgp = PendingPgp(bytes)
        }
    }

    fun unlockPgp(passphrase: String) {
        val pending = pendingPgp ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                savePgpSecret(pending.bytes, passphrase)
                pendingPgp = null
            } catch (e: StegException.WrongPassphrase) {
                pendingPgp = PendingPgp(pending.bytes, context.getString(R.string.error_wrong_key_passphrase))
            } catch (e: DuplicateKeyException) {
                pendingPgp = PendingPgp(pending.bytes, context.getString(R.string.key_duplicate, e.existingName))
            } catch (e: Throwable) {
                // Shown in the dialog, which would otherwise hide it.
                pendingPgp = PendingPgp(pending.bytes, errorMessage(context, e))
            } finally {
                busy = false
            }
        }
    }

    fun cancelPgp() {
        pendingPgp = null
    }

    private suspend fun savePgpSecret(bytes: ByteArray, passphrase: String) {
        val key = withContext(Dispatchers.Default) { pgpSecretKey(bytes, passphrase) }
        val label = key.info.userIds.firstOrNull()?.let { displayName(it) } ?: context.getString(R.string.pgp_key_label)
        Keyring.addMine(context, label, key.identity, key.info.recipient, KeySource.Pgp, key.info.subkeyFingerprint)
        notice = context.getString(R.string.key_imported)
    }

    fun startContact() {
        contactDraft = ContactDraft()
    }

    /** Reads a contact's public key from a file: an OpenPGP key, or a text file with an age1… line. */
    fun loadContactFile(uri: Uri) {
        val draft = contactDraft ?: return
        viewModelScope.launch {
            val updated = try {
                val bytes = withContext(Dispatchers.IO) { Files.readBytes(context, uri, MAX_KEY_FILE) }
                val loaded = withContext(Dispatchers.Default) { resolve(bytes) }
                draft.copy(
                    loaded = loaded,
                    keyText = "",
                    name = draft.name.ifBlank { loaded.suggestedName.orEmpty() },
                    error = null,
                )
            } catch (e: Throwable) {
                draft.copy(error = errorMessage(context, e))
            }
            // The dialog may have been closed while the file was read.
            if (contactDraft != null) contactDraft = updated
        }
    }

    fun saveContact() {
        val draft = contactDraft ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val loaded = draft.loaded ?: withContext(Dispatchers.Default) {
                    resolve(draft.keyText.toByteArray(Charsets.UTF_8))
                }
                val name = draft.name.trim().ifEmpty { loaded.suggestedName ?: context.getString(R.string.default_contact_name) }
                Keyring.addContact(context, name, loaded.recipient, loaded.fingerprint)
                contactDraft = null
                notice = context.getString(R.string.contact_added, name)
            } catch (e: DuplicateKeyException) {
                contactDraft = draft.copy(error = context.getString(R.string.key_duplicate, e.existingName))
            } catch (e: Throwable) {
                contactDraft = draft.copy(error = errorMessage(context, e))
            } finally {
                busy = false
            }
        }
    }

    fun confirmDelete() {
        if (busy) return
        val target = pendingDelete ?: return
        pendingDelete = null
        launchTask {
            when (target) {
                is PendingDelete.Mine -> Keyring.removeMine(context, target.key.id)
                is PendingDelete.Other -> Keyring.removeContact(context, target.contact.id)
            }
        }
    }

    var confirmReset by mutableStateOf(false)

    fun retryLoad() = launchTask { Keyring.retry(context) }

    fun reset() {
        confirmReset = false
        launchTask { Keyring.reset(context) }
    }

    companion object {
        const val MAX_KEY_FILE = 1L * 1024 * 1024

        /** "Ana Example <ana@example.org>" shows as "Ana Example". */
        fun displayName(userId: String): String =
            userId.substringBefore(" <").trim().ifEmpty { userId.trim() }

        /** An age1… recipient anywhere in the text (one per line), or an OpenPGP public key. */
        fun resolve(bytes: ByteArray): LoadedKey {
            val text = bytes.toString(Charsets.UTF_8)
            val armored = text.contains("-----BEGIN PGP")
            val ageLine = if (armored) {
                null
            } else {
                text.lineSequence()
                    .map { it.trim().substringBefore(' ') }
                    .firstOrNull { it.startsWith("age1", ignoreCase = true) }
            }
            if (ageLine != null) {
                return LoadedKey(parseRecipient(ageLine), null, null)
            }
            val info = pgpPublicKey(bytes)
            return LoadedKey(info.recipient, info.subkeyFingerprint, info.userIds.firstOrNull()?.let { displayName(it) })
        }
    }
}
