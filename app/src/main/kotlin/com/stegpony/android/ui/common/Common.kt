package com.stegpony.android.ui.common

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.io.TooLargeException
import com.stegpony.android.ui.theme.RatingColors
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import uniffi.stegpony_ffi.Detectability
import uniffi.stegpony_ffi.StegException

/** User-facing text for anything the flows can throw. */
fun errorMessage(context: Context, e: Throwable): String = when (e) {
    is StegException.BadCover -> context.getString(R.string.error_bad_image)
    is StegException.UnsupportedCover -> context.getString(R.string.error_unsupported_image)
    is StegException.CoverTooSmall -> context.getString(R.string.error_cover_too_small)
    is StegException.PayloadTooLarge -> context.getString(R.string.error_does_not_fit)
    is StegException.BadKey -> context.getString(R.string.error_bad_key)
    is StegException.WrongPassphrase -> context.getString(R.string.error_wrong_key_passphrase)
    is StegException.SecretNotInFile -> context.getString(R.string.error_secret_not_in_file)
    is StegException.WeakRecipient -> context.getString(R.string.error_bad_key)
    is StegException.DeviceCancelled -> context.getString(R.string.error_device_cancelled)
    is StegException.KeyDevice -> context.getString(R.string.error_key_device, e.detail)
    is StegException.Failed -> context.getString(R.string.error_failed, e.detail)
    is TooLargeException -> context.getString(R.string.error_file_too_large, e.limit / (1024 * 1024))
    is IOException -> context.getString(R.string.error_io)
    is OutOfMemoryError -> context.getString(R.string.error_memory)
    else -> context.getString(R.string.error_failed, e.message ?: e.javaClass.simpleName)
}

@Composable
fun detectabilityLabel(d: Detectability): String = stringResource(
    when (d) {
        Detectability.LOW -> R.string.rating_low
        Detectability.MEDIUM -> R.string.rating_medium
        Detectability.HIGH -> R.string.rating_high
    },
)

@Composable
fun detectabilityColor(d: Detectability): Color {
    val dark = isSystemInDarkTheme()
    return when (d) {
        Detectability.LOW -> if (dark) RatingColors.lowDark else RatingColors.low
        Detectability.MEDIUM -> if (dark) RatingColors.mediumDark else RatingColors.medium
        Detectability.HIGH -> MaterialTheme.colorScheme.error
    }
}

/**
 * How full the cover is and how the result would rate. [used] and [max] are
 * payload bytes as the core counts them.
 */
@Composable
fun CapacityMeter(used: Long, max: Long, rating: Detectability?, modifier: Modifier = Modifier) {
    val fraction = if (max <= 0) 1f else (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val color = rating?.let { detectabilityColor(it) } ?: MaterialTheme.colorScheme.error
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (rating != null) {
                stringResource(R.string.meter_fits, used, max, detectabilityLabel(rating))
            } else {
                stringResource(R.string.meter_too_big, used, max)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rating == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A titled card holding one step of a flow. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A passphrase field with a show/hide toggle. Never offered to autofill or the keyboard's memory. */
@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (shown) R.string.hide_passphrase else R.string.show_passphrase),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

private val secureRequests = AtomicInteger(0)

/**
 * FLAG_SECURE (no screenshots, recordings or recent-apps thumbnail) while this
 * is in composition. Counted, so overlapping screens don't clear it early.
 */
@Composable
fun SecureScreenEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        if (secureRequests.getAndIncrement() == 0) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (secureRequests.decrementAndGet() == 0) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Copies text marked sensitive (kept out of the clipboard preview on Android
 * 13+) and clears it after [CLEAR_AFTER_SECONDS]. In the background Android 10+
 * won't let the app read the clipboard, so when it can't tell whose clip is
 * there it clears anyway: wiping a later copy is the lesser harm than leaving
 * a secret behind.
 */
object Clipboard {
    const val CLEAR_AFTER_SECONDS = 60L

    private val counter = AtomicLong(0)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun copySensitive(context: Context, text: String) {
        val manager = context.applicationContext.getSystemService(ClipboardManager::class.java) ?: return
        val serial = counter.incrementAndGet()
        val label = "stegpony-$serial"
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        manager.setPrimaryClip(clip)
        handler.postDelayed({
            val current = runCatching { manager.primaryClipDescription?.label?.toString() }.getOrNull()
            val ours = current == label || (current == null && counter.get() == serial)
            if (ours) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.clearPrimaryClip()
                } else {
                    manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, CLEAR_AFTER_SECONDS * 1000)
    }
}
