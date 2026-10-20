package com.stegpony.android.ui.hide

/** The invisible characters zero-width text is written with (FORMAT.md 7.2). */
private val ZERO_WIDTH_CHARS = setOf('\u200B', '\u200C', '\u200D', '\u2060')

/**
 * The text without its hidden characters, which sit only at the start of the
 * text or right after whitespace. Joiners elsewhere, inside emoji, stay.
 */
internal fun visibleText(text: String): String {
    val out = StringBuilder(text.length)
    var atPosition = true
    for (c in text) {
        if (atPosition && c in ZERO_WIDTH_CHARS) continue
        out.append(c)
        atPosition = isWhiteSpace(c)
    }
    return out.toString()
}

/** Unicode White_Space, the set FORMAT.md 7.2 names (Char.isWhitespace differs slightly). */
private fun isWhiteSpace(c: Char): Boolean =
    c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == '\u00A0' || c == '\u1680' ||
        c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' || c == '\u202F' ||
        c == '\u205F' || c == '\u3000'
