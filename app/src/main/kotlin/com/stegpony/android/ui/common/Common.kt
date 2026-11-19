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
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    is StegException.NotStegText -> context.getString(R.string.error_not_steg_text, e.word)
    is StegException.TextIncomplete -> context.getString(R.string.error_text_incomplete)
    is StegException.CoverTextEmpty -> context.getString(R.string.need_cover_text)
    is StegException.Failed -> context.getString(R.string.error_failed, e.detail)
    is TooLargeException -> context.getString(R.string.error_file_too_large, e.limit / (1024 * 1024))
    is IOException -> context.getString(R.string.error_io)
    is OutOfMemoryError -> context.getString(R.string.error_memory)
    // Never the raw exception text: the class name is enough to report a bug with.
    else -> context.getString(R.string.error_unexpected, e.javaClass.simpleName)
}

@Composable
fun detectabilityColor(d: Detectability): Color {
    val dark = isSystemInDarkTheme()
    return when (d) {
        Detectability.LOW -> if (dark) RatingColors.lowDark else RatingColors.low
        Detectability.MEDIUM -> if (dark) RatingColors.mediumDark else RatingColors.medium
        Detectability.HIGH -> MaterialTheme.colorScheme.error
    }
}

/** The rating as it is shown: "Low detectability". */
@Composable
fun detectabilityTitle(d: Detectability): String = stringResource(
    when (d) {
        Detectability.LOW -> R.string.rating_low_title
        Detectability.MEDIUM -> R.string.rating_medium_title
        Detectability.HIGH -> R.string.rating_high_title
    },
)

/** An icon per rating, so the rating never rests on color alone. */
fun detectabilityIcon(d: Detectability): ImageVector = when (d) {
    Detectability.LOW -> Icons.Outlined.CheckCircle
    Detectability.MEDIUM -> Icons.Outlined.ErrorOutline
    Detectability.HIGH -> Icons.Outlined.WarningAmber
}

/** Inline status: a small icon and a few words in the status color. No pill. */
@Composable
fun StatusText(text: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

/**
 * How full the cover is and how the result would rate. [used] and [max] are
 * payload bytes as the core counts them. With [lowMax] and [mediumMax] (the
 * largest payloads still rated low and medium) the track is drawn in three
 * zones, so it shows how far the payload is from the next rating.
 */
@Composable
fun CapacityMeter(
    used: Long,
    max: Long,
    rating: Detectability?,
    modifier: Modifier = Modifier,
    lowMax: Long? = null,
    mediumMax: Long? = null,
) {
    val fraction = if (max <= 0) 1f else (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val color = rating?.let { detectabilityColor(it) } ?: MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val lowColor = detectabilityColor(Detectability.LOW)
    val mediumColor = detectabilityColor(Detectability.MEDIUM)
    val highColor = detectabilityColor(Detectability.HIGH)
    val zoned = max > 0 && lowMax != null && mediumMax != null
    val lowEnd = if (zoned) (lowMax!!.toFloat() / max).coerceIn(0f, 1f) else 0f
    val mediumEnd = if (zoned) (mediumMax!!.toFloat() / max).coerceIn(lowEnd, 1f) else 0f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            val w = size.width
            val h = size.height
            if (zoned) {
                drawRect(lowColor.copy(alpha = 0.28f), size = Size(w * lowEnd, h))
                drawRect(mediumColor.copy(alpha = 0.28f), topLeft = Offset(w * lowEnd, 0f), size = Size(w * (mediumEnd - lowEnd), h))
                drawRect(highColor.copy(alpha = 0.28f), topLeft = Offset(w * mediumEnd, 0f), size = Size(w * (1f - mediumEnd), h))
            } else {
                drawRect(track)
            }
            drawRect(color, size = Size(w * fraction, h))
        }
        if (rating != null) {
            StatusText(detectabilityTitle(rating), color, detectabilityIcon(rating))
            Text(
                stringResource(R.string.meter_bytes, used, max),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            StatusText(stringResource(R.string.meter_too_big, used, max), color, Icons.Outlined.Block)
        }
    }
}

/** Corners for buttons, fields, option rows and panels. */
val ControlShape = RoundedCornerShape(12.dp)

/** A heading on its own, for a group that isn't in a panel. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** A heading and, under it, a raised panel holding what belongs to it. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        Panel(content = content)
    }
}

/** A raised panel with a hairline border, for one object or one group of settings. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/** The outcome of a check: a bordered box where only the icon and the title carry the color. */
@Composable
fun VerdictPanel(title: String, detail: String?, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One of a few big either-or choices: an icon, a name and what it means. The
 * chosen one takes the accent border and tint.
 */
@Composable
fun OptionRow(
    icon: ImageVector,
    label: String,
    about: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, ControlShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (about != null) {
                Text(
                    about,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = accent)
    }
}

/** Progress through a flow of [total] steps: segments up to [current] (from 0) are filled. */
@Composable
fun StepBar(current: Int, total: Int, modifier: Modifier = Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val todo = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current) done else todo),
            )
        }
    }
}

/** Hairline between list rows. */
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A setting with a switch. The whole row toggles, not only the thumb. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    about: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (about != null) {
                Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * One option of a short either-or choice, with a line saying what it means.
 * Rows wrap, so long translated labels fit where a row of chips would not.
 */
@Composable
fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    about: String? = null,
    aboutColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (about != null) {
                Text(about, style = MaterialTheme.typography.bodySmall, color = aboutColor)
            }
        }
    }
}

/** What the app bar shows. Set by whichever screen is in front, through [ScreenTitle]. */
@Stable
class TopBarState {
    var title by mutableStateOf("")
    var onBack by mutableStateOf<(() -> Unit)?>(null)
}

val LocalTopBar = staticCompositionLocalOf<TopBarState> { error("no top bar") }

/** Names the screen in the app bar, with a back arrow when [onBack] is given. */
@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null) {
    val bar = LocalTopBar.current
    SideEffect {
        bar.title = title
        bar.onBack = onBack
    }
}

/** The one app bar, above every tab and sub-page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StegTopBar(state: TopBarState) {
    val back = state.onBack
    TopAppBar(
        title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (back != null) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            } else {
                // Top-level pages carry the mark; the title beside it names the page.
                Icon(
                    painterResource(R.drawable.ic_brand_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp).size(28.dp),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
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
        // Not the app's name: clipboard managers can read the label.
        val label = "clip-$serial"
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        manager.setPrimaryClip(clip)
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context.applicationContext, R.string.copied, Toast.LENGTH_SHORT).show()
        }
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
