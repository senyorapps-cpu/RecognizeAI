package com.example.recognizeai

import android.app.Application

class SightAIApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeHelper.applyGlobalMode(this)
        LocaleHelper.initFromSession(this)
    }
}
