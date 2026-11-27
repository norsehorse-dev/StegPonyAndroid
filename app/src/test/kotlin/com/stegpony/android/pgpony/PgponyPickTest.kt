package com.stegpony.android.pgpony

import org.junit.Assert.assertEquals
import org.junit.Test

class PgponyPickTest {
    @Test
    fun oneKeyIsThePick() {
        assertEquals(PickAnswer.One(0x1122334455667788L), PgponyPick.read(longArrayOf(0x1122334455667788L), pickerShown = true))
    }

    @Test
    fun aKeyIdWithTheTopBitSetIsStillOneKey() {
        // Key ids are 64 bits read as signed longs, so half of them are negative.
        assertEquals(PickAnswer.One(-2L), PgponyPick.read(longArrayOf(-2L), pickerShown = true))
    }

    @Test
    fun theSameKeyTwiceIsOneKey() {
        assertEquals(PickAnswer.One(7L), PgponyPick.read(longArrayOf(7L, 7L), pickerShown = true))
    }

    @Test
    fun twoKeysAreSeveral() {
        assertEquals(PickAnswer.Several, PgponyPick.read(longArrayOf(1L, 2L), pickerShown = true))
    }

    @Test
    fun anEmptyPickerIsNoKey() {
        assertEquals(PickAnswer.None, PgponyPick.read(LongArray(0), pickerShown = true))
        assertEquals(PickAnswer.None, PgponyPick.read(null, pickerShown = true))
    }

    @Test
    fun anEmptyAnswerWithNoPickerIsAnOldPgpony() {
        // Before 4.8.1 a request with no addresses is answered at once, with nothing.
        assertEquals(PickAnswer.TooOld, PgponyPick.read(LongArray(0), pickerShown = false))
        assertEquals(PickAnswer.TooOld, PgponyPick.read(null, pickerShown = false))
    }

    @Test
    fun aKeyWithNoPickerIsStillTaken() {
        assertEquals(PickAnswer.One(5L), PgponyPick.read(longArrayOf(5L), pickerShown = false))
    }
}
