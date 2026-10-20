package com.stegpony.android.lock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screenshot blocking (FLAG_SECURE). Reveal, Keys and the lock screen always
 * block; Hide does by default, since it shows the message and passphrases,
 * and Settings can allow screenshots there.
 */
object ScreenPrivacy {
    private const val PREFS = "stegpony"
    private const val KEY_SECURE_HIDE = "secure_hide"

    var secureHide by mutableStateOf(true)
        private set

    fun load(context: Context) {
        secureHide = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SECURE_HIDE, true)
    }

    fun setSecureHide(context: Context, secure: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SECURE_HIDE, secure).apply()
        secureHide = secure
    }
}
