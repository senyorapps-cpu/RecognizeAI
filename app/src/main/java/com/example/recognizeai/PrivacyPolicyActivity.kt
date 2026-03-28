package com.example.recognizeai

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.recognizeai.databinding.ActivityPrivacyPolicyBinding

class PrivacyPolicyActivity : BaseActivity() {

    private lateinit var binding: ActivityPrivacyPolicyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.webView.webViewClient = WebViewClient()
        binding.webView.settings.javaScriptEnabled = false
        binding.webView.loadUrl("http://sightai.mnaks.online/privacy")
    }
}
