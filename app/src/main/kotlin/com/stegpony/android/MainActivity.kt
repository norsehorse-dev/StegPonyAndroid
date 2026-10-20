package com.stegpony.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.stegpony.android.lock.AppLock
import com.stegpony.android.lock.ScreenPrivacy
import com.stegpony.android.ui.LocalTab
import com.stegpony.android.ui.Tab
import com.stegpony.android.ui.lock.LockScreen
import androidx.core.content.IntentCompat
import com.stegpony.android.io.Files
import com.stegpony.android.ui.StegPonyRoot
import com.stegpony.android.ui.theme.StegPonyTheme

/** Something another app handed to StegPony. */
sealed interface Incoming {
    /** An image to look inside: goes to Reveal. */
    data class Image(val uri: Uri) : Incoming

    /** Shared text: a key goes to Keys, anything else to Reveal (see SharedText). */
    data class Text(val text: String) : Incoming

    /** A key file, such as PGPony's application/pgp-keys share: goes to Keys. */
    data class KeyFile(val uri: Uri) : Incoming
}

/**
 * Single activity. An image or text shared in, or an image opened with
 * StegPony, goes straight to Reveal; a key shared in goes to Keys.
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
        ScreenPrivacy.load(this)
        // Decide before the first frame, so a locked app never shows its screens.
        AppLock.onStart(this)
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            StegPonyTheme {
                val tab = rememberSaveable { mutableStateOf(Tab.Hide) }
                CompositionLocalProvider(LocalTab provides tab) {
                    if (AppLock.locked) {
                        LockScreen()
                    } else {
                        StegPonyRoot(
                            incoming = incoming.value,
                            onIncomingHandled = { incoming.value = null },
                        )
                    }
                }
            }
        }
    }

    /** With the app lock on, the screen turning off locks at once. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLock.lockNow(this@MainActivity)
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(this)
    }

    override fun onStop() {
        AppLock.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
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
            else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Incoming.Text(it) }
        }
    }
}
