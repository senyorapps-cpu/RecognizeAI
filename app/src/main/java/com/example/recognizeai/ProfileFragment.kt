package com.example.recognizeai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.recognizeai.databinding.FragmentProfileBinding
import org.json.JSONArray

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        loadUserInfo()
        loadStats()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadUserInfo() {
        if (session.isGoogle) {
            binding.tvGreeting.text = "Hello, ${session.displayName}!"
            binding.tvSubtitle.text = session.email.ifEmpty { "Google Account" }
        } else {
            binding.tvGreeting.text = "Hello dear guest"
            binding.tvSubtitle.text = "Guest Explorer"
        }
    }

    private fun loadStats() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("landmarks", "[]") ?: "[]"
        val jsonArray = JSONArray(jsonStr)

        val totalSnaps = jsonArray.length()
        val countries = mutableSetOf<String>()
        var journalCount = 0

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val location = obj.optString("location", "")
            val parts = location.split(",").map { it.trim() }
            if (parts.size >= 2) {
                countries.add(parts.last())
            }
            if (obj.optBoolean("is_saved", false)) {
                journalCount++
            }
        }

        Log.d("ProfileFragment", "Stats: snaps=$totalSnaps, countries=${countries.size} ($countries), journals=$journalCount")

        binding.tvTotalSnaps.text = totalSnaps.toString()
        binding.tvCountries.text = countries.size.toString()
        binding.tvJournals.text = journalCount.toString()
    }

    private fun setupClickListeners() {
        binding.btnLogout.setOnClickListener {
            session.logout()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        binding.btnContactUs.setOnClickListener {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.settingLanguage.setOnClickListener {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.settingSubscription.setOnClickListener {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.settingPrivacy.setOnClickListener {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.settingAppearance.setOnClickListener {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
