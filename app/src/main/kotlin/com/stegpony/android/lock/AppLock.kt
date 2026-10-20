package com.stegpony.android.lock

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The app lock, after PassPony's unlock gate: off by default; when on, the
 * phone's own biometric or screen-lock check opens the app. It locks on a
 * fresh start, when the screen turns off, on "Lock now", and when the app
 * comes back after more than [GRACE_MS] away. The grace time lives in memory
 * only, so a restarted process is always locked.
 *
 * It gates access to the screens; the keys themselves are protected by the
 * Keystore whether or not it is on.
 */
object AppLock {
    const val GRACE_MS: Long = 60_000
    private const val PREFS = "stegpony"
    private const val KEY_ENABLED = "app_lock_enabled"
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** True while the lock screen should cover the app. */
    var locked by mutableStateOf(false)
        private set

    /** Elapsed-realtime when the app last went to the background unlocked. */
    private var leftAt: Long? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Whether the phone has a biometric or screen lock the app lock can use. */
    fun canUse(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) locked = false
        leftAt = null
    }

    /** The activity is starting: lock if the lock is on and the grace time has run out (or never began). */
    fun onStart(context: Context) {
        if (!isEnabled(context)) {
            locked = false
            return
        }
        val left = leftAt
        if (left == null || SystemClock.elapsedRealtime() - left >= GRACE_MS) locked = true
    }

    /** The activity went to the background. Leaving while locked never starts a grace time. */
    fun onStop() {
        if (!locked) leftAt = SystemClock.elapsedRealtime()
    }

    /** Lock at once (screen off, Lock now). */
    fun lockNow(context: Context) {
        if (!isEnabled(context)) return
        locked = true
        leftAt = null
    }

    /** Shows the system check; [onError] gets null for a plain dismissal. */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        onSuccess: () -> Unit,
        onError: (String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val dismissed = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onError(if (dismissed) null else errString.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            // BiometricPrompt throws when started at a bad lifecycle moment;
            // the lock screen's button and the next resume try again.
            onError(e.message)
        }
    }

    /** The check passed. */
    fun unlocked() {
        locked = false
        leftAt = null
    }
}
