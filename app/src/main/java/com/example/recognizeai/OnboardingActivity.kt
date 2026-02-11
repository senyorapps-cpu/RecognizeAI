package com.example.recognizeai

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.recognizeai.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skip onboarding + login if already logged in
        val session = SessionManager(this)
        if (session.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = OnboardingAdapter(this)
        binding.viewPager.adapter = adapter

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
}
