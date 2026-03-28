package com.example.recognizeai

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

        // Keep scroll content above the bottom nav bar
        val baseScrollPadding = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseScrollPadding + navBarHeight)
            insets
        }

        loadUserInfo()
        loadStats()
        loadBadges()
        updateLanguageDisplay()
        setupDarkModeToggle()
        setupClickListeners()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            loadStats()
            loadBadges()
        }
    }

    private fun loadUserInfo() {
        if (session.isGoogle) {
            binding.tvGreeting.text = getString(R.string.profile_hello, session.displayName)
            binding.tvSubtitle.text = session.email.ifEmpty { getString(R.string.profile_subtitle_google) }
            binding.statsRow.visibility = View.VISIBLE
            binding.settingSubscription.visibility = View.VISIBLE
            binding.tvLogoutText.text = getString(R.string.profile_logout)
        } else {
            // Guest
            binding.tvGreeting.text = getString(R.string.profile_hello_guest)
            binding.tvSubtitle.text = getString(R.string.profile_subtitle_guest)
            binding.statsRow.visibility = View.GONE
            binding.settingSubscription.visibility = View.GONE
            binding.tvLogoutText.text = getString(R.string.profile_sign_in)
        }
        binding.tvPlanBadge.text = when (session.plan) {
            "plus" -> "✦ Traveler"
            "pro"  -> "✦ Globetrotter"
            else   -> "Explorer"
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
                    if (obj.optInt("is_saved", 0) != 0) journalCount++
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
            if (obj.optInt("is_saved", 0) != 0) journalCount++
        }

        binding.tvTotalSnaps.text = totalSnaps.toString()
        binding.tvCountries.text = countries.size.toString()
        binding.tvJournals.text = journalCount.toString()
    }

    private fun loadBadges() {
        if (_binding == null) return
        val userId = session.userId
        val deviceId = session.deviceId
        val url = "${SessionManager.BASE_URL}/api/user/badges?device_id=$deviceId" +
                if (userId > 0) "&user_id=$userId" else ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"
                val badges = JSONArray(body)

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    renderBadgeGrid(badges)
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Failed to load badges", e)
            }
        }
    }

    private fun renderBadgeGrid(badges: JSONArray) {
        val grid = binding.badgeGrid
        grid.removeAllViews()

        val total = badges.length()
        var earned = 0
        val badgeList = mutableListOf<JSONObject>()
        for (i in 0 until total) {
            val b = badges.getJSONObject(i)
            badgeList.add(b)
            if (b.optBoolean("earned", false)) earned++
        }

        binding.tvBadgeCount.text = "$earned / $total"

        val dp = resources.displayMetrics.density
        val colCount = 4
        val rows = (badgeList.size + colCount - 1) / colCount

        for (row in 0 until rows) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (12 * dp).toInt() }
            }

            for (col in 0 until colCount) {
                val idx = row * colCount + col
                val cellParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (col < colCount - 1) cellParams.marginEnd = (8 * dp).toInt()

                if (idx >= badgeList.size) {
                    // Empty filler
                    val spacer = View(requireContext()).apply { layoutParams = cellParams }
                    rowLayout.addView(spacer)
                    continue
                }

                val badge = badgeList[idx]
                val isEarned = badge.optBoolean("earned", false)
                val emoji = badge.optString("emoji", "🏅")
                val title = badge.optString("title", "")

                val cell = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                    alpha = if (isEarned) 1f else 0.35f
                    setBackgroundResource(R.drawable.bg_setting_item)
                    layoutParams = cellParams
                }

                val emojiView = TextView(requireContext()).apply {
                    text = emoji
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val titleView = TextView(requireContext()).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(resources.getColor(R.color.deep_blue, null))
                    gravity = Gravity.CENTER
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = (4 * dp).toInt() }
                }

                cell.addView(emojiView)
                cell.addView(titleView)
                rowLayout.addView(cell)
            }

            grid.addView(rowLayout)
        }
    }

    private fun updateLanguageDisplay() {
        val lang = LocaleHelper.getLanguageByCode(LocaleHelper.getCurrentLanguageCode())
        binding.tvLanguageValue.text = "${lang.flag} ${lang.nativeName}"
    }

    private fun setupDarkModeToggle() {
        // Theme is now handled via showThemePicker()
    }

    private fun showThemePicker() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_theme_picker)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)

        val current = session.appTheme
        dialog.findViewById<android.widget.ImageView>(R.id.checkDefault).visibility =
            if (current == ThemeHelper.THEME_DEFAULT) android.view.View.VISIBLE else android.view.View.GONE
        dialog.findViewById<android.widget.ImageView>(R.id.checkNight).visibility =
            if (current == ThemeHelper.THEME_NIGHT) android.view.View.VISIBLE else android.view.View.GONE
        dialog.findViewById<android.widget.ImageView>(R.id.checkPaper).visibility =
            if (current == ThemeHelper.THEME_PAPER) android.view.View.VISIBLE else android.view.View.GONE

        fun pick(theme: String) {
            session.appTheme = theme
            ThemeHelper.applyGlobalMode(requireContext())
            dialog.dismiss()
            requireActivity().recreate()
        }

        dialog.findViewById<android.view.View>(R.id.cardThemeDefault).setOnClickListener { pick(ThemeHelper.THEME_DEFAULT) }
        dialog.findViewById<android.view.View>(R.id.cardThemeNight).setOnClickListener { pick(ThemeHelper.THEME_NIGHT) }
        dialog.findViewById<android.view.View>(R.id.cardThemePaper).setOnClickListener { pick(ThemeHelper.THEME_PAPER) }

        dialog.show()
    }

    private fun setupClickListeners() {
        binding.btnLogout.setOnClickListener {
            if (session.isGuest) {
                session.logout()
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            } else {
                session.logout()
                val intent = Intent(requireContext(), OnboardingActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
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
            startActivity(Intent(requireContext(), SubscriptionActivity::class.java))
        }

        binding.settingTerms.setOnClickListener {
            startActivity(Intent(requireContext(), TermsOfServiceActivity::class.java))
        }

        binding.settingPrivacy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyPolicyActivity::class.java))
        }

        binding.settingAppearance.setOnClickListener {
            showThemePicker()
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
