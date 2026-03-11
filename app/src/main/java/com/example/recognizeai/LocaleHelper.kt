package com.example.recognizeai

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun setLocale(languageCode: String) {
        cachedLanguage = languageCode
        val localeList = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Returns the language code that is ACTUALLY applied to the app's resources.
     * Does NOT fall back to SessionManager — use this to detect if locale needs to be re-applied.
     */
    fun getAppliedLanguageCode(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (!locales.isEmpty) {
            val tag = locales.toLanguageTags().split(",").firstOrNull()?.split("-")?.firstOrNull()
            if (tag != null && supportedLanguages.any { it.code == tag }) {
                return tag
            }
        }
        return "en"  // System default
    }

    /**
     * Returns the user's preferred language code (applied locale, or saved preference as fallback).
     * Use this for display purposes (e.g. showing current language in profile).
     */
    fun getCurrentLanguageCode(): String {
        val applied = getAppliedLanguageCode()
        if (applied != "en" || cachedLanguage == null) return applied
        return cachedLanguage ?: "en"
    }

    private var cachedLanguage: String? = null

    /**
     * Call this early (e.g. in Application or first Activity) so the fallback is available.
     * Also re-applies the locale if it was lost (e.g. after reinstall).
     */
    fun initFromSession(context: Context) {
        val saved = SessionManager(context).language
        cachedLanguage = saved
        // If saved language differs from what's actually applied, re-apply it
        if (saved != "en" && saved != getAppliedLanguageCode()) {
            setLocale(saved)
        }
    }

    /**
     * Fetches the user's saved language from the server using device ID.
     * Call this on a background thread. Returns the language code or null if not found.
     */
    fun fetchLanguageFromServer(deviceId: String): String? {
        return try {
            val request = Request.Builder()
                .url("${SessionManager.BASE_URL}/api/device/$deviceId/language")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val lang = JSONObject(body).optString("language", "en")
                if (lang.isNotEmpty() && supportedLanguages.any { it.code == lang }) lang else null
            } else null
        } catch (e: Exception) {
            Log.e("LocaleHelper", "Failed to fetch language from server", e)
            null
        }
    }

    fun getLanguageByCode(code: String): LanguageItem {
        return supportedLanguages.find { it.code == code } ?: supportedLanguages.first()
    }
}
