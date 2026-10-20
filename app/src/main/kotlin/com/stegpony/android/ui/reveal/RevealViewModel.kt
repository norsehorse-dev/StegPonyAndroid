package com.stegpony.android.ui.reveal

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegpony.android.io.Files
import com.stegpony.android.io.PickedFile
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.Payload
import uniffi.stegpony_ffi.RevealSecret
import uniffi.stegpony_ffi.reveal as revealImage

sealed interface RevealOutcome {
    data class Found(val payload: Payload) : RevealOutcome
    data object NothingFound : RevealOutcome
}

class RevealViewModel(app: Application) : AndroidViewModel(app) {
    var image by mutableStateOf<PickedFile?>(null)
        private set
    // State, not a plain field: canReveal reads it first, and a read Compose
    // can't track would leave the Reveal button stuck until something else
    // recomposed the screen.
    private var imageBytes by mutableStateOf<ByteArray?>(null)
    var loading by mutableStateOf(false)
        private set

    var passphrase by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<RevealOutcome?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)

    private val context get() = getApplication<Application>()

    /** Returns false when a reveal is running and the image wasn't taken. */
    fun open(uri: Uri): Boolean {
        if (busy) return false
        errorText = null
        outcome = null
        loading = true
        viewModelScope.launch {
            try {
                val (picked, bytes) = withContext(Dispatchers.IO) {
                    Files.describe(context, uri) to Files.readBytes(context, uri, Files.MAX_IMAGE_BYTES)
                }
                image = picked
                imageBytes = bytes
            } catch (e: Throwable) {
                image = null
                imageBytes = null
                errorText = errorMessage(context, e)
            } finally {
                loading = false
            }
        }
        return true
    }

    val canReveal: Boolean get() = imageBytes != null && passphrase.isNotEmpty() && !busy && !loading

    fun reveal() {
        val bytes = imageBytes ?: return
        if (!canReveal) return
        errorText = null
        outcome = null
        busy = true
        val secrets = listOf(RevealSecret.Passphrase(passphrase))
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.Default) { revealImage(bytes, secrets, emptyList()) }
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

    /** Forgets what was revealed. */
    fun close() {
        outcome = null
        Files.clearShared(context, Files.AREA_REVEAL)
    }
}
