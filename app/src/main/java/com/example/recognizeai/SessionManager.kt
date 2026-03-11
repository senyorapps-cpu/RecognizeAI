package com.example.recognizeai

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tripai_session", Context.MODE_PRIVATE)

    // ANDROID_ID is the device identifier (IMEI is not accessible on Android 10+)
    val deviceId: String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "unknown"

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHOTO_URL = "photo_url"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_PLAN = "plan"
        private const val KEY_SCANS_TODAY = "scans_today"
        private const val KEY_SCAN_DATE = "scan_date"

        const val BASE_URL = "http://mnaks.online:3001"
    }

    fun saveUser(
        userId: Long,
        authType: String,
        displayName: String,
        email: String = "",
        photoUrl: String = ""
    ) {
        prefs.edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_AUTH_TYPE, authType)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PHOTO_URL, photoUrl)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    val isLoggedIn: Boolean get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    val userId: Long get() = prefs.getLong(KEY_USER_ID, -1L)
    val authType: String get() = prefs.getString(KEY_AUTH_TYPE, "") ?: ""
    val displayName: String get() = prefs.getString(KEY_DISPLAY_NAME, "Guest") ?: "Guest"
    val email: String get() = prefs.getString(KEY_EMAIL, "") ?: ""
    val photoUrl: String get() = prefs.getString(KEY_PHOTO_URL, "") ?: ""

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).commit() }

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_DARK_MODE, value).apply() }

    val isGuest: Boolean get() = authType == "guest"
    val isGoogle: Boolean get() = authType == "google"

    // ── Subscription plan ──────────────────────────────────────
    var plan: String
        get() = prefs.getString(KEY_PLAN, "free") ?: "free"
        set(value) { prefs.edit().putString(KEY_PLAN, value).apply() }

    val isPlus: Boolean get() = plan == "plus" || plan == "pro"
    val isPro: Boolean get() = plan == "pro"
    val isFree: Boolean get() = plan == "free"

    val planDisplayName: String get() = when (plan) {
        "plus" -> "Traveler"
        "pro" -> "Globetrotter"
        else -> "Explorer"
    }

    val scanLimit: Int get() = when (plan) {
        "plus" -> 50
        "pro" -> 200
        else -> 5
    }

    val maxPendingQueue: Int get() = when (plan) {
        "plus" -> 20
        "pro" -> Int.MAX_VALUE
        else -> 3
    }

    val maxJournalEntries: Int get() = when (plan) {
        "plus", "pro" -> Int.MAX_VALUE
        else -> 20
    }

    // Daily scan counter — resets automatically on a new calendar day
    var scansToday: Int
        get() {
            val today = java.time.LocalDate.now().toString()
            val savedDate = prefs.getString(KEY_SCAN_DATE, "") ?: ""
            return if (savedDate == today) prefs.getInt(KEY_SCANS_TODAY, 0) else 0
        }
        set(value) {
            val today = java.time.LocalDate.now().toString()
            prefs.edit()
                .putInt(KEY_SCANS_TODAY, value)
                .putString(KEY_SCAN_DATE, today)
                .apply()
        }

    fun incrementScansToday() { scansToday = scansToday + 1 }
    // ───────────────────────────────────────────────────────────

    fun logout() {
        val savedLanguage = language
        prefs.edit().clear().apply()
        this.language = savedLanguage
    }
}
