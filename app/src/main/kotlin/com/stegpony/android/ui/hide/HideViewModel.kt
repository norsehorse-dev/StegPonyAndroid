package com.stegpony.android.ui.hide

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegpony.android.i18n.LanguageManager
import com.stegpony.android.R
import com.stegpony.android.format.Buckets
import com.stegpony.android.io.Files
import com.stegpony.android.io.PickedFile
import android.media.ExifInterface
import com.stegpony.android.keys.CoverHistory
import com.stegpony.android.keys.DuplicateKeyException
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.PassphraseGenerator
import com.stegpony.android.pgpony.PgponyClient
import com.stegpony.android.pgpony.PgponyContract
import com.stegpony.android.pgpony.PgponyException
import com.stegpony.android.pgpony.PgponyPick
import com.stegpony.android.pgpony.PgponyReply
import com.stegpony.android.pgpony.PickAnswer
import com.stegpony.android.pgpony.pgponyErrorMessage
import com.stegpony.android.ui.keys.KeysViewModel
import java.io.ByteArrayInputStream
import androidx.compose.ui.graphics.ImageBitmap
import com.stegpony.android.ui.common.Previews
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.Cover
import uniffi.stegpony_ffi.CoverCapacity
import uniffi.stegpony_ffi.Decoy
import uniffi.stegpony_ffi.Detectability
import uniffi.stegpony_ffi.Fit
import uniffi.stegpony_ffi.HideSecret
import uniffi.stegpony_ffi.ImageFormat
import uniffi.stegpony_ffi.ImageMode
import uniffi.stegpony_ffi.Payload
import uniffi.stegpony_ffi.StegException
import uniffi.stegpony_ffi.TextFit
import uniffi.stegpony_ffi.TextMode
import uniffi.stegpony_ffi.filePayloadLen
import uniffi.stegpony_ffi.hideText
import uniffi.stegpony_ffi.parseRecipient
import uniffi.stegpony_ffi.pgpPublicKey
import uniffi.stegpony_ffi.textFit
import uniffi.stegpony_ffi.textPayloadLen

/** The steps of hiding, in order. [Done] shows the result. */
enum class HideStep { Cover, Payload, Key, Review, Done }

/** What the payload is hidden in: a photo, or text to paste somewhere. */
enum class Carrier { Image, Text }

enum class PayloadKind { Text, File }
enum class KeyKind { Passphrase, Recipient }

/** What the decoy is: a message typed here, or a file. */
enum class DecoyKind { Text, File }

/** A public key picked in PGPony for this hide. It is not in the keyring unless the user saves it. */
data class PgponyRecipient(val name: String, val recipient: String, val fingerprint: String?)

/** Something worth knowing about a cover before hiding in it. */
enum class CoverNote {
    /** You've hidden in this photo before (or it is something StegPony wrote). */
    Reused,

    /** A JPEG without camera details: maybe a download or a saved messenger image, which may exist elsewhere. */
    NoCameraDetails,

    /** Picked from Downloads, so a copy may exist online. */
    FromDownloads,
}

/** The cover as read: its file, the core's view of it, and capacity per mode once known. */
class LoadedCover(
    val file: PickedFile,
    val cover: Cover,
    val format: ImageFormat,
    /** See [CoverHistory.fingerprint]. */
    val fingerprint: String,
    val notes: List<CoverNote>,
    /** Taken just now with the in-app camera. */
    val fresh: Boolean,
    /** A small picture of it for the screen; the core works from the file bytes. */
    val preview: ImageBitmap?,
) {
    /** Modes this cover can use, best first. */
    val modes: List<ImageMode> =
        if (format == ImageFormat.JPEG) listOf(ImageMode.JPEG, ImageMode.LOSSLESS) else listOf(ImageMode.LOSSLESS)
}

class HideResult(
    val bytes: ByteArray,
    val format: ImageFormat,
    val detectability: Detectability,
    val name: String,
    /** A small picture of the result for the screen. */
    val preview: ImageBitmap?,
) {
    val mime: String get() = if (format == ImageFormat.JPEG) "image/jpeg" else "image/png"
}

