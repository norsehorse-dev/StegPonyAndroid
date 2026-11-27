package com.stegpony.android.ui.reveal

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegpony.android.i18n.LanguageManager
import com.stegpony.android.card.HardwareKey
import com.stegpony.android.card.OpenPgpCardSession
import com.stegpony.android.io.Files
import com.stegpony.android.io.PickedFile
import com.stegpony.android.keys.Keyring
import com.stegpony.android.R
import com.stegpony.android.pgpony.AgreedKeyDevice
import com.stegpony.android.pgpony.PgponyBatch
import com.stegpony.android.pgpony.PgponyClient
import com.stegpony.android.pgpony.PgponyContract
import com.stegpony.android.pgpony.PgponyException
import com.stegpony.android.pgpony.PgponyKey
import com.stegpony.android.pgpony.PgponyKeys
import com.stegpony.android.pgpony.PgponyReply
import com.stegpony.android.pgpony.RecordingKeyDevice
import com.stegpony.android.ui.common.errorMessage
import androidx.compose.ui.graphics.ImageBitmap
import com.stegpony.android.ui.common.Previews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.KeyDevice
import uniffi.stegpony_ffi.Payload
import uniffi.stegpony_ffi.RevealSecret
import uniffi.stegpony_ffi.StegException
import uniffi.stegpony_ffi.identityKeyPair
import uniffi.stegpony_ffi.recipientFromX25519
import uniffi.stegpony_ffi.revealText
import uniffi.stegpony_ffi.reveal as revealImage

/** Where to look: an image, or pasted text. */
enum class RevealSource { Image, Text }

sealed interface RevealOutcome {
    data class Found(val payload: Payload) : RevealOutcome
    data object NothingFound : RevealOutcome
}

/** Where a reveal through PGPony stands. */
sealed interface PgponyStep {
    /** Asking PGPony which keys it has. */
    data object Connecting : PgponyStep

    /** The user picks one of PGPony's keys. */
    data class Choosing(val keys: List<PgponyKey>) : PgponyStep

    /** Reading the image or text, before or after PGPony's part. */
    data object Looking : PgponyStep

    /** One of PGPony's own screens is up: its consent, or its confirmation. */
    data object Waiting : PgponyStep
}

class RevealViewModel(app: Application) : AndroidViewModel(app) {
    var source by mutableStateOf(RevealSource.Image)
        private set
    /** Pasted or shared-in text to look in. */
    var pastedText by mutableStateOf("")

