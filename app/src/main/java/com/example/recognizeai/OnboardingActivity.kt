package com.example.recognizeai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.viewpager2.widget.ViewPager2
import com.example.recognizeai.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingAdapter

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.initFromSession(this)

        // Apply dark mode preference early (only if not already matching)
        val session = SessionManager(this)
        val targetMode = if (session.isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        // Apply saved language if AppCompatDelegate state was lost
        val savedLang = session.language
        if (savedLang != LocaleHelper.getCurrentLanguageCode()) {
            LocaleHelper.setLocale(savedLang)
            return
        }

        // Skip onboarding + login if already logged in
        if (session.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = OnboardingAdapter(this)
        binding.viewPager.adapter = adapter

        binding.btnLanguage.setOnClickListener {
            showLanguagePicker()
        }

        binding.btnSkip.setOnClickListener {
            navigateToLogin()
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Hide skip button on last page
                binding.btnSkip.visibility = if (position == 2) View.GONE else View.VISIBLE
            }
        })
    }

    fun goToNextPage() {
        val current = binding.viewPager.currentItem
        if (current < 2) {
            binding.viewPager.currentItem = current + 1
        }
    }

    fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun showLanguagePicker() {
        val sheet = LanguageBottomSheet()
        sheet.onLanguageSelected = { lang ->
            val s = SessionManager(this)
            s.language = lang.code
            LocaleHelper.setLocale(lang.code)
            saveLanguageToServer(s, lang.code)
        }
        sheet.show(supportFragmentManager, LanguageBottomSheet.TAG)
    }

    private fun saveLanguageToServer(session: SessionManager, langCode: String) {
        val uid = session.userId
        if (uid <= 0) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().put("language", langCode)
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/users/$uid/language")
                    .put(body)
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("OnboardingActivity", "Failed to save language to server", e)
            }
        }
    }
}