/** Text with the payload in it: StegPony words, or the cover message with invisible characters. */
class TextResult(val text: String, val mode: TextMode, val size: Long, val name: String)

class HideViewModel(app: Application) : AndroidViewModel(app) {
    /** Where the user is in the flow. Kept here so it survives switching tabs. */
    var step by mutableStateOf(HideStep.Cover)
        private set

    var carrier by mutableStateOf(Carrier.Image)
        private set
    var textMode by mutableStateOf(TextMode.WORDS)
        private set
    /** The visible message invisible characters are placed in. */
    var coverText by mutableStateOf("")

    var cover by mutableStateOf<LoadedCover?>(null)
        private set
    var mode by mutableStateOf(ImageMode.JPEG)
        private set
    /** Capacity per mode for the current cover; a mode is missing until it has been worked out. */
    val capacities = mutableStateMapOf<ImageMode, CoverCapacity>()
    var loading by mutableStateOf(false)
        private set

    var payloadKind by mutableStateOf(PayloadKind.Text)
    var text by mutableStateOf("")
    var file by mutableStateOf<PickedFile?>(null)
        private set

    var keyKind by mutableStateOf(KeyKind.Passphrase)
    var passphrase by mutableStateOf("")
    var passphraseAgain by mutableStateOf("")
    /** A passphrase the app made, shown in the clear on the key step while it is still the one in the fields. */
    var generatedPassphrase by mutableStateOf<String?>(null)
        private set
    /** A pasted age1… key, used when no saved key is picked. */
    var recipient by mutableStateOf("")
    /** The recipient of a contact or one of your own keys, picked from the keyring. */
    var pickedRecipient by mutableStateOf<String?>(null)

    /** A picked key counts only while it is still in the keyring, or is the key picked in PGPony. */
    private val effectiveRecipient: String
        get() {
            val keys = Keyring.current
            val picked = pickedRecipient?.takeIf { r ->
                keys.contacts.any { it.recipient == r } || keys.mine.any { it.recipient == r } ||
                    pgponyRecipient?.recipient == r
            }
            return picked ?: recipient.trim()
        }

    // ── A recipient picked in PGPony ─────────────────────────────────────
    //
    // PGPony's OpenPGP provider shows its own public key picker and hands the
    // picked key over. Public keys only: nothing here asks for a passphrase or
    // touches a secret key.

    /** The key last picked in PGPony, offered in the list beside the saved keys. */
    var pgponyRecipient by mutableStateOf<PgponyRecipient?>(null)
        private set

    /** PGPony is being asked, or one of its screens is up. */
    var pgponyBusy by mutableStateOf(false)
        private set

    /** A PGPony screen for the page to start. Cleared by [pgponyLaunched]. */
    var pgponyLaunch by mutableStateOf<PendingIntent?>(null)
        private set

    /** What to do when the PGPony screen now showing returns OK; null when none is expected. */
    private var afterPgpony: (suspend (Intent?) -> Unit)? = null
    private var pgponyJob: Job? = null

    fun pgponyInstalled(context: Context): Boolean = PgponyClient.isInstalled(context)

    fun pickFromPgpony() {
        if (busy || pgponyBusy) return
        errorText = null
        runPgpony { askForKey(PgponyClient.request(PgponyContract.ACTION_GET_KEY_IDS), pickerShown = false, round = 0) }
    }

