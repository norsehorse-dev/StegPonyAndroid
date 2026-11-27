package com.stegpony.android.pgpony

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError

/** Why a step with PGPony didn't work, for the UI to put into words. */
sealed class PgponyException(message: String) : Exception(message) {
    class NotInstalled : PgponyException("PGPony is not installed")

    /** PGPony is there but has no X25519 actions: 4.8.1 or older. */
    class TooOld : PgponyException("PGPony is too old")

    /** Binding failed, timed out, or PGPony went away mid-call. */
    class Unreachable : PgponyException("PGPony could not be reached")

    /** A reply that doesn't follow the contract. */
    class BadReply : PgponyException("PGPony's reply could not be read")

    /** An error reply. [reason] is the contract's ERROR_REASON, when PGPony gave one. */
    class Refused(val reason: String?) : PgponyException("PGPony refused: ${reason ?: "no reason given"}")
}

/** What PGPony answered. */
sealed interface PgponyReply {
    class Success(val data: Intent) : PgponyReply

    /** PGPony needs the user: start [pending] for a result. */
    class NeedsUser(val pending: PendingIntent) : PgponyReply

    class Error(val reason: String?, val errorId: Int?, val message: String?) : PgponyReply
}

/**
 * Calls PGPony's OpenPGP provider. Each call binds, runs and unbinds: a reveal
 * makes a handful of calls with PGPony's own screens in between, so nothing is
 * gained by holding the binding open.
 */
object PgponyClient {
    private const val BIND_TIMEOUT_MS = 5_000L

    /** No call here waits on the user: PGPony answers at once, or with a screen to start. */
    private const val CALL_TIMEOUT_MS = 15_000L

    /** How long the pipe's reader may take to finish once the call has returned. */
    private const val READ_TIMEOUT_MS = 5_000L

    private fun serviceIntent(): Intent =
        Intent(PgponyContract.SERVICE_ACTION).setPackage(PgponyContract.PACKAGE)

    /** Whether PGPony's provider is there to bind. Needs the manifest's queries entry. */
    fun isInstalled(context: Context): Boolean =
        context.packageManager.resolveService(serviceIntent(), 0) != null

    /** A request for one of PGPony's actions, with the API version set. */
    fun request(action: String): Intent = Intent().apply {
        this.action = action
        putExtra(PgponyContract.EXTRA_API_VERSION, PgponyContract.API_VERSION)
    }

    /**
     * Sends [request] and returns the reply. Also used for the re-call after a
     * confirmation, with the intent PGPony's screen returned, sent as it is.
     */
    suspend fun call(context: Context, request: Intent): PgponyReply = withService(context) { service ->
        val reply = service.execute(request, null, 0) ?: throw PgponyException.Unreachable()
        // An error reply holds an OpenPgpError; without our class loader the
        // extras can't be read at all on Android 12 and older.
        reply.setExtrasClassLoader(OpenPgpError::class.java.classLoader)
        classify(reply)
    }

