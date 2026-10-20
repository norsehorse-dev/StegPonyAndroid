package com.stegpony.android.ui.hide

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPreviewTest {
    @Test
    fun hiddenCharactersGoEmojiJoinersStay() {
        val family = "👨‍👩‍👧"
        val hidden = "​⁠‌‍"
        assertEquals(
            "$family dinner　at eight",
            visibleText("$hidden$family $hidden" + "dinner　${hidden}at eight"),
        )
    }

    @Test
    fun onlyWhiteSpaceStartsAPosition() {
        // U+001C is not White_Space, so the character after it is not hidden data.
        assertEquals("a\u001C​b", visibleText("a\u001C​b"))
        assertEquals("a b", visibleText("a ​b"))
    }
}