    /** One step with PGPony. Any failure ends the pick with a message. */
    private fun runPgpony(step: suspend () -> Unit) {
        pgponyBusy = true
        pgponyJob = viewModelScope.launch {
            try {
                step()
            } catch (e: PgponyException) {
                endPgpony(pgponyErrorMessage(context, e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                endPgpony(errorMessage(context, e))
            }
        }
    }

    private fun endPgpony(message: String? = null) {
        pgponyBusy = false
        pgponyLaunch = null
        afterPgpony = null
        if (message != null) errorText = message
    }

    /**
     * Asks PGPony for the picked key ids. PGPony may answer with a screen
     * first, twice at most: its Connected apps consent on first use, which
     * returns no intent and is followed by the same request again, and then
     * its key picker, which returns the request with the picks in it.
     */
    private suspend fun askForKey(request: Intent, pickerShown: Boolean, round: Int) {
        val app = getApplication<Application>()
        when (val reply = PgponyClient.call(app, request)) {
            is PgponyReply.NeedsUser -> {
                // Consent, then the picker. A third screen is not in the contract.
                if (round >= MAX_PGPONY_SCREENS) throw PgponyException.BadReply()
                afterPgpony = { returned ->
                    if (returned != null) {
                        askForKey(returned, pickerShown = true, round = round + 1)
                    } else {
                        askForKey(request, pickerShown, round + 1)
                    }
                }
                pgponyLaunch = reply.pending
            }
            is PgponyReply.Error -> {
                // A PGPony that doesn't take our API version is an old one.
                if (reply.reason == null && reply.errorId == PgponyContract.ERROR_INCOMPATIBLE_API_VERSIONS) {
                    endPgpony(context.getString(R.string.pgpony_too_old_pick))
                } else {
                    throw PgponyException.Refused(reply.reason)
                }
            }
            is PgponyReply.Success -> {
                val ids = reply.data.getLongArrayExtra(PgponyContract.RESULT_KEY_IDS)
                when (val answer = PgponyPick.read(ids, pickerShown)) {
                    PickAnswer.TooOld -> endPgpony(context.getString(R.string.pgpony_too_old_pick))
                    PickAnswer.None -> endPgpony(context.getString(R.string.pgpony_pick_none))
                    PickAnswer.Several -> endPgpony(context.getString(R.string.pgpony_pick_one))
                    is PickAnswer.One -> fetchPgponyKey(answer.keyId)
                }
            }
        }
    }

    private suspend fun fetchPgponyKey(keyId: Long) {
        val app = getApplication<Application>()
        val request = PgponyClient.request(PgponyContract.ACTION_GET_KEY).putExtra(PgponyContract.EXTRA_KEY_ID, keyId)
        val (reply, bytes) = PgponyClient.fetch(app, request)
        when (reply) {
            // PGPony's text for a key it can't hand over is written for a mail
            // app's user, so it is not shown; ours says what to do.
            is PgponyReply.Error -> endPgpony(context.getString(R.string.pgpony_key_not_given))
            // The picker was just answered; asking again here is not in the contract.
            is PgponyReply.NeedsUser -> throw PgponyException.BadReply()
            is PgponyReply.Success -> {
                val info = try {
                    withContext(Dispatchers.Default) { pgpPublicKey(bytes) }
                } catch (e: StegException.BadKey) {
                    // RSA, NIST curves, post-quantum only: no Curve25519 encryption subkey.
                    return endPgpony(context.getString(R.string.pgpony_pick_unusable))
                }
                val name = info.userIds.firstOrNull()?.let { KeysViewModel.displayName(it) }
                    ?: context.getString(R.string.pgp_key_label)
                pgponyRecipient = PgponyRecipient(name, info.recipient, info.subkeyFingerprint)
                pickedRecipient = info.recipient
                keyKind = KeyKind.Recipient
                endPgpony()
            }
        }
    }

    /** The page has started the screen in [pgponyLaunch]. */
    fun pgponyLaunched() {
        pgponyLaunch = null
    }

    /** The screen could not be started at all. */
    fun pgponyLaunchFailed() {
        endPgpony(context.getString(R.string.pgpony_unreachable))
    }

    /** A PGPony screen closed. Anything but OK ends the pick, with nothing said: the user changed their mind. */
    fun pgponyResult(resultCode: Int, data: Intent?) {
        val then = afterPgpony ?: return
        afterPgpony = null
        if (resultCode != Activity.RESULT_OK) {
            endPgpony()
            return
        }
        runPgpony { then(data) }
    }

    /** Gives up a pick that is under way: the user stops waiting, or leaves the key step. */
    fun cancelPgpony() {
        pgponyJob?.cancel()
        endPgpony()
    }

    /** Whether the key picked in PGPony is neither a contact nor one of your own keys yet. */
    val pgponyRecipientUnsaved: Boolean
        get() {
            val r = pgponyRecipient?.recipient ?: return false
            val keys = Keyring.current
            return keys.contacts.none { it.recipient == r } && keys.mine.none { it.recipient == r }
        }

    /** Adds the key picked in PGPony to the keyring as a contact. */
    fun savePgponyRecipient() {
        val pick = pgponyRecipient ?: return
        if (!pgponyRecipientUnsaved) return
        viewModelScope.launch {
            try {
                Keyring.addContact(context, pick.name, pick.recipient, pick.fingerprint)
            } catch (e: DuplicateKeyException) {
                // Tapped twice: the first tap saved it.
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            }
        }
    }

    var decoyOn by mutableStateOf(false)
    var decoyKind by mutableStateOf(DecoyKind.Text)
    var decoyText by mutableStateOf("")
    var decoyFile by mutableStateOf<PickedFile?>(null)
        private set
    var decoyPassphrase by mutableStateOf("")

    /**
     * Set by the user to hide a file with a decoy that is shorter than it
     * should be (see [decoyShortBy]). Never offered for a text message, where
     * a longer decoy is easy to write.
     */
    val decoyShortAccepted: Boolean
        get() = acceptedShort != null && acceptedShort == shortKey

    /** The message and decoy lengths the user accepted a short decoy for; any change asks again. */
    private var acceptedShort by mutableStateOf<Pair<Long?, Long?>?>(null)
    private val shortKey: Pair<Long?, Long?> get() = payloadLen to decoyLen

    fun acceptShortDecoy(accept: Boolean) {
        acceptedShort = if (accept) shortKey else null
    }

    fun pickDecoyFile(uri: Uri) {
        errorText = null
        viewModelScope.launch {
            try {
                decoyFile = withContext(Dispatchers.IO) {
                    val described = Files.describe(context, uri)
                    // Some providers don't report a size; count it, so the size check is right.
                    if (described.size != null) {
                        described
                    } else {
                        described.copy(size = Files.readBytes(context, uri, Files.MAX_PAYLOAD_BYTES).size.toLong())
                    }
                }
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            }
        }
    }

    /**
     * How many bytes the decoy is short of the message's size class, or 0.
     * Both slots are written with one size class, the larger of the two, and
     * whoever opens the decoy sees how much of it the decoy used. A decoy that
     * would have fit a smaller class shows that something larger is hidden
     * beside it.
     */
    val decoyShortBy: Long
        get() {
            val payload = payloadLen ?: return 0
            val decoy = decoyLen ?: return 0
            return Buckets.decoyShortBy(payload, decoy)
        }

    /** A short decoy stops a text message outright, and a file until the user says to go on. */
    private val decoyTooShort: Boolean
        get() = decoyShortBy > 0 && (payloadKind == PayloadKind.Text || !decoyShortAccepted)

    private fun framedLen(p: Payload): Long = when (p) {
        is Payload.Text -> textPayloadLen(p.text).toLong()
        is Payload.File -> filePayloadLen(p.name, p.mime, p.data.size.toULong()).toLong()
    }

    private val needDecoy: Int
        get() = if (decoyKind == DecoyKind.File) R.string.need_decoy_file else R.string.need_decoy_text

    var keepMetadata by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<HideResult?>(null)
        private set
    var textResult by mutableStateOf<TextResult?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)

    // Localized, so messages follow the in-app language before Android 13 too.
    private val context get() = LanguageManager.localized(getApplication<Application>())

    fun pickCover(uri: Uri) = loadCover(fresh = false) {
        Files.describe(context, uri) to Files.readBytes(context, uri, Files.MAX_IMAGE_BYTES)
    }

    /** A photo just taken with the in-app camera. */
    fun useCaptured(bytes: ByteArray) = loadCover(fresh = true) {
        PickedFile(Uri.EMPTY, Files.outputName("jpg"), bytes.size.toLong(), "image/jpeg") to bytes
    }

    private fun loadCover(fresh: Boolean, read: suspend () -> Pair<PickedFile, ByteArray>) {
        if (busy || loading) return
        errorText = null
        result = null
        loading = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val (picked, bytes) = read()
                    val c = Cover(bytes)
                    val format = c.format()
                    val fingerprint = CoverHistory.fingerprint(bytes)
                    val notes = buildList {
                        if (CoverHistory.contains(context, fingerprint)) add(CoverNote.Reused)
                        if (!fresh && isDownload(picked.uri)) add(CoverNote.FromDownloads)
                        if (!fresh && format == ImageFormat.JPEG && !hasCameraDetails(bytes)) add(CoverNote.NoCameraDetails)
                    }
                    LoadedCover(picked, c, format, fingerprint, notes, fresh, Previews.decode(bytes))
                }
                cover?.cover?.destroy()
                cover = loaded
                capacities.clear()
                mode = loaded.modes.first()
                loadCapacity(loaded, mode)
            } catch (e: Throwable) {
                // A cover whose capacity can't be worked out is no use; drop it
                // so the screen doesn't wait on it forever.
                cover?.cover?.destroy()
                cover = null
                capacities.clear()
                errorText = errorMessage(context, e)
            } finally {
                loading = false
            }
        }
    }

    fun selectCarrier(c: Carrier) {
        if (busy || c == carrier) return
        carrier = c
        result = null
        textResult = null
        errorText = null
    }

    /**
     * The welcome tour hides a typed message in a photo under a passphrase.
     * Puts the choices there without touching the cover or what was typed.
     */
    fun prepareForTour() {
        if (busy) return
        if (carrier != Carrier.Image) selectCarrier(Carrier.Image)
        payloadKind = PayloadKind.Text
        keyKind = KeyKind.Passphrase
        decoyOn = false
    }

    fun selectTextMode(m: TextMode) {
        if (busy) return
        textMode = m
        textResult = null
    }

    fun selectMode(m: ImageMode) {
        val c = cover ?: return
        if (busy || loading || m !in c.modes) return
        mode = m
        result = null
        ensureCapacity(c, m)
    }

    /** Works out the cover's capacity in [m] unless it is already known. */
    private fun ensureCapacity(c: LoadedCover, m: ImageMode) {
        if (m in capacities) return
        viewModelScope.launch {
            loading = true
            try {
                loadCapacity(c, m)
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
                // The cover's main mode is what a cover is loaded for; if that can't
                // be read, drop the cover as loading would have.
                if (cover === c && m == c.modes.first()) {
                    c.cover.destroy()
                    cover = null
                    capacities.clear()
                } else if (cover === c) {
                    // The other mode can't be read: go back to the one that works,
                    // so the step isn't left waiting on a capacity that never comes.
                    mode = c.modes.first()
                }
            } finally {
                loading = false
            }
        }
    }

    /** Reads the cover in this mode (a JPEG's coefficients, or its pixels for Lossless). */
    private suspend fun loadCapacity(c: LoadedCover, m: ImageMode) {
        val cap = withContext(Dispatchers.Default) { c.cover.capacity(m) }
        if (cover === c) capacities[m] = cap
    }

    fun pickFile(uri: Uri) {
        errorText = null
        result = null
        viewModelScope.launch {
            try {
                file = withContext(Dispatchers.IO) {
                    val described = Files.describe(context, uri)
                    // Some providers don't report a size; count it, so the meter is right.
                    if (described.size != null) {
                        described
                    } else {
                        described.copy(size = Files.readBytes(context, uri, Files.MAX_PAYLOAD_BYTES).size.toLong())
                    }
                }
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            }
        }
    }

    /** The payload's size as the core counts it, or null when there is nothing to hide yet. */
    val payloadLen: Long?
        get() = when (payloadKind) {
            PayloadKind.Text -> if (text.isEmpty()) null else textPayloadLen(text).toLong()
            PayloadKind.File -> file?.let {
                filePayloadLen(it.name, it.mime ?: OCTET_STREAM, (it.size ?: 0L).toULong()).toLong()
            }
        }

    private val decoyLen: Long?
        get() = when {
            !decoyOn -> null
            decoyKind == DecoyKind.Text -> if (decoyText.isEmpty()) null else textPayloadLen(decoyText).toLong()
            else -> decoyFile?.let {
                filePayloadLen(it.name, it.mime ?: OCTET_STREAM, (it.size ?: 0L).toULong()).toLong()
            }
        }

    val capacity: CoverCapacity?
        get() = capacities[mode]

    /** How big the hidden text will be, or null when there is nothing to hide yet. */
    val textFit: TextFit?
        get() {
            val len = payloadLen ?: return null
            return textFit(textMode, len.toULong(), decoyLen?.toULong())
        }

    /** Bytes the message (or decoy) can grow before the hidden text gets longer. */
    val textRoom: Long?
        get() {
            val f = textFit?.takeIf { it.fits } ?: return null
            return (f.bucketPayloadBytes.toLong() - maxOf(payloadLen ?: 0L, decoyLen ?: 0L)).coerceAtLeast(0L)
        }

    /** Whether the payload (and decoy) fit in the current mode, once capacity is known. */
    val fit: Fit?
        get() {
            val c = cover?.cover ?: return null
            if (capacity == null) return null
            val len = payloadLen ?: return null
            return runCatching { c.fit(mode, len.toULong(), decoyLen?.toULong()) }.getOrNull()
        }

    /** What still stands between the user and the Hide button, or null when ready. */
    val blocker: Int?
        get() = when {
            carrier == Carrier.Image && cover == null -> R.string.need_cover
            carrier == Carrier.Image && capacity == null -> R.string.reading_cover
            payloadLen == null -> R.string.need_payload
            carrier == Carrier.Image && fit?.fits != true -> R.string.error_does_not_fit
            carrier == Carrier.Text && textFit?.fits != true -> R.string.error_text_too_long
            carrier == Carrier.Text && textMode == TextMode.ZERO_WIDTH && coverText.isBlank() -> R.string.need_cover_text
            keyKind == KeyKind.Passphrase && passphrase.isEmpty() -> R.string.need_passphrase
            keyKind == KeyKind.Passphrase && passphrase != passphraseAgain -> R.string.passphrases_differ
            keyKind == KeyKind.Recipient && effectiveRecipient.isEmpty() -> R.string.need_recipient
            decoyOn && decoyLen == null -> needDecoy
            decoyOn && decoyTooShort -> R.string.need_longer_decoy
            decoyOn && decoyPassphrase.isEmpty() -> R.string.need_decoy_passphrase
            decoyOn && keyKind == KeyKind.Passphrase && decoyPassphrase == passphrase -> R.string.decoy_same_passphrase
            else -> null
        }

    fun hide() {
        if (busy || blocker != null) return
        val c = cover
        // Fixed for this hide: the cover can't change while busy.
        val targetMode = mode
        if (carrier == Carrier.Image && c == null) return
        errorText = null
        result = null
        textResult = null
        busy = true
        viewModelScope.launch {
            try {
                val secret = when (keyKind) {
                    KeyKind.Passphrase -> HideSecret.Passphrase(passphrase)
                    KeyKind.Recipient -> HideSecret.Recipient(parseRecipient(effectiveRecipient))
                }
                val payload = when (payloadKind) {
                    PayloadKind.Text -> Payload.Text(text)
                    PayloadKind.File -> {
                        val f = file ?: throw IllegalStateException("no file")
                        val data = withContext(Dispatchers.IO) {
                            Files.readBytes(context, f.uri, Files.MAX_PAYLOAD_BYTES)
                        }
                        Payload.File(f.name, f.mime ?: OCTET_STREAM, data)
                    }
                }
                val decoy = if (decoyOn) {
                    val decoyPayload = when (decoyKind) {
                        DecoyKind.Text -> Payload.Text(decoyText)
                        DecoyKind.File -> {
                            val f = decoyFile ?: throw IllegalStateException("no decoy file")
                            val data = withContext(Dispatchers.IO) {
                                Files.readBytes(context, f.uri, Files.MAX_PAYLOAD_BYTES)
                            }
                            Payload.File(f.name, f.mime ?: OCTET_STREAM, data)
                        }
                    }
                    Decoy(decoyPayload, HideSecret.Passphrase(decoyPassphrase))
                } else {
                    null
                }
                // Checked again on the bytes that were read: a provider can report a size that is off.
                if (decoy != null && !(payloadKind == PayloadKind.File && decoyShortAccepted) &&
                    Buckets.decoyShortBy(framedLen(payload), framedLen(decoy.payload)) > 0
                ) {
                    errorText = context.getString(R.string.need_longer_decoy)
                    return@launch
                }
                if (carrier == Carrier.Text) {
                    val mode = textMode
                    val size = textFit?.size?.toLong() ?: 0L
                    val out = withContext(Dispatchers.Default) {
                        hideText(mode, if (mode == TextMode.ZERO_WIDTH) coverText else "", payload, secret, decoy)
                    }
                    textResult = TextResult(out, mode, size, Files.textOutputName())
                } else {
                    val loaded = c ?: return@launch
                    val keep = keepMetadata && targetMode == ImageMode.JPEG
                    val out = withContext(Dispatchers.Default) {
                        loaded.cover.hide(targetMode, payload, secret, decoy, keep)
                    }
                    val ext = if (out.format == ImageFormat.JPEG) "jpg" else "png"
                    val preview = withContext(Dispatchers.Default) { Previews.decode(out.image) }
                    result = HideResult(out.image, out.format, out.detectability, Files.outputName(ext), preview)
                    // Remember the cover, and the output, so neither is used as a
                    // cover again without a warning. Best effort.
                    runCatching { CoverHistory.add(context, loaded.fingerprint, CoverHistory.fingerprint(out.image)) }
                }
                step = HideStep.Done
                // Secrets are not kept around once used.
                passphrase = ""
                passphraseAgain = ""
                generatedPassphrase = null
                decoyPassphrase = ""
            } catch (e: Throwable) {
                errorText = if (carrier == Carrier.Text && e is StegException.PayloadTooLarge) {
                    context.getString(R.string.error_text_too_long)
                } else {
                    errorMessage(context, e)
                }
            } finally {
                busy = false
            }
        }
    }

    /** Fills both passphrase fields with six random words. */
    fun generatePassphrase() {
        if (busy) return
        val made = PassphraseGenerator.generate(PassphraseGenerator.list(context))
        passphrase = made
        passphraseAgain = made
        generatedPassphrase = made
    }

    fun save(uri: Uri) {
        val bytes = when (carrier) {
            Carrier.Image -> result?.bytes
            Carrier.Text -> textResult?.text?.toByteArray(Charsets.UTF_8)
        } ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Files.write(context, uri, bytes) }
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            }
        }
    }

    /**
     * Back to the first step with a clean slate. The cover goes, because a
     * photo that has been hidden in once must not be used again, and so do the
     * message, the decoy and the passphrases, so nothing secret is kept.
     */
    fun startOver() {
        if (busy) return
        result = null
        textResult = null
        errorText = null
        cover?.cover?.destroy()
        cover = null
        capacities.clear()
        coverText = ""
        text = ""
        file = null
        passphrase = ""
        passphraseAgain = ""
        generatedPassphrase = null
        decoyOn = false
        decoyKind = DecoyKind.Text
        decoyText = ""
        decoyFile = null
        decoyPassphrase = ""
        acceptedShort = null
        // A key picked in PGPony was for that hide only.
        cancelPgpony()
        if (pickedRecipient != null && pickedRecipient == pgponyRecipient?.recipient && pgponyRecipientUnsaved) {
            pickedRecipient = null
        }
        pgponyRecipient = null
        step = HideStep.Cover
    }

    fun next() {
        // Waiting on PGPony only holds the key step, where the pick lands.
        if (busy || loading || (step == HideStep.Key && pgponyBusy) || stepBlocker(step) != null) return
        errorText = null
        step = when (step) {
            HideStep.Cover -> HideStep.Payload
            HideStep.Payload -> HideStep.Key
            HideStep.Key -> HideStep.Review
            else -> step
        }
    }

    fun back() {
        if (busy) return
        // Leaving the key step gives up a pick that is still under way.
        if (pgponyBusy) cancelPgpony()
        errorText = null
        step = when (step) {
            HideStep.Payload -> HideStep.Cover
            HideStep.Key -> HideStep.Payload
            HideStep.Review -> HideStep.Key
            else -> step
        }
    }

    /** What keeps [s] from being finished, or null when it can be left. */
    fun stepBlocker(s: HideStep): Int? = when (s) {
        HideStep.Cover -> when {
            carrier == Carrier.Image && cover == null -> R.string.need_cover
            carrier == Carrier.Image && capacity == null -> R.string.reading_cover
            carrier == Carrier.Text && textMode == TextMode.ZERO_WIDTH && coverText.isBlank() -> R.string.need_cover_text
            else -> null
        }
        HideStep.Payload -> when {
            payloadLen == null -> R.string.need_payload
            carrier == Carrier.Image && fit?.fits != true -> R.string.error_does_not_fit
            carrier == Carrier.Text && textFit?.fits != true -> R.string.error_text_too_long
            else -> null
        }
        HideStep.Key -> when {
            keyKind == KeyKind.Passphrase && passphrase.isEmpty() -> R.string.need_passphrase
            keyKind == KeyKind.Passphrase && passphrase != passphraseAgain -> R.string.passphrases_differ
            keyKind == KeyKind.Recipient && effectiveRecipient.isEmpty() -> R.string.need_recipient
            decoyOn && decoyLen == null -> needDecoy
            decoyOn && decoyTooShort -> R.string.need_longer_decoy
            decoyOn && decoyPassphrase.isEmpty() -> R.string.need_decoy_passphrase
            decoyOn && keyKind == KeyKind.Passphrase && decoyPassphrase == passphrase -> R.string.decoy_same_passphrase
            // The decoy takes room too, so adding one can push the payload out.
            decoyOn && carrier == Carrier.Image && fit?.fits != true -> R.string.error_decoy_does_not_fit
            decoyOn && carrier == Carrier.Text && textFit?.fits != true -> R.string.error_decoy_does_not_fit
            else -> null
        }
        HideStep.Review, HideStep.Done -> blocker
    }

    /** Who it is locked for, as Review names them: a saved name, or the pasted key shortened. */
    val recipientSummary: String
        get() {
            val r = effectiveRecipient
            val keys = Keyring.current
            return keys.contacts.firstOrNull { it.recipient == r }?.name
                ?: keys.mine.firstOrNull { it.recipient == r }?.label
                ?: pgponyRecipient?.takeIf { it.recipient == r }?.name
                ?: if (r.length > 24) r.take(12) + "…" + r.takeLast(6) else r
        }

    override fun onCleared() {
        cover?.cover?.destroy()
        super.onCleared()
    }

    private fun isDownload(uri: Uri): Boolean {
        val authority = uri.authority.orEmpty()
        return authority == "com.android.providers.downloads.documents" ||
            authority == "downloads" ||
            uri.path.orEmpty().contains("/Download/", ignoreCase = true)
    }

    /** A camera writes its make or model into EXIF; downloads and messenger copies usually lose them. */
    private fun hasCameraDetails(bytes: ByteArray): Boolean = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        exif.getAttribute(ExifInterface.TAG_MAKE) != null || exif.getAttribute(ExifInterface.TAG_MODEL) != null
    }.getOrDefault(true)

    companion object {
        const val OCTET_STREAM = "application/octet-stream"

        /** PGPony's screens before a pick is answered: its consent, then its picker. */
        private const val MAX_PGPONY_SCREENS = 2
    }
}
