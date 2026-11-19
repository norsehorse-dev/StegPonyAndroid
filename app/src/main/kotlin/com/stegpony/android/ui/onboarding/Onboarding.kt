package com.stegpony.android.ui.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the welcome tour has been seen. Read once per process, so a
 * language change on its first page (which recreates the activity before
 * Android 13) and a replay from Settings both keep the tour open.
 */
object Onboarding {
    private const val PREFS = "stegpony"
    private const val KEY_DONE = "onboarding_done"
    private var loaded = false

    /** False while the tour should cover the app. */
    var done by mutableStateOf(true)
        private set

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        done = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }

    fun finish(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        done = true
    }

    /** Settings: show the tour again. Not stored, so closing the app mid-replay doesn't bring it back. */
    fun replay() {
        done = false
    }
}
