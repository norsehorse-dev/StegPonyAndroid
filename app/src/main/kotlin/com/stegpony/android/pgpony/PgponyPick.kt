package com.stegpony.android.pgpony

/** What PGPony's answer to "pick a public key" comes to. */
sealed interface PickAnswer {
    /** One key, by its 64-bit key id. */
    data class One(val keyId: Long) : PickAnswer

    /** The picker was shown and nothing was ticked. */
    data object None : PickAnswer

    /** More than one key was ticked. StegPony hides for one. */
    data object Several : PickAnswer

    /** An empty answer with no picker: a PGPony from before 4.8.1. */
    data object TooOld : PickAnswer
}

object PgponyPick {
    /**
     * Reads the key ids of a GET_KEY_IDS answer. [pickerShown] says whether
     * the answer followed PGPony's picker. A PGPony from before 4.8.1 has no
     * picker for a request with no addresses and answers at once with an
     * empty list, which is how it is told apart from a picker left empty.
     */
    fun read(keyIds: LongArray?, pickerShown: Boolean): PickAnswer {
        val ids = keyIds?.distinct().orEmpty()
        return when {
            ids.isEmpty() && !pickerShown -> PickAnswer.TooOld
            ids.isEmpty() -> PickAnswer.None
            ids.size > 1 -> PickAnswer.Several
            else -> PickAnswer.One(ids.single())
        }
    }
}
