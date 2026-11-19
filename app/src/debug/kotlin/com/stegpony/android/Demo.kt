package com.stegpony.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.stegpony.android.i18n.LanguageManager
import com.stegpony.android.i18n.SupportedLanguage
import com.stegpony.android.keys.KeySource
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import com.stegpony.android.ui.Tab
import com.stegpony.android.ui.hide.HideStep
import com.stegpony.android.ui.hide.HideViewModel
import com.stegpony.android.ui.onboarding.Onboarding
import com.stegpony.android.ui.reveal.RevealViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.stegpony_ffi.generateIdentity

/**
 * Store screenshots, debug builds only (tools/play-screenshots.sh drives it over adb):
 *
 *   am start -n com.stegpony.android.debug/com.stegpony.android.MainActivity --es demo_lang de --es demo_screen tour
 *
 * demo_lang switches the in-app language. demo_screen picks the screen: tour,
 * cover, review, result, reveal, keys or settings, asked for in that order,
 * since each builds on the one before. Everything on screen is real: the demo
 * loads the photo the script put in the cache, hides a message in it with the
 * core, and reveals it again the normal way. The keys are real keys made on
 * the spot for people who don't exist.
 *
 * While a demo is running, screenshot blocking is off ([capturing]), because a
 * blocked screen can't be captured. The release build's Demo has that fixed to
 * false.
 */
object Demo {
    var capturing = false
        private set

    private const val PASSPHRASE = "seven quiet lanterns drift north"
    private const val OWN_KEY = "Alex Rivera"
    private val CONTACTS = listOf("Sam Okafor", "Mia Chen")

    private val MESSAGES = mapOf(
        SupportedLanguage.EN to "Meet at the north gate at six. Bring the documents.",
        SupportedLanguage.DE to "Treffen um sechs am Nordtor. Bring die Unterlagen mit.",
        SupportedLanguage.ES to "Nos vemos a las seis en la puerta norte. Trae los documentos.",
        SupportedLanguage.FR to "Rendez-vous à six heures à la porte nord. Apportez les documents.",
        SupportedLanguage.PT_BR to "Encontro às seis no portão norte. Traga os documentos.",
        SupportedLanguage.RU to "Встречаемся в шесть у северных ворот. Возьмите документы.",
        SupportedLanguage.ZH_HANS to "六点在北门见。带上文件。",
    )

    private val scope = MainScope()
    private var last: Job? = null

    /** Returns the tab to show, or null when the intent isn't a demo request. */
    fun handle(context: Context, intent: Intent?, hide: HideViewModel, reveal: RevealViewModel): Tab? {
        val screen = intent?.getStringExtra("demo_screen") ?: return null
        // Taken once: an activity recreated with the same intent doesn't run it again.
        intent.removeExtra("demo_screen")
        capturing = true
        val app = context.applicationContext
        intent.getStringExtra("demo_lang")?.let { tag ->
            val language = SupportedLanguage.resolve(tag) ?: SupportedLanguage.EN
            if (LanguageManager.current() != language) LanguageManager.setLanguage(language)
            intent.removeExtra("demo_lang")
        }
        if (screen == "tour") {
            // The welcome page. The pages after it are longer than a 9:16 capture in some languages.
            Onboarding.startPage = 1
            Onboarding.replay()
        } else {
            Onboarding.finish(app)
        }
        // One stage at a time, in the order asked for.
        val previous = last
        last = scope.launch {
            previous?.join()
            runCatching { stage(app, screen, hide, reveal) }
        }
        return when (screen) {
            "reveal" -> Tab.Reveal
            "keys" -> Tab.Keys
            "settings" -> Tab.Settings
            else -> Tab.Hide
        }
    }

    private suspend fun stage(context: Context, screen: String, hide: HideViewModel, reveal: RevealViewModel) {
        when (screen) {
            "cover" -> cover(context, hide)
            "review" -> review(context, hide)
            "result" -> result(context, hide)
            "reveal" -> {
                result(context, hide)
                val made = hide.result ?: return
                val out = File(context.cacheDir, "demo/out").apply { mkdirs() }
                val file = File(out, made.name)
                withContext(Dispatchers.IO) { file.writeBytes(made.bytes) }
                if (!reveal.open(FileProvider.getUriForFile(context, context.packageName + ".files", file))) return
                await { reveal.hasImage }
                reveal.passphrase = PASSPHRASE
                reveal.reveal()
                await { reveal.outcome != null || reveal.errorText != null }
            }
            "keys" -> keys(context)
        }
    }

    /** Loads the photo the script put in the cache, and waits for its capacity. */
    private suspend fun cover(context: Context, hide: HideViewModel) {
        hide.prepareForTour()
        if (hide.cover == null) {
            val photo = File(context.cacheDir, "demo/cover").listFiles()?.filter { it.isFile }?.minByOrNull { it.name } ?: return
            hide.pickCover(FileProvider.getUriForFile(context, context.packageName + ".files", photo))
        }
        await { hide.capacity != null || hide.errorText != null }
    }

    private suspend fun review(context: Context, hide: HideViewModel) {
        if (hide.result != null) return
        cover(context, hide)
        hide.text = MESSAGES[LanguageManager.current()] ?: MESSAGES.getValue(SupportedLanguage.EN)
        hide.passphrase = PASSPHRASE
        hide.passphraseAgain = PASSPHRASE
        var steps = 0
        while (hide.step != HideStep.Review && steps < 4) {
            hide.next()
            steps++
        }
    }

    private suspend fun result(context: Context, hide: HideViewModel) {
        if (hide.result != null) return
        review(context, hide)
        hide.hide()
        await(60_000) { hide.result != null || hide.errorText != null }
    }

    /** One key of your own and two contacts, unless there are keys already. */
    private suspend fun keys(context: Context) {
        await { Keyring.state.value is KeyringState.Ready }
        if (Keyring.state.value !is KeyringState.Ready) return
        if (Keyring.current.mine.isNotEmpty() || Keyring.current.contacts.isNotEmpty()) return
        val own = withContext(Dispatchers.Default) { generateIdentity() }
        Keyring.addMine(context, OWN_KEY, own.identity, own.recipient, KeySource.Generated)
        for (name in CONTACTS) {
            val other = withContext(Dispatchers.Default) { generateIdentity() }
            Keyring.addContact(context, name, other.recipient)
        }
    }

    private suspend fun await(timeoutMs: Long = 30_000, ready: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) {
            while (!ready()) delay(100)
        }
    }
}
