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
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_PLAN = "plan"
        private const val KEY_SCANS_TODAY = "scans_today"
        private const val KEY_SCAN_DATE = "scan_date"

        // Plan limit keys (fetched from server, stored locally)
        private const val KEY_LIMIT_FREE_SCANS    = "limit_free_scans"
        private const val KEY_LIMIT_PLUS_SCANS    = "limit_plus_scans"
        private const val KEY_LIMIT_PRO_SCANS     = "limit_pro_scans"
        private const val KEY_LIMIT_FREE_JOURNAL  = "limit_free_journal"
        private const val KEY_LIMIT_FREE_QUEUE    = "limit_free_queue"
        private const val KEY_LIMIT_PLUS_QUEUE    = "limit_plus_queue"
        private const val KEY_LIMIT_PRO_QUEUE     = "limit_pro_queue"
        private const val KEY_LIMIT_FREE_PINS     = "limit_free_pins"
        private const val KEY_LIMIT_FREE_AUDIO    = "limit_free_audio"
        private const val KEY_LIMIT_FREE_SHARE    = "limit_free_share"

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

    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, "default") ?: "default"
        set(value) { prefs.edit().putString(KEY_APP_THEME, value).apply() }

    var isDarkMode: Boolean
        get() = appTheme == "night"
        set(value) { appTheme = if (value) "night" else "default" }

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
        "plus" -> prefs.getInt(KEY_LIMIT_PLUS_SCANS, 50)
        "pro"  -> prefs.getInt(KEY_LIMIT_PRO_SCANS,  200)
        else   -> prefs.getInt(KEY_LIMIT_FREE_SCANS, 5)
    }

    val maxPendingQueue: Int get() = when (plan) {
        "pro"  -> { val v = prefs.getInt(KEY_LIMIT_PRO_QUEUE, -1);  if (v == -1) Int.MAX_VALUE else v }
        "plus" -> { val v = prefs.getInt(KEY_LIMIT_PLUS_QUEUE, 20); if (v == -1) Int.MAX_VALUE else v }
        else   -> prefs.getInt(KEY_LIMIT_FREE_QUEUE, 3)
    }

    val maxJournalEntries: Int get() = when (plan) {
        "plus", "pro" -> Int.MAX_VALUE
        else -> prefs.getInt(KEY_LIMIT_FREE_JOURNAL, 20)
    }

    val maxMapPins: Int get() = when (plan) {
        "plus", "pro" -> Int.MAX_VALUE
        else -> { val v = prefs.getInt(KEY_LIMIT_FREE_PINS, 20); if (v == -1) Int.MAX_VALUE else v }
    }

    val audioEnabled: Boolean get() = if (isPlus) true else prefs.getBoolean(KEY_LIMIT_FREE_AUDIO, false)
    val shareEnabled: Boolean get() = if (isPlus) true else prefs.getBoolean(KEY_LIMIT_FREE_SHARE, false)

    fun savePlanLimits(
        freeScans: Int, plusScans: Int, proScans: Int,
        freeJournal: Int, freeQueue: Int, plusQueue: Int, proQueue: Int, freePins: Int,
        freeAudio: Boolean, freeShare: Boolean
    ) {
        prefs.edit()
            .putInt(KEY_LIMIT_FREE_SCANS,   freeScans)
            .putInt(KEY_LIMIT_PLUS_SCANS,   plusScans)
            .putInt(KEY_LIMIT_PRO_SCANS,    proScans)
            .putInt(KEY_LIMIT_FREE_JOURNAL, freeJournal)
            .putInt(KEY_LIMIT_FREE_QUEUE,   freeQueue)
            .putInt(KEY_LIMIT_PLUS_QUEUE,   plusQueue)
            .putInt(KEY_LIMIT_PRO_QUEUE,    proQueue)
            .putInt(KEY_LIMIT_FREE_PINS,    freePins)
            .putBoolean(KEY_LIMIT_FREE_AUDIO, freeAudio)
            .putBoolean(KEY_LIMIT_FREE_SHARE, freeShare)
            .apply()
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
