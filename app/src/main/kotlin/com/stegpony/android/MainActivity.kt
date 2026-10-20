package com.stegpony.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.stegpony.android.io.Files
import com.stegpony.android.ui.StegPonyRoot
import com.stegpony.android.ui.theme.StegPonyTheme

/** Something another app handed to StegPony. */
sealed interface Incoming {
    /** An image to look inside: goes to Reveal. */
    data class Image(val uri: Uri) : Incoming

    /** Shared text, such as an age1… key: goes to Keys. */
    data class KeyText(val text: String) : Incoming

    /** A key file, such as PGPony's application/pgp-keys share: goes to Keys. */
    data class KeyFile(val uri: Uri) : Incoming
}

/**
 * Single activity. An image shared in, or opened with StegPony, goes straight
 * to Reveal; a key shared in goes to Keys.
 */
class MainActivity : AppCompatActivity() {
    private val incoming = mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            Files.clearShared(this)
            incoming.value = incomingFrom(intent)
        }
        setContent {
            StegPonyTheme {
                StegPonyRoot(
                    incoming = incoming.value,
                    onIncomingHandled = { incoming.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingFrom(intent)?.let { incoming.value = it }
    }

    private fun incomingFrom(intent: Intent?): Incoming? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> return null
        }
        val type = intent.type ?: uri?.let { contentResolver.getType(it) }.orEmpty()
        return when {
            type.startsWith("image/") -> uri?.let { Incoming.Image(it) }
            uri != null -> Incoming.KeyFile(uri)
            else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Incoming.KeyText(it) }
        }
    }
}
