package com.stegpony.android

import android.content.Context
import android.content.Intent
import com.stegpony.android.ui.Tab
import com.stegpony.android.ui.hide.HideViewModel
import com.stegpony.android.ui.reveal.RevealViewModel

/** Release builds have no screenshot demo; see src/debug for the real one. */
object Demo {
    /** Never true in a release build, so screenshot blocking can't be switched off. */
    const val capturing = false

    @Suppress("UNUSED_PARAMETER")
    fun handle(context: Context, intent: Intent?, hide: HideViewModel, reveal: RevealViewModel): Tab? = null
}
