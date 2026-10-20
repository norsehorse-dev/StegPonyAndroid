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
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
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
import uniffi.stegpony_ffi.filePayloadLen
import uniffi.stegpony_ffi.parseRecipient
import uniffi.stegpony_ffi.textPayloadLen

enum class PayloadKind { Text, File }
enum class KeyKind { Passphrase, Recipient }

/** The cover as read: its file, the core's view of it, and capacity per mode once known. */
class LoadedCover(val file: PickedFile, val cover: Cover, val format: ImageFormat) {
    /** Modes this cover can use, best first. */
    val modes: List<ImageMode> =
        if (format == ImageFormat.JPEG) listOf(ImageMode.JPEG, ImageMode.LOSSLESS) else listOf(ImageMode.LOSSLESS)
}

class HideResult(val bytes: ByteArray, val format: ImageFormat, val detectability: Detectability, val name: String) {
    val mime: String get() = if (format == ImageFormat.JPEG) "image/jpeg" else "image/png"
}

class HideViewModel(app: Application) : AndroidViewModel(app) {
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
    var recipient by mutableStateOf("")

    var decoyOn by mutableStateOf(false)
    var decoyText by mutableStateOf("")
    var decoyPassphrase by mutableStateOf("")

    var keepMetadata by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<HideResult?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)

    private val context get() = getApplication<Application>()

    fun pickCover(uri: Uri) {
        if (busy || loading) return
        errorText = null
        result = null
        loading = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val picked = Files.describe(context, uri)
                    val bytes = Files.readBytes(context, uri, Files.MAX_IMAGE_BYTES)
                    val c = Cover(bytes)
                    LoadedCover(picked, c, c.format())
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

    fun selectMode(m: ImageMode) {
        val c = cover ?: return
        if (busy || loading || m !in c.modes) return
        mode = m
        result = null
        if (m !in capacities) {
            viewModelScope.launch {
                loading = true
                try {
                    loadCapacity(c, m)
                } catch (e: Throwable) {
                    errorText = errorMessage(context, e)
                } finally {
                    loading = false
                }
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

    val capacity: CoverCapacity? get() = capacities[mode]

    /** Whether the payload (and decoy) fit in the current mode, once capacity is known. */
    val fit: Fit?
        get() {
            val c = cover ?: return null
            if (capacity == null) return null
            val len = payloadLen ?: return null
            return runCatching { c.cover.fit(mode, len.toULong(), decoyLen?.toULong()) }.getOrNull()
        }

    /** What still stands between the user and the Hide button, or null when ready. */
    val blocker: Int?
        get() = when {
            cover == null -> R.string.need_cover
            capacity == null -> R.string.reading_cover
            payloadLen == null -> R.string.need_payload
            fit?.fits != true -> R.string.error_does_not_fit
            keyKind == KeyKind.Passphrase && passphrase.isEmpty() -> R.string.need_passphrase
            keyKind == KeyKind.Passphrase && passphrase != passphraseAgain -> R.string.passphrases_differ
            keyKind == KeyKind.Recipient && recipient.isBlank() -> R.string.need_recipient
            decoyOn && decoyText.isEmpty() -> R.string.need_decoy_text
            decoyOn && decoyPassphrase.isEmpty() -> R.string.need_decoy_passphrase
            decoyOn && keyKind == KeyKind.Passphrase && decoyPassphrase == passphrase -> R.string.decoy_same_passphrase
            else -> null
        }

    fun hide() {
        val c = cover ?: return
        if (busy || blocker != null) return
        errorText = null
        result = null
        busy = true
        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.Default) {
                    val secret = when (keyKind) {
                        KeyKind.Passphrase -> HideSecret.Passphrase(passphrase)
                        KeyKind.Recipient -> HideSecret.Recipient(parseRecipient(recipient.trim()))
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
                    c.cover.hide(mode, payload, secret, decoy, keepMetadata && mode == ImageMode.JPEG)
                }
                val ext = if (out.format == ImageFormat.JPEG) "jpg" else "png"
                result = HideResult(out.image, out.format, out.detectability, Files.outputName(ext))
                // Secrets are not kept around once used.
                passphrase = ""
                passphraseAgain = ""
                decoyPassphrase = ""
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            } finally {
                busy = false
            }
        }
    }

    fun save(uri: Uri) {
        val r = result ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Files.write(context, uri, r.bytes) }
            } catch (e: Throwable) {
                errorText = errorMessage(context, e)
            }
        }
    }

    /** Starts over with a new cover; the payload and keys stay. */
    fun clearResult() {
        result = null
    }

    override fun onCleared() {
        cover?.cover?.destroy()
        super.onCleared()
    }

    companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
