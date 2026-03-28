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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingAdapter

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-apply saved locale if lost (e.g. after reinstall) — may trigger recreation
        LocaleHelper.initFromSession(this)

        // Apply dark mode preference early (only if not already matching)
        val session = SessionManager(this)
        val targetMode = if (session.isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        // Skip onboarding + login if already logged in
        if (session.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Skip onboarding if user has already seen it
        if (session.hasSeenOnboarding) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // On fresh install (SharedPreferences wiped), fetch language from server using device ID.
        // The OS may persist a stale per-app locale from before uninstall, so always trust the server.
        if (session.language == "en") {
            CoroutineScope(Dispatchers.IO).launch {
                val serverLang = LocaleHelper.fetchLanguageFromServer(session.deviceId)
                if (serverLang != null && serverLang != LocaleHelper.getAppliedLanguageCode()) {
                    withContext(Dispatchers.Main) {
                        session.language = serverLang
                        LocaleHelper.setLocale(serverLang)
                        // setLocale triggers activity recreation with the correct locale
                    }
                }
            }
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
        SessionManager(this).hasSeenOnboarding = true
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().put("language", langCode)
                    .put("device_id", session.deviceId)
                val body = json.toString().toRequestBody("application/json".toMediaType())

                val uid = session.userId
                val url = if (uid > 0) {
                    "${SessionManager.BASE_URL}/api/users/$uid/language"
                } else {
                    "${SessionManager.BASE_URL}/api/device/${session.deviceId}/language"
                }
                val request = Request.Builder()
                    .url(url)
                    .put(body)
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("OnboardingActivity", "Failed to save language to server", e)
            }
        }
    }
}