    var image by mutableStateOf<PickedFile?>(null)
        private set
    // State, not a plain field: canReveal reads it first, and a read Compose
    // can't track would leave the Reveal button stuck until something else
    // recomposed the screen.
    private var imageBytes by mutableStateOf<ByteArray?>(null)
    /** A small picture of the chosen image, for the screen only. */
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    var passphrase by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<RevealOutcome?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)

    // Localized, so messages follow the in-app language before Android 13 too.
    private val context get() = LanguageManager.localized(getApplication<Application>())

    fun selectSource(s: RevealSource) {
        if (blocked || s == source) return
        source = s
        outcome = null
        errorText = null
    }

    /** Text shared in from another app. Returns false when a reveal is running and it wasn't taken. */
    fun openText(text: String): Boolean {
        if (blocked) return false
        source = RevealSource.Text
        pastedText = text
        outcome = null
        errorText = null
        return true
    }

    /** Returns false when a reveal is running and the image wasn't taken. */
    fun open(uri: Uri): Boolean {
        if (blocked) return false
        source = RevealSource.Image
        errorText = null
        outcome = null
        loading = true
        viewModelScope.launch {
            try {
                val (picked, bytes) = withContext(Dispatchers.IO) {
                    Files.describe(context, uri) to Files.readBytes(context, uri, Files.MAX_IMAGE_BYTES)
                }
                val small = withContext(Dispatchers.Default) { Previews.decode(bytes) }
                image = picked
                imageBytes = bytes
                preview = small
            } catch (e: Throwable) {
                image = null
                imageBytes = null
                preview = null
                errorText = errorMessage(context, e)
            } finally {
                loading = false
            }
        }
        return true
    }

    /** Ready once there is an image and something to try: a passphrase or at least one of your keys. */
    fun canReveal(haveKeys: Boolean): Boolean =
        hasInput && (passphrase.isNotEmpty() || haveKeys) && !blocked && !loading

    fun reveal() {
        val bytes = imageBytes
        val text = pastedText
        val fromText = source == RevealSource.Text
        if (!fromText && bytes == null) return
        val mine = Keyring.current.softwareKeys
        if (!canReveal(mine.isNotEmpty())) return
        errorText = null
        outcome = null
        busy = true
        // A typed passphrase is tried alone. Trying your keys as well would
        // defeat a decoy: someone made to open an image hidden for their own
        // key types the decoy passphrase, and the key would open the real one.
        // A pasted AGE-SECRET-KEY-1… is a key, used once and not saved.
        val typed = passphrase
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.Default) {
                    val secrets: List<RevealSecret> = when {
                        isSecretKey(typed) -> listOf(RevealSecret.Identity(identityKeyPair(typed.trim()).identity))
                        typed.isNotEmpty() -> listOf(RevealSecret.Passphrase(typed))
                        else -> mine.map { RevealSecret.Identity(it.identity) }
                    }
                    if (fromText) {
                        revealText(text, secrets, emptyList()).payload
                    } else {
                        revealImage(bytes!!, secrets, emptyList())
                    }
                }
                outcome = if (found != null) RevealOutcome.Found(found) else RevealOutcome.NothingFound
                passphrase = ""
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            } finally {
                busy = false
            }
        }
    }

    /** Saves a revealed file to a document the user chose. */
    fun saveFile(uri: Uri) {
        val p = (outcome as? RevealOutcome.Found)?.payload as? Payload.File ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Files.write(context, uri, p.data) }
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            }
        }
    }

    /**
     * The hardware-key operation for the current image, run on the card
     * thread, while the card dialog is open. The image is fixed when the
     * dialog opens: an image shared in meanwhile waits (see [blocked]).
     */
    var cardOperation by mutableStateOf<((OpenPgpCardSession, ByteArray) -> Payload?)?>(null)
        private set

    /** A reveal, a hardware-key dialog or a reveal through PGPony is under way; a new image waits. */
    val blocked: Boolean get() = busy || cardOperation != null || pgponyStep != null

    fun openCard() {
        if (blocked || !hasInput) return
        if (source == RevealSource.Image) {
            val bytes = imageBytes ?: return
            cardOperation = { session: OpenPgpCardSession, pin: ByteArray -> HardwareKey.reveal(session, pin, bytes) }
            return
        }
        // Check that it is StegPony text before asking for the key's PIN; with
        // no keys this only reads the text.
        val text = pastedText
        errorText = null
        outcome = null
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { revealText(text, emptyList(), emptyList()) }
                cardOperation = { session: OpenPgpCardSession, pin: ByteArray -> HardwareKey.revealText(session, pin, text) }
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            } finally {
                busy = false
            }
        }
    }

    fun closeCard() {
        cardOperation = null
    }

    fun cardResult(found: Payload?) {
        cardOperation = null
        errorText = null
        outcome = if (found != null) RevealOutcome.Found(found) else RevealOutcome.NothingFound
        passphrase = ""
    }

    // ── Reveal through PGPony ────────────────────────────────────────────
    //
    // A key that lives in PGPony Android, on the phone or on a security key,
    // opens the image without being imported here. PGPony does the X25519
    // agreements and asks the user each time; StegPony collects the points
    // first so there is one confirmation per reveal (pgpony/PgponyReveal.kt).

    /** Where the PGPony reveal stands; null when none is under way. */
    var pgponyStep by mutableStateOf<PgponyStep?>(null)
        private set

    /** A PGPony screen for the Reveal page to start. Cleared by [pgponyLaunched]. */
    var pgponyLaunch by mutableStateOf<PendingIntent?>(null)
        private set

    /** What to do when the PGPony screen now showing returns OK; null when none is expected. */
    private var afterPgpony: (suspend (Intent?) -> Unit)? = null
    /** Said when that screen is closed without an OK. */
    private var pgponyDeclined: Int = R.string.pgpony_cancelled
    private var pgponyJob: Job? = null
    private var pgponyMaxPoints = PgponyContract.DEFAULT_MAX_POINTS
    /** Shared secrets PGPony has returned so far in this reveal; wiped when it ends, however it ends. */
    private var pgponySecrets: MutableList<ByteArray>? = null

    /** The image bytes or the text this PGPony reveal is for, fixed when it starts. */
    private var pgponyImage: ByteArray? = null
    private var pgponyText: String? = null

    fun pgponyInstalled(context: Context): Boolean = PgponyClient.isInstalled(context)

    fun openPgpony() {
        if (blocked || !hasInput) return
        if (source == RevealSource.Image) {
            pgponyImage = imageBytes ?: return
            pgponyText = null
        } else {
            pgponyImage = null
            pgponyText = pastedText
        }
        errorText = null
        outcome = null
        runPgpony { loadPgponyKeys() }
    }

    /** One step of the PGPony reveal. Any failure ends the whole reveal with a message. */
    private fun runPgpony(step: suspend () -> Unit) {
        pgponyJob = viewModelScope.launch {
            try {
                step()
            } catch (e: PgponyException) {
                endPgpony(pgponyErrorMessage(e))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                endPgpony(errorMessage(context, e))
            }
        }
    }

    private fun endPgpony(message: String? = null) {
        pgponyStep = null
        pgponyLaunch = null
        afterPgpony = null
        pgponyImage = null
        pgponyText = null
        pgponySecrets?.forEach { it.fill(0) }
        pgponySecrets = null
        if (message != null) errorText = message
    }

    /** Hands a PGPony screen to the page and notes what follows it. */
    private fun askInPgpony(pending: PendingIntent, declined: Int, then: suspend (Intent?) -> Unit) {
        afterPgpony = then
        pgponyDeclined = declined
        pgponyStep = PgponyStep.Waiting
        pgponyLaunch = pending
    }

    private suspend fun loadPgponyKeys() {
        pgponyStep = PgponyStep.Connecting
        val app = getApplication<Application>()
        when (val caps = PgponyClient.call(app, PgponyClient.request(PgponyContract.ACTION_GET_CAPABILITIES))) {
            // First use: PGPony asks whether StegPony may connect at all.
            is PgponyReply.NeedsUser -> return askInPgpony(caps.pending, R.string.pgpony_not_allowed) { loadPgponyKeys() }
            is PgponyReply.Error -> {
                val tooOld = PgponyClient.meansTooOld(PgponyContract.ACTION_GET_CAPABILITIES, caps.reason, caps.errorId, caps.message)
                throw if (tooOld) PgponyException.TooOld() else PgponyException.Refused(caps.reason)
            }
            is PgponyReply.Success -> {
                val actions = caps.data.getStringArrayExtra(PgponyContract.EXTRA_CAPABILITIES).orEmpty()
                if (PgponyContract.ACTION_X25519_KEYS !in actions || PgponyContract.ACTION_X25519_AGREE !in actions) {
                    throw PgponyException.TooOld()
                }
                pgponyMaxPoints = caps.data
                    .getIntExtra(PgponyContract.EXTRA_X25519_MAX_POINTS, PgponyContract.DEFAULT_MAX_POINTS)
                    .coerceIn(1, PgponyContract.DEFAULT_MAX_POINTS)
            }
        }
        when (val listed = PgponyClient.call(app, PgponyClient.request(PgponyContract.ACTION_X25519_KEYS))) {
            is PgponyReply.NeedsUser -> return askInPgpony(listed.pending, R.string.pgpony_not_allowed) { loadPgponyKeys() }
            is PgponyReply.Error -> throw PgponyException.Refused(listed.reason)
            is PgponyReply.Success -> {
                val data = listed.data
                val keys = PgponyKeys.parse(
                    data.getStringArrayExtra(PgponyContract.EXTRA_X25519_SUBKEY_FINGERPRINTS),
                    data.getStringArrayExtra(PgponyContract.EXTRA_X25519_PRIMARY_FINGERPRINTS),
                    data.getByteArrayExtra(PgponyContract.EXTRA_X25519_PUBLIC_POINTS),
                    data.getStringArrayExtra(PgponyContract.EXTRA_X25519_LABELS),
                    data.getBooleanArrayExtra(PgponyContract.EXTRA_X25519_ON_CARD),
                )
                if (keys.isEmpty()) return endPgpony(context.getString(R.string.pgpony_no_keys))
                pgponyStep = PgponyStep.Choosing(keys)
            }
        }
    }

    /** The user chose [key] from PGPony's list. */
    fun pickPgponyKey(key: PgponyKey) {
        if (pgponyStep !is PgponyStep.Choosing) return
        runPgpony { revealWithPgpony(key) }
    }

    private fun lookWith(device: KeyDevice): Payload? {
        val image = pgponyImage
        return if (image != null) {
            revealImage(image, emptyList(), listOf(device))
        } else {
            revealText(pgponyText.orEmpty(), emptyList(), listOf(device)).payload
        }
    }

    private suspend fun revealWithPgpony(key: PgponyKey) {
        pgponyStep = PgponyStep.Looking
        val recipient = recipientFromX25519(key.point)
        // First pass: which points would the core ask this key about?
        val points = withContext(Dispatchers.Default) {
            RecordingKeyDevice(recipient).also { lookWith(it) }.points
        }
        if (points.isEmpty()) {
            // Too small to hold anything for a key: nothing to ask PGPony.
            endPgpony()
            outcome = RevealOutcome.NothingFound
            return
        }
        val secrets = mutableListOf<ByteArray>()
        pgponySecrets = secrets
        agreeInPgpony(key, recipient, points, PgponyBatch.chunks(points, pgponyMaxPoints), secrets)
    }

    /** Sends the next chunk of points; when every chunk is answered, opens the image. */
    private suspend fun agreeInPgpony(
        key: PgponyKey,
        recipient: String,
        points: List<ByteArray>,
        chunks: List<ByteArray>,
        secrets: MutableList<ByteArray>,
    ) {
        val done = secrets.size
        if (done == points.size) return openWithSecrets(recipient, points, secrets)
        // Chunks are full except the last, so the chunk in turn follows from how many secrets are in.
        val chunk = chunks[done / pgponyMaxPoints]
        val count = chunk.size / PgponyContract.POINT_LEN
        val request = PgponyClient.request(PgponyContract.ACTION_X25519_AGREE)
            .putExtra(PgponyContract.EXTRA_X25519_SUBKEY_FINGERPRINT, key.subkeyFingerprint)
            .putExtra(PgponyContract.EXTRA_X25519_POINTS, chunk)
        val app = getApplication<Application>()
        suspend fun take(reply: PgponyReply.Success) {
            val returned = reply.data.getByteArrayExtra(PgponyContract.EXTRA_X25519_SECRETS)
            try {
                secrets += PgponyBatch.split(returned, count)
            } finally {
                returned?.fill(0)
            }
            agreeInPgpony(key, recipient, points, chunks, secrets)
        }
        when (val reply = PgponyClient.call(app, request)) {
            is PgponyReply.NeedsUser -> askInPgpony(reply.pending, R.string.pgpony_cancelled) { returned ->
                pgponyStep = PgponyStep.Looking
                // PGPony keeps the result for one collection: the re-call with the intent its screen returned.
                if (returned == null) throw PgponyException.BadReply()
                when (val again = PgponyClient.call(app, returned)) {
                    is PgponyReply.Success -> take(again)
                    is PgponyReply.Error -> throw PgponyException.Refused(again.reason)
                    // It asked, the user said yes, and it asks again: don't loop.
                    is PgponyReply.NeedsUser -> throw PgponyException.BadReply()
                }
            }
            is PgponyReply.Error -> throw PgponyException.Refused(reply.reason)
            is PgponyReply.Success -> take(reply)
        }
    }

    private suspend fun openWithSecrets(recipient: String, points: List<ByteArray>, secrets: List<ByteArray>) {
        pgponyStep = PgponyStep.Looking
        val device = AgreedKeyDevice(recipient, points, secrets)
        val found = try {
            withContext(Dispatchers.Default) { lookWith(device) }
        } catch (e: StegException.KeyDevice) {
            // The core asked about a point the first pass never saw.
            throw PgponyException.BadReply()
        } finally {
            device.wipe()
        }
        endPgpony()
        outcome = if (found != null) RevealOutcome.Found(found) else RevealOutcome.NothingFound
        passphrase = ""
    }

    /** The page has started the screen in [pgponyLaunch]. */
    fun pgponyLaunched() {
        pgponyLaunch = null
    }

    /** The screen could not be started at all. */
    fun pgponyLaunchFailed() {
        endPgpony(context.getString(R.string.pgpony_unreachable))
    }

    /**
     * A PGPony screen closed. Anything but OK ends the reveal. PGPony's
     * consent screen answers OK with no intent; its confirmation answers with
     * the intent to call again, which the agree step checks for itself.
     */
    fun pgponyResult(resultCode: Int, data: Intent?) {
        val then = afterPgpony ?: return
        afterPgpony = null
        if (resultCode != Activity.RESULT_OK) {
            endPgpony(context.getString(pgponyDeclined))
            return
        }
        runPgpony { then(data) }
    }

    /** The user backs out: closes the key list, or stops waiting for PGPony. */
    fun cancelPgpony() {
        pgponyJob?.cancel()
        endPgpony()
    }

    private fun pgponyErrorMessage(e: PgponyException): String = when (e) {
        is PgponyException.NotInstalled -> context.getString(R.string.pgpony_not_installed)
        is PgponyException.TooOld -> context.getString(R.string.pgpony_too_old)
        is PgponyException.Unreachable -> context.getString(R.string.pgpony_unreachable)
        is PgponyException.BadReply -> context.getString(R.string.pgpony_bad_reply)
        is PgponyException.Refused -> when (e.reason) {
            PgponyContract.REASON_UNKNOWN_KEY,
            PgponyContract.REASON_UNSUPPORTED_KEY,
            PgponyContract.REASON_KEY_UNAVAILABLE -> context.getString(R.string.pgpony_key_unavailable)
            PgponyContract.REASON_AGREEMENT_FAILED -> context.getString(R.string.pgpony_agreement_failed)
            // No reason: PGPony turned StegPony itself away, as it does when the
            // app's signature is not the one it allowed.
            null -> context.getString(R.string.pgpony_refused_app)
            // invalid_request, too_many_points, low_order_point, or a reason newer than this build.
            else -> context.getString(R.string.pgpony_refused, e.reason)
        }
    }

    val hasImage: Boolean get() = imageBytes != null && !loading

    /** Something to look in, for the current source. */
    val hasInput: Boolean
        get() = when (source) {
            RevealSource.Image -> hasImage
            RevealSource.Text -> pastedText.isNotBlank()
        }

    companion object {
        fun isSecretKey(text: String): Boolean = text.trim().startsWith("AGE-SECRET-KEY-1", ignoreCase = true)
    }

    /** Forgets what was revealed. */
    fun close() {
        outcome = null
        errorText = null
        if (source == RevealSource.Text) pastedText = ""
        Files.clearShared(context, Files.AREA_REVEAL)
    }
}
