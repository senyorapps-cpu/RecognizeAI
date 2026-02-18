package com.example.recognizeai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.recognizeai.databinding.FragmentProfileBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        loadUserInfo()
        loadStats()
        updateLanguageDisplay()
        setupDarkModeToggle()
        setupClickListeners()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            loadStats()
        }
    }

    private fun loadUserInfo() {
        if (session.isGoogle) {
            binding.tvGreeting.text = getString(R.string.profile_hello, session.displayName)
            binding.tvSubtitle.text = session.email.ifEmpty { getString(R.string.profile_subtitle_google) }
        } else {
            binding.tvGreeting.text = getString(R.string.profile_hello_guest)
            binding.tvSubtitle.text = getString(R.string.profile_subtitle_guest)
        }
    }

    private fun loadStats() {
        if (_binding == null) return
        val userId = session.userId
        val deviceId = session.deviceId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (userId > 0) {
                    "${SessionManager.BASE_URL}/api/user/$userId/landmarks?device_id=$deviceId"
                } else {
                    "${SessionManager.BASE_URL}/api/landmarks/by-device?device_id=$deviceId"
                }
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"
                if (!response.isSuccessful) throw Exception("Server error: ${response.code}")

                val jsonArray = JSONArray(body)
                val totalSnaps = jsonArray.length()
                val countries = mutableSetOf<String>()
                var journalCount = 0

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val location = obj.optString("location", "")
                    val parts = location.split(",").map { it.trim() }
                    if (parts.size >= 2) countries.add(parts.last())
                    if (obj.optBoolean("is_saved", false)) journalCount++
                }

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.tvTotalSnaps.text = totalSnaps.toString()
                        binding.tvCountries.text = countries.size.toString()
                        binding.tvJournals.text = journalCount.toString()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Failed to load stats from server", e)
                withContext(Dispatchers.Main) { loadStatsFromLocal() }
            }
        }
    }

    private fun loadStatsFromLocal() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", Context.MODE_PRIVATE)
        val jsonArray = JSONArray(prefs.getString("landmarks", "[]"))

        val totalSnaps = jsonArray.length()
        val countries = mutableSetOf<String>()
        var journalCount = 0

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val location = obj.optString("location", "")
            val parts = location.split(",").map { it.trim() }
            if (parts.size >= 2) countries.add(parts.last())
            if (obj.optBoolean("is_saved", false)) journalCount++
        }

        binding.tvTotalSnaps.text = totalSnaps.toString()
        binding.tvCountries.text = countries.size.toString()
        binding.tvJournals.text = journalCount.toString()
    }

    private fun updateLanguageDisplay() {
        val lang = LocaleHelper.getLanguageByCode(LocaleHelper.getCurrentLanguageCode())
        binding.tvLanguageValue.text = "${lang.flag} ${lang.nativeName}"
    }

    private fun setupDarkModeToggle() {
        binding.switchDarkMode.isChecked = session.isDarkMode
    }

    private fun toggleDarkMode() {
        val newMode = !session.isDarkMode
        session.isDarkMode = newMode
        binding.switchDarkMode.isChecked = newMode
        AppCompatDelegate.setDefaultNightMode(
            if (newMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun setupClickListeners() {
        binding.btnLogout.setOnClickListener {
            session.logout()
            val intent = Intent(requireContext(), OnboardingActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        binding.btnContactUs.setOnClickListener {
            startActivity(Intent(requireContext(), ContactUsActivity::class.java))
        }

        binding.settingLanguage.setOnClickListener {
            val sheet = LanguageBottomSheet()
            sheet.onLanguageSelected = { lang ->
                session.language = lang.code
                LocaleHelper.setLocale(lang.code)
                saveLanguageToServer(lang.code)
            }
            sheet.show(parentFragmentManager, LanguageBottomSheet.TAG)
        }

        binding.settingSubscription.setOnClickListener {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.settingPrivacy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyPolicyActivity::class.java))
        }

        binding.settingAppearance.setOnClickListener {
            toggleDarkMode()
        }
    }

    private fun saveLanguageToServer(langCode: String) {
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
                Log.e("ProfileFragment", "Failed to save language to server", e)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
