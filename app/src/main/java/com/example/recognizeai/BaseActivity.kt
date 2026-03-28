package com.example.recognizeai

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = SessionManager(newBase).language
        val config = newBase.resources.configuration
        config.setLocale(java.util.Locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
