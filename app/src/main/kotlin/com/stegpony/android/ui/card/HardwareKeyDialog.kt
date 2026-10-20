package com.stegpony.android.ui.card

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import uniffi.stegpony_ffi.StegException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stegpony.android.R
import com.stegpony.android.card.CardLinkAdvice
import com.stegpony.android.card.CardLinkKind
import com.stegpony.android.card.NoDecryptionKeyException
import com.stegpony.android.card.NotCurve25519Exception
import com.stegpony.android.card.OpenPgpCard
import com.stegpony.android.card.OpenPgpCardException
import com.stegpony.android.card.OpenPgpCardSession
import com.stegpony.android.card.nfc.OpenPgpCardReader
import com.stegpony.android.card.usb.UsbCardConnectionManager
import com.stegpony.android.card.usb.UsbCardOperations
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.errorMessage
import com.stegpony.android.ui.common.findActivity

private sealed interface CardStep {
    data object Pin : CardStep
    data object Working : CardStep
    data class Failed(val message: String) : CardStep
}

/** Whether this phone can reach a hardware key at all (NFC radio or USB host). */
fun canUseHardwareKeys(context: Context): Boolean {
    val pm = context.packageManager
    return pm.hasSystemFeature("android.hardware.nfc") || pm.hasSystemFeature("android.hardware.usb.host")
}

/**
 * User-facing text for a card failure. The NFC and USB runners wrap anything
 * that isn't a card exception in OpenPgpCardException.Communication, so the
 * real cause (an RSA key, an unreadable image) is unwrapped first.
 */
fun cardErrorMessage(context: Context, error: Throwable): String {
    val cause = error.cause
    val e = if (error is OpenPgpCardException.Communication && cause != null && cause !is IOException) cause else error
    return when (e) {
        is StegException -> errorMessage(context, e)
        else -> cardOnlyMessage(context, e)
    }
}

private fun cardOnlyMessage(context: Context, e: Throwable): String = when (e) {
    is OpenPgpCardException.WrongPin ->
        if (e.triesRemaining > 0) {
            context.resources.getQuantityString(R.plurals.card_wrong_pin, e.triesRemaining, e.triesRemaining)
        } else {
            context.getString(R.string.card_pin_blocked)
        }
    is OpenPgpCardException.CardStatus ->
        if (e.sw == OpenPgpCard.SW_AUTH_METHOD_BLOCKED) {
            context.getString(R.string.card_pin_blocked)
        } else {
            context.getString(R.string.card_failed, e.message.orEmpty())
        }
    is OpenPgpCardException.TagLost -> context.getString(R.string.card_lost)
    is OpenPgpCardException.NotAnOpenPgpCard -> context.getString(R.string.card_not_openpgp)
    is NotCurve25519Exception -> context.getString(R.string.card_not_cv25519, e.algorithm)
    is NoDecryptionKeyException -> context.getString(R.string.card_no_decryption_key)
    else -> context.getString(R.string.card_failed, e.message ?: e.javaClass.simpleName)
}

/**
 * Runs [operation] on a hardware key over NFC or USB, asking for the PIN first
 * when [askPin] is set (the operation then receives it; otherwise it gets an
 * empty array). NFC reader mode stays on for as long as the dialog is open,
 * PIN step included, so Android doesn't hand the key to another app.
 * [operation] runs off the main thread with the key connected; [onSuccess]
 * runs on the main thread, and never after the dialog was dismissed unless
 * the operation had already finished.
 */