    /**
     * Sends [request] for an action that writes its answer to an output pipe
     * (GET_KEY), and returns the reply with the bytes written. PGPony closes
     * its end of the pipe when the call returns, whatever the answer, so the
     * read always ends. More than [PgponyContract.MAX_KEY_BYTES] is refused.
     */
    suspend fun fetch(context: Context, request: Intent): Pair<PgponyReply, ByteArray> = withService(context) { service ->
        val pipeId = Random.nextInt(1, Int.MAX_VALUE)
        val readEnd = service.createOutputPipe(pipeId) ?: throw PgponyException.Unreachable()
        val bytes = ByteArrayOutputStream()
        var tooLarge = false
        // Read while PGPony writes: a pipe holds only so much before the writer waits.
        val reader = thread(name = "pgpony-read", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (bytes.size() + n > PgponyContract.MAX_KEY_BYTES) {
                            tooLarge = true
                            break
                        }
                        bytes.write(buffer, 0, n)
                    }
                }
            } catch (e: IOException) {
                // A broken pipe reads as whatever arrived; the reply says what happened.
            }
        }
        val reply = try {
            service.execute(request, null, pipeId) ?: throw PgponyException.Unreachable()
        } catch (e: Exception) {
            // The call never reached PGPony, so nobody will close the other end.
            runCatching { readEnd.close() }
            throw e
        }
        reply.setExtrasClassLoader(OpenPgpError::class.java.classLoader)
        reader.join(READ_TIMEOUT_MS)
        if (tooLarge || reader.isAlive) throw PgponyException.BadReply()
        classify(reply) to bytes.toByteArray()
    }

    private fun classify(reply: Intent): PgponyReply = try {
        when (reply.getIntExtra(PgponyContract.RESULT_CODE, PgponyContract.RESULT_CODE_ERROR)) {
            PgponyContract.RESULT_CODE_SUCCESS -> PgponyReply.Success(reply)
            PgponyContract.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                val pending = IntentCompat.getParcelableExtra(reply, PgponyContract.RESULT_INTENT, PendingIntent::class.java)
                    ?: throw PgponyException.BadReply()
                PgponyReply.NeedsUser(pending)
            }
            else -> {
                val error = IntentCompat.getParcelableExtra(reply, PgponyContract.RESULT_ERROR, OpenPgpError::class.java)
                PgponyReply.Error(
                    reason = reply.getStringExtra(PgponyContract.EXTRA_ERROR_REASON),
                    errorId = error?.getErrorId(),
                    message = error?.getMessage(),
                )
            }
        }
    } catch (e: PgponyException) {
        throw e
    } catch (e: RuntimeException) {
        // BadParcelableException and friends: the reply is not what the contract says.
        throw PgponyException.BadReply()
    }

    /**
     * Whether an error reply to [action] is the answer of a PGPony that
     * doesn't have it. PGPony 4.8.1 and older answer an action they don't
     * know with a generic error that names the action, in the language PGPony
     * is set to, so the action's name is what is looked for, not the words
     * around it. A PGPony that predates API version 12 refuses the version.
     * An error of PGPony's own actions always carries a reason, so one with a
     * reason is never this.
     */
    fun meansTooOld(action: String, reason: String?, errorId: Int?, message: String?): Boolean {
        if (reason != null) return false
        if (errorId == PgponyContract.ERROR_INCOMPATIBLE_API_VERSIONS) return true
        return message?.contains(action) == true
    }

    private suspend fun <T> withService(context: Context, block: (IOpenPgpService2) -> T): T {
        val app = context.applicationContext
        val connected = CompletableDeferred<IOpenPgpService2>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = binder?.let { IOpenPgpService2.Stub.asInterface(it) }
                if (service != null) connected.complete(service) else connected.completeExceptionally(PgponyException.Unreachable())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.completeExceptionally(PgponyException.Unreachable())
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.completeExceptionally(PgponyException.Unreachable())
            }

            override fun onBindingDied(name: ComponentName?) {
                connected.completeExceptionally(PgponyException.Unreachable())
            }
        }
        val bound = try {
            app.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        try {
            if (!bound) throw if (isInstalled(app)) PgponyException.Unreachable() else PgponyException.NotInstalled()
            val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() } ?: throw PgponyException.Unreachable()
            // A binder call can't be interrupted, so it runs on its own and is
            // given up on after a while: a PGPony that hangs must not leave the
            // Reveal page waiting for good.
            val answer = CompletableDeferred<T>()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    answer.complete(block(service))
                } catch (e: PgponyException) {
                    answer.completeExceptionally(e)
                } catch (e: Exception) {
                    // RemoteException, SecurityException, or PGPony's process going away.
                    answer.completeExceptionally(PgponyException.Unreachable())
                }
            }
            return withTimeoutOrNull(CALL_TIMEOUT_MS) { answer.await() } ?: throw PgponyException.Unreachable()
        } finally {
            // Also after a failed bind: Android keeps the connection registered either way.
            try {
                app.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // Never registered.
            }
        }
    }
}
