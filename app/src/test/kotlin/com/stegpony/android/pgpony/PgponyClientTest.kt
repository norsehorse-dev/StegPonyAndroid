package com.stegpony.android.pgpony

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgponyClientTest {
    private val action = PgponyContract.ACTION_GET_CAPABILITIES

    @Test
    fun anUnknownActionMeansPgponyIsTooOld() {
        // How PGPony 4.8.1 and older answer GET_CAPABILITIES.
        assertTrue(PgponyClient.meansTooOld(action, reason = null, errorId = 0, message = "Unknown OpenPGP API action: $action"))
    }

    @Test
    fun theAnswerIsRecognizedInAnyLanguage() {
        // PGPony translates its error messages; the action's name is the part that stays.
        assertTrue(PgponyClient.meansTooOld(action, reason = null, errorId = 0, message = "Unbekannte OpenPGP-API-Aktion: $action"))
    }

    @Test
    fun aRefusedApiVersionMeansPgponyIsTooOld() {
        assertTrue(
            PgponyClient.meansTooOld(action, reason = null, errorId = PgponyContract.ERROR_INCOMPATIBLE_API_VERSIONS, message = null),
        )
    }

    @Test
    fun anErrorWithAReasonIsFromACurrentPgpony() {
        assertFalse(
            PgponyClient.meansTooOld(
                action,
                reason = PgponyContract.REASON_UNKNOWN_KEY,
                errorId = 0,
                message = "Unknown OpenPGP API action: $action",
            ),
        )
    }

    @Test
    fun anyOtherErrorIsNotAboutAge() {
        assertFalse(PgponyClient.meansTooOld(action, reason = null, errorId = 0, message = "This app's signature changed"))
        assertFalse(PgponyClient.meansTooOld(action, reason = null, errorId = null, message = null))
    }
}
