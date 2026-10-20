package com.stegpony.android.io

import com.stegpony.android.ui.keys.KeysViewModel

/** Decides where text shared in from another app goes. */
object SharedText {
    /** An age1… recipient: "age1" and 58 Bech32 characters. */
    private val AGE_RECIPIENT = Regex("(?<![a-z0-9])age1[02-9ac-hj-np-z]{58}(?![a-z0-9])", RegexOption.IGNORE_CASE)

    /** Key material goes to Keys; anything else is text to look inside, on Reveal. */
    fun isKey(text: String): Boolean =
        KeysViewModel.isSecret(text) ||
            text.contains("-----BEGIN PGP") ||
            AGE_RECIPIENT.containsMatchIn(text)
}
