package com.stegpony.android.ui.hide

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.io.PickedFile
import android.media.ExifInterface
import com.stegpony.android.keys.CoverHistory
import com.stegpony.android.keys.Keyring
import java.io.ByteArrayInputStream
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.Channel
import uniffi.stegpony_ffi.ChannelProfile
import uniffi.stegpony_ffi.Cover
import uniffi.stegpony_ffi.CoverCapacity
import uniffi.stegpony_ffi.Decoy
import uniffi.stegpony_ffi.Detectability
import uniffi.stegpony_ffi.Fit
import uniffi.stegpony_ffi.HideSecret
import uniffi.stegpony_ffi.ImageFormat
import uniffi.stegpony_ffi.ImageMode
import uniffi.stegpony_ffi.Payload
import uniffi.stegpony_ffi.PreparationReport
import uniffi.stegpony_ffi.StegException
import uniffi.stegpony_ffi.TextFit
import uniffi.stegpony_ffi.TextMode
import uniffi.stegpony_ffi.channelProfiles
import uniffi.stegpony_ffi.filePayloadLen
import uniffi.stegpony_ffi.hideText
import uniffi.stegpony_ffi.parseRecipient
import uniffi.stegpony_ffi.textFit
import uniffi.stegpony_ffi.textPayloadLen

/** What the payload is hidden in: a photo, or text to paste somewhere. */
enum class Carrier { Image, Text }

enum class PayloadKind { Text, File }
enum class KeyKind { Passphrase, Recipient }

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
) {
    /** Modes this cover can use, best first. */
    val modes: List<ImageMode> =
        if (format == ImageFormat.JPEG) listOf(ImageMode.JPEG, ImageMode.LOSSLESS) else listOf(ImageMode.LOSSLESS)
}

/** How the hidden image will travel, which decides what it is hidden in. */
enum class SendAs {
    /** Unchanged, as a file or document: the cover is used as it is. */
    File,

    /**
     * As an ordinary Telegram photo (experimental). Telegram recompresses photos,
     * so the core first makes a copy the way Telegram would, and the payload is
     * hidden in that copy in native JPEG mode.
     */
    TelegramPhoto,
}

/**
 * [source] re-encoded by the core for a channel, with that copy's JPEG capacity.
 * Made once per cover and channel; the source cover is left as it was.
 */
class ChannelCover(
    val source: LoadedCover,
    val cover: Cover,
    val report: PreparationReport,
    val capacity: CoverCapacity,
)

class HideResult(
    val bytes: ByteArray,
    val format: ImageFormat,
    val detectability: Detectability,
    val name: String,
    val sentAs: SendAs,
) {
    val mime: String get() = if (format == ImageFormat.JPEG) "image/jpeg" else "image/png"
}

/** Text with the payload in it: StegPony words, or the cover message with invisible characters. */
class TextResult(val text: String, val mode: TextMode, val size: Long, val name: String)

class HideViewModel(app: Application) : AndroidViewModel(app) {
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

    var sendAs by mutableStateOf(SendAs.File)
        private set
    /** The current cover prepared for Telegram, once made. Kept when switching back to File. */
    var channelCover by mutableStateOf<ChannelCover?>(null)
        private set
    /** A Telegram copy of the current cover is being made. */
    var preparing by mutableStateOf(false)
        private set
    /** Why the current cover couldn't be prepared for Telegram. */
    var prepareError by mutableStateOf<String?>(null)
        private set
    /** The cover a preparation is running for; results for any other cover are stale. */
    private var preparingFor: LoadedCover? = null
    /** The cover whose preparation failed. Preparation is deterministic, so it isn't retried. */
    private var preparationFailedFor: LoadedCover? = null

    /** The core's Telegram profile, for its experimental status and verification date. */
    val telegramProfile: ChannelProfile? by lazy {
        runCatching { channelProfiles().firstOrNull { it.channel == Channel.TELEGRAM_PHOTO_V1 } }.getOrNull()
    }

    var payloadKind by mutableStateOf(PayloadKind.Text)
    var text by mutableStateOf("")
    var file by mutableStateOf<PickedFile?>(null)
        private set

    var keyKind by mutableStateOf(KeyKind.Passphrase)
    var passphrase by mutableStateOf("")
    var passphraseAgain by mutableStateOf("")
    /** A pasted age1… key, used when no saved key is picked. */
    var recipient by mutableStateOf("")
    /** The recipient of a contact or one of your own keys, picked from the keyring. */
    var pickedRecipient by mutableStateOf<String?>(null)

