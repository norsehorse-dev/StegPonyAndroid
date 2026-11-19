package com.stegpony.android.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.os.LocaleListCompat

/**
 * The languages StegPony ships, each with a values folder and an entry in
 * res/xml/locales_config.xml. [tag] is the BCP-47 tag; [nativeName] is never
 * translated, so a reader can find their own language from any other.
 */
enum class SupportedLanguage(val tag: String, val nativeName: String) {
    EN("en", "English"),
    DE("de", "Deutsch"),
    ES("es", "Español"),
    FR("fr", "Français"),
    PT_BR("pt-BR", "Português (Brasil)"),
    RU("ru", "Русский"),
    ZH_HANS("zh-Hans", "简体中文");

    companion object {
        /** The shipped language closest to [tag] (de-AT is German, pt-PT is Brazilian Portuguese), or null. */
        fun resolve(tag: String?): SupportedLanguage? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.replace('_', '-')
            entries.firstOrNull { it.tag.equals(normalized, ignoreCase = true) }?.let { return it }
            return when (normalized.substringBefore('-').lowercase()) {
                "en" -> EN
                "de" -> DE
                "es" -> ES
                "fr" -> FR
                "pt" -> PT_BR
                "ru" -> RU
                // Traditional Chinese readers get English, not Simplified.
                "zh" -> if (isTraditionalChinese(normalized)) null else ZH_HANS
                else -> null
            }
        }

        private fun isTraditionalChinese(tag: String): Boolean {
            val parts = tag.split('-').drop(1).map { it.lowercase() }
            if ("hans" in parts) return false
            if ("hant" in parts) return true
            return parts.any { it == "tw" || it == "hk" || it == "mo" }
        }
    }
}

/** The language in use, as a tag, for screens that show which one is selected. */
object LanguageState {
    val current: MutableState<String> = mutableStateOf(SupportedLanguage.EN.tag)

    /** Call after the activity's super.onCreate, once AppCompat has restored its stored choice, and on a configuration change. */
    fun init(context: Context) {
        val stored = AppCompatDelegate.getApplicationLocales()
        val chosen = (0 until stored.size()).firstNotNullOfOrNull { SupportedLanguage.resolve(stored.get(it)?.toLanguageTag()) }
        current.value = (chosen ?: LanguageManager.detect(context)).tag
    }
}

/**
 * In-app language switching, as in PGPony. AppCompat stores the choice (the
 * system does on Android 13 and later, where it also shows in the system's
 * app-language settings). On Android 13 and later the activity takes the
 * change in place (configChanges in the manifest), so there is no flash;
 * before that AppCompat recreates it.
 */
object LanguageManager {
    @Volatile
    private var localizedCache: Pair<String, Context>? = null

    /** The first of the phone's languages StegPony ships, or English. */
    fun detect(context: Context): SupportedLanguage {
        val locales = context.resources.configuration.locales
        for (i in 0 until locales.size()) {
            SupportedLanguage.resolve(locales.get(i).toLanguageTag())?.let { return it }
        }
        return SupportedLanguage.EN
    }

    fun current(): SupportedLanguage = SupportedLanguage.resolve(LanguageState.current.value) ?: SupportedLanguage.EN

    fun setLanguage(language: SupportedLanguage) {
        LanguageState.current.value = language.tag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    /**
     * A context whose strings are in the chosen language. Before Android 13
     * AppCompat only switches the activity's resources, so anything read
     * through the application context (view models, toasts) would stay in the
     * phone's language without this.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tags.isEmpty()) return context
        localizedCache?.let { if (it.first == tags) return it.second }
        val app = context.applicationContext
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tags))
        val made = app.createConfigurationContext(config)
        localizedCache = tags to made
        return made
    }
}
