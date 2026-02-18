package com.example.recognizeai

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

data class LanguageItem(
    val code: String,
    val nativeName: String,
    val flag: String
)

object LocaleHelper {

    val supportedLanguages = listOf(
        LanguageItem("en", "English", "\uD83C\uDDFA\uD83C\uDDF8"),
        LanguageItem("es", "Espa\u00F1ol", "\uD83C\uDDEA\uD83C\uDDF8"),
        LanguageItem("fr", "Fran\u00E7ais", "\uD83C\uDDEB\uD83C\uDDF7"),
        LanguageItem("de", "Deutsch", "\uD83C\uDDE9\uD83C\uDDEA"),
        LanguageItem("pt", "Portugu\u00EAs", "\uD83C\uDDE7\uD83C\uDDF7"),
        LanguageItem("ru", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439", "\uD83C\uDDF7\uD83C\uDDFA")
    )

    fun setLocale(languageCode: String) {
        cachedLanguage = languageCode
        val localeList = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getCurrentLanguageCode(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (!locales.isEmpty) {
            val tag = locales.toLanguageTags().split(",").firstOrNull()?.split("-")?.firstOrNull()
            if (tag != null && supportedLanguages.any { it.code == tag }) {
                return tag
            }
        }
        // Fallback to SessionManager saved language (AppCompatDelegate can return empty on Android 13+)
        return cachedLanguage ?: "en"
    }

    private var cachedLanguage: String? = null

    /** Call this early (e.g. in Application or first Activity) so the fallback is available */
    fun initFromSession(context: Context) {
        cachedLanguage = SessionManager(context).language
    }

    fun getLanguageByCode(code: String): LanguageItem {
        return supportedLanguages.find { it.code == code } ?: supportedLanguages.first()
    }
}
