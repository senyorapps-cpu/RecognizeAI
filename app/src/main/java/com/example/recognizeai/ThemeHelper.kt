package com.example.recognizeai

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {
    const val THEME_DEFAULT = "default"
    const val THEME_NIGHT = "night"
    const val THEME_PAPER = "paper"

    fun applyGlobalMode(context: Context) {
        when (SessionManager(context).appTheme) {
            THEME_NIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    fun applyToActivity(activity: AppCompatActivity) {
        if (SessionManager(activity).appTheme == THEME_PAPER) {
            activity.setTheme(R.style.Theme_SightAI_Paper)
        }
    }
}
