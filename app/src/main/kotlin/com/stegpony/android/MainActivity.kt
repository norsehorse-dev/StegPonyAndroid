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

/**
 * Single activity. An image shared in, or opened with StegPony, goes straight
 * to Reveal.
 */
class MainActivity : AppCompatActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            Files.clearShared(this)
            incoming.value = imageIn(intent)
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
        imageIn(intent)?.let { incoming.value = it }
    }

    private fun imageIn(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}
