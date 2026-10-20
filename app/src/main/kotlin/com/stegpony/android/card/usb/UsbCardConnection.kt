// Ported from PGPony Android (same author, Apache-2.0). Keep in step with it.
// UsbCardConnection.kt
// PGPony Android — USB Phase 2
//
// Enumeration, permission, and attach/detach for wired OpenPGP cards, plus
// the runner that executes a card operation over one.
//
// The NFC twin of this is OpenPgpCardReader, and the shapes deliberately
// match: hand in an operation that takes an OpenPgpCardSession, get a Result
// back on the main thread. OpenPgpCardSession depends only on the
// CardTransport interface, so nothing in the protocol layer knows which of
// these it is running on.
//
// WHERE THIS DIFFERS FROM NFC, and it is most of the file:
//
//   • NFC availability is two booleans read once. USB availability changes
//     while the app is open, because someone can plug a key in, so this
//     publishes changes rather than answering a question once.
//   • NFC needs no permission. USB needs a per-device grant, obtained through
//     a system dialog, delivered by broadcast.
//   • NFC reader mode is a modal session the platform drives. A USB
//     connection is opened and closed by us, per operation, which is simpler
//     but means the close is ours to get right.
//
// THREADING. bulkTransfer blocks. Nothing here may run on the main thread,
// which is why [UsbCardOperations.run] takes an executor and posts its result
// back rather than offering a blocking call that looks safe to call anywhere.

package com.stegpony.android.card.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.stegpony.android.card.CardLinkAvailability
import com.stegpony.android.card.OpenPgpCardException
import com.stegpony.android.card.OpenPgpCardSession
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Tracks attached CCID readers and their permission state.
 *
 * Create one per Activity, call [register] in onStart and [unregister] in
 * onStop. [onChanged] fires on the main thread whenever the answer to "can we
 * use a wired card right now" may have changed.
 */
class UsbCardConnectionManager(
    private val context: Context,
    private val onChanged: () -> Unit = {},
) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** Whether this device can host USB peripherals at all. */
    val usbHostSupported: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private var registered = false
    private var pendingPermission: ((Boolean, UsbDevice?) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = deviceFrom(intent)
                    val callback = pendingPermission
                    pendingPermission = null
                    callback?.invoke(granted, device)
                    onChanged()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onChanged()
            }
        }
    }

    fun register() {
        if (registered || !usbHostSupported) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // RECEIVER_NOT_EXPORTED: the permission action is ours, and the two
        // USB actions are protected system broadcasts, so nothing outside the
        // system should be able to reach this receiver.
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        pendingPermission = null
    }

    /** Attached CCID readers, newest enumeration each time. */
    fun readers(): List<UsbDevice> {
        val m = usbManager ?: return emptyList()
        if (!usbHostSupported) return emptyList()
        return UsbCcidCardTransport.findReaders(m)
    }

    /** The first attached reader, which is the one the UI acts on. */
    fun firstReader(): UsbDevice? = readers().firstOrNull()

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager?.hasPermission(device) == true

    /**
     * Ask for access to [device]. The system shows the dialog; the answer
     * arrives by broadcast, so [onResult] fires on the main thread later, not
     * inline.
     *
     * Only one request is tracked at a time. A second request while one is
     * outstanding replaces the first, which is the right behaviour for a UI
     * where the user can only be looking at one dialog anyway.
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val m = usbManager
        if (m == null) {
            onResult(false)
            return
        }
        if (m.hasPermission(device)) {
            onResult(true)
            return
        }
        pendingPermission = { granted, _ -> onResult(granted) }

        // MUTABLE on purpose: UsbManager fills EXTRA_DEVICE and
        // EXTRA_PERMISSION_GRANTED into this intent, which an immutable
        // PendingIntent would forbid. It is explicit and package-scoped.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        m.requestPermission(
            device,
            PendingIntent.getBroadcast(context, 0, intent, flags)
        )
    }

    /**
     * Current connectivity, given the NFC half the caller already knows.
     *
     * NFC state is passed in rather than read here so there is exactly one
     * place that decides what the combination means, and it is the pure,
     * tested one.
     */
    fun availability(nfcPresent: Boolean, nfcEnabled: Boolean): CardLinkAvailability {
        val reader = firstReader()
        return CardLinkAvailability(
            nfcPresent = nfcPresent,
            nfcEnabled = nfcEnabled,
            usbSupported = usbHostSupported,
            usbReaderAttached = reader != null,
            usbPermissionGranted = reader != null && hasPermission(reader),
        )
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        /** Package-scoped; see requestPermission for why the intent is explicit. */
        const val ACTION_USB_PERMISSION = "com.stegpony.android.USB_PERMISSION"
    }
}

/**
 * Runs one card operation over a wired reader.
 *
 * The NFC counterpart is OpenPgpCardReader.handleTag, and this is deliberately
 * the same shape: an operation over an OpenPgpCardSession, a Result delivered
 * on the main thread, the transport closed in a finally.
 *
 * There is no awaitRemoval twin. That exists on NFC because a tag left in the
 * field falls through to the platform dispatcher and launches whatever app
 * claims its NDEF record, which is issue #7. Unplugging a USB key cannot do
 * that, so holding the session open afterwards would buy nothing.
 */
object UsbCardOperations {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val defaultExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Open [device], run [operation], close, and post the result.
     *
     * Every failure path closes the transport. The one that matters is a
     * mid-operation unplug, which surfaces from CcidExchange as
     * OpenPgpCardException.TagLost with wired wording rather than the NFC
     * default about holding the card still.
     */
    fun <T> run(
        manager: UsbManager,
        device: UsbDevice,
        operation: (OpenPgpCardSession) -> T,
        executor: Executor = defaultExecutor,
        onResult: (Result<T>) -> Unit,
    ) {
        executor.execute {
            var transport: UsbCcidCardTransport? = null
            val result: Result<T> = try {
                val t = UsbCcidCardTransport.open(manager, device)
                transport = t
                Result.success(operation(OpenPgpCardSession(t)))
            } catch (e: OpenPgpCardException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(
                    OpenPgpCardException.Communication(
                        "Unexpected error talking to the security key: ${e.message}", e
                    )
                )
            } finally {
                transport?.close()
            }
            mainHandler.post { onResult(result) }
        }
    }
}
