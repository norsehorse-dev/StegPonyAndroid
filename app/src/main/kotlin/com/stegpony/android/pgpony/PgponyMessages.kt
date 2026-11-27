package com.stegpony.android.pgpony

import android.content.Context
import com.stegpony.android.R

/** What to tell the user when a step with PGPony didn't work. [context] decides the language. */
fun pgponyErrorMessage(context: Context, e: PgponyException): String = when (e) {
    is PgponyException.NotInstalled -> context.getString(R.string.pgpony_not_installed)
    is PgponyException.TooOld -> context.getString(R.string.pgpony_too_old)
    is PgponyException.Unreachable -> context.getString(R.string.pgpony_unreachable)
    is PgponyException.BadReply -> context.getString(R.string.pgpony_bad_reply)
    is PgponyException.Refused -> when (e.reason) {
        PgponyContract.REASON_UNKNOWN_KEY,
        PgponyContract.REASON_UNSUPPORTED_KEY,
        PgponyContract.REASON_KEY_UNAVAILABLE -> context.getString(R.string.pgpony_key_unavailable)
        PgponyContract.REASON_AGREEMENT_FAILED -> context.getString(R.string.pgpony_agreement_failed)
        // No reason: PGPony turned StegPony itself away, as it does when the
        // app's signature is not the one it allowed.
        null -> context.getString(R.string.pgpony_refused_app)
        // invalid_request, too_many_points, low_order_point, or a reason newer than this build.
        else -> context.getString(R.string.pgpony_refused, e.reason)
    }
}
