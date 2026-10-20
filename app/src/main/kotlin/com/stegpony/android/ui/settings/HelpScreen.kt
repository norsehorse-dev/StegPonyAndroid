package com.stegpony.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.stegpony.android.R

/**
 * What StegPony does and doesn't do, for people who may be relying on it.
 * Plain and specific; no claim stronger than the format and the measurements
 * support. Every topic here is security text and gets human review in each
 * language.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    SubPage(stringResource(R.string.help_title), onBack) {
        HELP_TOPICS.forEach { (title, body) ->
            HelpTopic(stringResource(title), stringResource(body))
        }
    }
}

private val HELP_TOPICS = listOf(
    R.string.help_what_title to R.string.help_what_body,
    R.string.help_sending_title to R.string.help_sending_body,
    R.string.help_covers_title to R.string.help_covers_body,
    R.string.help_rating_title to R.string.help_rating_body,
    R.string.help_secrets_title to R.string.help_secrets_body,
    R.string.help_hardware_title to R.string.help_hardware_body,
    R.string.help_decoy_title to R.string.help_decoy_body,
    R.string.help_phone_title to R.string.help_phone_body,
    R.string.help_limits_title to R.string.help_limits_body,
)