@Composable
fun <T> HardwareKeyDialog(
    title: String,
    askPin: Boolean,
    operation: (OpenPgpCardSession, ByteArray) -> T,
    onSuccess: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val currentOperation by rememberUpdatedState(operation)
    val currentOnSuccess by rememberUpdatedState(onSuccess)

    // Bumped whenever USB or NFC availability may have changed: a key plugged
    // in or out, a permission answer, NFC switched on or off (Quick Settings
    // doesn't pause the activity), or a return from Settings.
    var linkTick by remember { mutableIntStateOf(0) }
    val usbCards = remember(context) { UsbCardConnectionManager(context) { linkTick++ } }
    DisposableEffect(usbCards) {
        usbCards.register()
        onDispose { usbCards.unregister() }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                linkTick++
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) linkTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val link = remember(linkTick) {
        usbCards.availability(
            nfcPresent = OpenPgpCardReader.isNfcAvailable(context),
            nfcEnabled = OpenPgpCardReader.isNfcEnabled(context),
        )
    }
    // Asked once when a key is plugged in; after a refusal the dialog offers a
    // button instead of asking again on its own.
    LaunchedEffect(link.advice) {
        if (link.advice == CardLinkAdvice.AttachedReaderNeedsPermission) {
            usbCards.firstReader()?.let { device -> usbCards.requestPermission(device) { linkTick++ } }
        }
    }

    val reader = remember(activity) { activity?.let { OpenPgpCardReader(it) } }
    val nfc = remember(context) { NfcAdapter.getDefaultAdapter(context) }

    var pin by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<CardStep>(if (askPin) CardStep.Pin else CardStep.Working) }
    var usbBusy by remember { mutableStateOf(false) }
    var liftKey by remember { mutableStateOf(false) }
    // Each card run gets a number; a result from an older run (cancelled, or
    // replaced when the link changed) is dropped, except a success already
    // waiting for the key to be lifted.
    val generation = remember { AtomicInteger(0) }
    var pinBytesInFlight by remember { mutableStateOf<ByteArray?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            generation.incrementAndGet()
            pinBytesInFlight?.fill(0)
            reader?.stop()
        }
    }

    // While the PIN is typed, reader mode is on and ignores taps, so a key
    // held to the phone early isn't handed to another app.
    LaunchedEffect(step, link.nfcUsable) {
        if (step == CardStep.Pin && link.nfcUsable && activity != null) {
            nfc?.enableReaderMode(
                activity,
                { },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }
    }

    LaunchedEffect(step, link.preferred) {
        if (step != CardStep.Working) return@LaunchedEffect
        if (link.preferred == CardLinkKind.USB && usbBusy) return@LaunchedEffect
        reader?.stop()
        val run = generation.incrementAndGet()
        pinBytesInFlight?.fill(0)
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        pinBytesInFlight = pinBytes
        val op: (OpenPgpCardSession) -> T = { session -> currentOperation(session, pinBytes) }
        // Reader mode is left on after a result: a key still in the field
        // with reader mode off goes to Android's tag dispatcher, which opens
        // whatever app claims it. It is released when the dialog closes.
        val done: (Result<T>) -> Unit = { result ->
            usbBusy = false
            pinBytes.fill(0)
            val lifted = liftKey
            liftKey = false
            val current = generation.get() == run
            result
                .onSuccess { if (current || lifted) currentOnSuccess(it) }
                .onFailure { e ->
                    if (current) {
                        if (e is OpenPgpCardException.WrongPin) pin = ""
                        step = CardStep.Failed(cardErrorMessage(context, e))
                    }
                }
        }
        when (link.preferred) {
            CardLinkKind.USB -> {
                val manager = context.getSystemService(UsbManager::class.java)
                val device = usbCards.firstReader()
                if (manager == null || device == null) {
                    // The key went away between the check and now.
                    linkTick++
                } else {
                    usbBusy = true
                    UsbCardOperations.run(manager, device, op, onResult = done)
                }
            }
            CardLinkKind.NFC -> {
                // On success, the result waits until the key is lifted, for
                // the same reason.
                val started = reader?.startOperation(
                    op,
                    done,
                    holdUntilRemovedMs = 10_000L,
                    onAwaitingRemoval = { if (generation.get() == run) liftKey = true },
                ) == true
                if (!started) linkTick++
            }
            // Nothing usable yet; the dialog says what to do, and a change
            // (NFC switched on, a key plugged in) re-runs this.
            null -> Unit
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = step) {
                    CardStep.Pin -> {
                        Text(stringResource(R.string.card_pin_about), style = MaterialTheme.typography.bodySmall)
                        PassphraseField(pin, { pin = it }, stringResource(R.string.card_pin_label))
                    }
                    CardStep.Working -> if (liftKey) {
                        Text(stringResource(R.string.card_lift), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val usb = when (link.advice) {
                            CardLinkAdvice.UseAttachedReader,
                            CardLinkAdvice.AttachedReaderNeedsPermission,
                            CardLinkAdvice.PlugInAReader,
                            -> true
                            else -> false
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (usb) Icons.Outlined.Usb else Icons.Outlined.Contactless, contentDescription = null)
                            Text(
                                stringResource(
                                    when (link.advice) {
                                        CardLinkAdvice.UseAttachedReader -> R.string.card_usb_working
                                        CardLinkAdvice.AttachedReaderNeedsPermission -> R.string.card_usb_permission
                                        CardLinkAdvice.TapNow -> R.string.card_tap
                                        CardLinkAdvice.TapNowOrPlugIn -> R.string.card_tap_or_plug
                                        CardLinkAdvice.EnableNfc -> R.string.card_enable_nfc
                                        CardLinkAdvice.PlugInAReader -> R.string.card_plug_in
                                        CardLinkAdvice.NoTransportAvailable -> R.string.card_no_link
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (link.anyUsable) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    is CardStep.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (step) {
                CardStep.Pin -> TextButton(onClick = { step = CardStep.Working }, enabled = pin.isNotEmpty()) {
                    Text(stringResource(R.string.card_continue))
                }
                is CardStep.Failed -> TextButton(onClick = { step = if (askPin) CardStep.Pin else CardStep.Working }) {
                    Text(stringResource(R.string.card_try_again))
                }
                CardStep.Working -> when (link.advice) {
                    CardLinkAdvice.AttachedReaderNeedsPermission -> TextButton(onClick = {
                        usbCards.firstReader()?.let { device -> usbCards.requestPermission(device) { linkTick++ } }
                    }) { Text(stringResource(R.string.card_allow_usb)) }
                    CardLinkAdvice.EnableNfc -> TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                    }) { Text(stringResource(R.string.card_nfc_settings)) }
                    else -> Unit
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
