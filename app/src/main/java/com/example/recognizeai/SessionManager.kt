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

        const val BASE_URL = "http://10.4.4.38:3001"
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

    val isGuest: Boolean get() = authType == "guest"
    val isGoogle: Boolean get() = authType == "google"

    fun logout() {
        prefs.edit().clear().apply()
    }
}