    /** A picked key counts only while it is still in the keyring. */
    private val effectiveRecipient: String
        get() {
            val keys = Keyring.current
            val picked = pickedRecipient?.takeIf { r ->
                keys.contacts.any { it.recipient == r } || keys.mine.any { it.recipient == r }
            }
            return picked ?: recipient.trim()
        }

    var decoyOn by mutableStateOf(false)
    var decoyText by mutableStateOf("")
    var decoyPassphrase by mutableStateOf("")

    var keepMetadata by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<HideResult?>(null)
        private set
    var textResult by mutableStateOf<TextResult?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)

    private val context get() = getApplication<Application>()

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
                    LoadedCover(picked, c, format, fingerprint, notes, fresh)
                }
                cover?.cover?.destroy()
                dropChannelCover()
                cover = loaded
                capacities.clear()
                mode = loaded.modes.first()
                // Only the capacity the chosen way of sending needs; the other is
                // worked out if the user switches.
                if (sendAs == SendAs.File) loadCapacity(loaded, mode)
            } catch (e: Throwable) {
                // A cover whose capacity can't be worked out is no use; drop it
                // so the screen doesn't wait on it forever.
                cover?.cover?.destroy()
                dropChannelCover()
                cover = null
                capacities.clear()
                errorText = errorMessage(context, e)
            } finally {
                loading = false
            }
            cover?.takeIf { sendAs == SendAs.TelegramPhoto }?.let(::prepareForTelegram)
        }
    }

    fun selectSendAs(s: SendAs) {
        if (busy || loading || s == sendAs) return
        sendAs = s
        result = null
        errorText = null
        val c = cover ?: return
        when (s) {
            SendAs.TelegramPhoto -> prepareForTelegram(c)
            SendAs.File -> ensureCapacity(c, mode)
        }
    }

    /**
     * Makes the Telegram copy of [source] in the background, once per cover. The
     * native call can't be interrupted, so a result that arrives after the cover
     * changed is thrown away rather than shown.
     */
    private fun prepareForTelegram(source: LoadedCover) {
        if (channelCover?.source === source || preparingFor === source || preparationFailedFor === source) return
        preparingFor = source
        preparing = true
        prepareError = null
        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.Default) {
                    val prepared = source.cover.prepareForChannel(Channel.TELEGRAM_PHOTO_V1)
                    try {
                        ChannelCover(source, prepared.cover, prepared.report, prepared.cover.capacity(ImageMode.JPEG))
                    } catch (e: Throwable) {
                        prepared.cover.destroy()
                        throw e
                    }
                }
                if (cover === source) {
                    channelCover?.cover?.destroy()
                    channelCover = ready
                } else {
                    ready.cover.destroy()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (cover === source) {
                    preparationFailedFor = source
                    prepareError = preparationMessage(e)
                }
            } finally {
                if (preparingFor === source) {
                    preparingFor = null
                    preparing = false
                }
            }
        }
    }

    private fun dropChannelCover() {
        channelCover?.cover?.destroy()
        channelCover = null
        prepareError = null
        preparationFailedFor = null
        // A preparation still running belongs to the old cover; its result is discarded.
        preparingFor = null
        preparing = false
    }

    private fun preparationMessage(e: Throwable): String = when (e) {
        is StegException.PreparationFailed -> context.getString(R.string.error_telegram_headroom)
        is StegException.UnsupportedCover -> context.getString(R.string.error_telegram_unsupported)
        is StegException.CoverTooSmall, is OutOfMemoryError -> errorMessage(context, e)
        else -> context.getString(R.string.error_telegram_failed)
    }

    fun selectCarrier(c: Carrier) {
        if (busy || c == carrier) return
        carrier = c
        result = null
        textResult = null
        errorText = null
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
                // be read, drop the cover as loading would have (a cover loaded for
                // Telegram skips this check until the user switches to File).
                if (cover === c && m == c.modes.first()) {
                    c.cover.destroy()
                    dropChannelCover()
                    cover = null
                    capacities.clear()
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
        get() = if (decoyOn && decoyText.isNotEmpty()) textPayloadLen(decoyText).toLong() else null

    /** The Telegram copy of the current cover, when it is ready. */
    private val currentChannelCover: ChannelCover?
        get() = channelCover?.takeIf { it.source === cover }

    /** The mode the image is hidden in: Telegram copies always use native JPEG. */
    val hideMode: ImageMode
        get() = if (sendAs == SendAs.TelegramPhoto) ImageMode.JPEG else mode

    /** What the payload goes into: the cover itself, or its Telegram copy. */
    private val hideCover: Cover?
        get() = if (sendAs == SendAs.TelegramPhoto) currentChannelCover?.cover else cover?.cover

    val capacity: CoverCapacity?
        get() = if (sendAs == SendAs.TelegramPhoto) currentChannelCover?.capacity else capacities[mode]

    /** The report for the Telegram copy shown on screen, when it is ready. */
    val preparationReport: PreparationReport?
        get() = currentChannelCover?.report

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
            val c = hideCover ?: return null
            if (capacity == null) return null
            val len = payloadLen ?: return null
            return runCatching { c.fit(hideMode, len.toULong(), decoyLen?.toULong()) }.getOrNull()
        }

    /** What still stands between the user and the Hide button, or null when ready. */
    val blocker: Int?
        get() = when {
            carrier == Carrier.Image && cover == null -> R.string.need_cover
            carrier == Carrier.Image && sendAs == SendAs.TelegramPhoto && preparing -> R.string.preparing_telegram
            carrier == Carrier.Image && sendAs == SendAs.TelegramPhoto && capacity == null -> R.string.telegram_not_ready
            carrier == Carrier.Image && capacity == null -> R.string.reading_cover
            payloadLen == null -> R.string.need_payload
            carrier == Carrier.Image && fit?.fits != true ->
                if (sendAs == SendAs.TelegramPhoto) R.string.error_does_not_fit_telegram else R.string.error_does_not_fit
            carrier == Carrier.Text && textFit?.fits != true -> R.string.error_text_too_long
            carrier == Carrier.Text && textMode == TextMode.ZERO_WIDTH && coverText.isBlank() -> R.string.need_cover_text
            keyKind == KeyKind.Passphrase && passphrase.isEmpty() -> R.string.need_passphrase
            keyKind == KeyKind.Passphrase && passphrase != passphraseAgain -> R.string.passphrases_differ
            keyKind == KeyKind.Recipient && effectiveRecipient.isEmpty() -> R.string.need_recipient
            decoyOn && decoyText.isEmpty() -> R.string.need_decoy_text
            decoyOn && decoyPassphrase.isEmpty() -> R.string.need_decoy_passphrase
            decoyOn && keyKind == KeyKind.Passphrase && decoyPassphrase == passphrase -> R.string.decoy_same_passphrase
            else -> null
        }

    fun hide() {
        if (busy || blocker != null) return
        val c = cover
        // Fixed for this hide: the cover can't change while busy, and the payload
        // goes into the Telegram copy without preparing it again.
        val target = hideCover
        val targetMode = hideMode
        val sentAs = sendAs
        if (carrier == Carrier.Image && (c == null || target == null)) return
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
                val decoy = if (decoyOn) Decoy(Payload.Text(decoyText), HideSecret.Passphrase(decoyPassphrase)) else null
                if (carrier == Carrier.Text) {
                    val mode = textMode
                    val size = textFit?.size?.toLong() ?: 0L
                    val out = withContext(Dispatchers.Default) {
                        hideText(mode, if (mode == TextMode.ZERO_WIDTH) coverText else "", payload, secret, decoy)
                    }
                    textResult = TextResult(out, mode, size, Files.textOutputName())
                } else {
                    val loaded = c ?: return@launch
                    val into = target ?: return@launch
                    // A Telegram copy carries no private metadata, and Telegram strips it anyway.
                    val keep = keepMetadata && targetMode == ImageMode.JPEG && sentAs == SendAs.File
                    val out = withContext(Dispatchers.Default) {
                        into.hide(targetMode, payload, secret, decoy, keep)
                    }
                    val ext = if (out.format == ImageFormat.JPEG) "jpg" else "png"
                    result = HideResult(out.image, out.format, out.detectability, Files.outputName(ext), sentAs)
                    // Remember the cover, and the output, so neither is used as a
                    // cover again without a warning. Best effort.
                    runCatching { CoverHistory.add(context, loaded.fingerprint, CoverHistory.fingerprint(out.image)) }
                }
                // Secrets are not kept around once used.
                passphrase = ""
                passphraseAgain = ""
                decoyPassphrase = ""
            } catch (e: Throwable) {
                errorText = if (carrier == Carrier.Text && e is StegException.PayloadTooLarge) {
                    context.getString(R.string.error_text_too_long)
                } else if (carrier == Carrier.Image && sentAs == SendAs.TelegramPhoto && e is StegException.PayloadTooLarge) {
                    context.getString(R.string.error_does_not_fit_telegram)
                } else {
                    errorMessage(context, e)
                }
            } finally {
                busy = false
            }
        }
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

    /** Starts over with a new cover; the payload and keys stay. */
    fun clearResult() {
        result = null
        textResult = null
    }

    override fun onCleared() {
        cover?.cover?.destroy()
        channelCover?.cover?.destroy()
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
    }
}
