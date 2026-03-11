package com.example.recognizeai

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.recognizeai.databinding.ActivitySubscriptionBinding

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        updateCurrentPlanIndicators()
        setupClickListeners()
    }

    private fun updateCurrentPlanIndicators() {
        when (session.plan) {
            "plus" -> binding.tvPlusCurrent.visibility = android.view.View.VISIBLE
            "pro"  -> binding.tvProCurrent.visibility = android.view.View.VISIBLE
            else   -> binding.tvFreeCurrent.visibility = android.view.View.VISIBLE
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnUpgradePlus.setOnClickListener {
            if (session.plan == "plus") {
                Toast.makeText(this, "You are already on the Traveler plan.", Toast.LENGTH_SHORT).show()
            } else {
                showUpgradeComingSoon("Traveler")
            }
        }

        binding.btnUpgradePro.setOnClickListener {
            if (session.plan == "pro") {
                Toast.makeText(this, "You are already on the Globetrotter plan.", Toast.LENGTH_SHORT).show()
            } else {
                showUpgradeComingSoon("Globetrotter")
            }
        }
    }

    private fun showUpgradeComingSoon(planName: String) {
        Toast.makeText(
            this,
            "In-app purchases coming soon! Upgrade to $planName will be available via Google Play.",
            Toast.LENGTH_LONG
        ).show()
    }
}
