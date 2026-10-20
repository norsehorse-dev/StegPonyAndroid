package com.stegpony.android.ui.scan

/** What the caller makes of a decoded code. */
sealed interface ScanVerdict {
    /** Taken; the scanner closes. */
    data object Accept : ScanVerdict

    /** Shown over the camera; scanning goes on (a wrong code, or progress through a multi-code key). */
    data class Note(val message: String, val isError: Boolean) : ScanVerdict
}
