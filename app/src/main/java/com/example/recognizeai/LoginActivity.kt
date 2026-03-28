package com.example.recognizeai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.recognizeai.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
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

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private lateinit var googleSignInClient: GoogleSignInClient

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            loginWithGoogle(
                googleId = account.id ?: "",
                email = account.email ?: "",
                displayName = account.displayName ?: "User",
                photoUrl = account.photoUrl?.toString() ?: ""
            )
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google sign-in failed", e)
            Toast.makeText(this, "Google sign-in failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)

        // Skip login if already logged in
        if (session.isLoggedIn) {
            navigateToHome()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGoogleSignIn()

        binding.btnGoogle.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }

        binding.btnGuest.setOnClickListener {
            loginAsGuest()
        }

        updateLanguageDisplay()

        binding.btnLanguage.setOnClickListener {
            showLanguagePicker()
        }
    }

    private fun updateLanguageDisplay() {
        val lang = LocaleHelper.getLanguageByCode(LocaleHelper.getCurrentLanguageCode())
        binding.tvCurrentLanguage.text = "${lang.flag} ${lang.nativeName}"
    }

    private fun showLanguagePicker() {
        val sheet = LanguageBottomSheet()
        sheet.onLanguageSelected = { lang ->
            session.language = lang.code
            LocaleHelper.setLocale(lang.code)
            saveLanguageToServer(lang.code)
        }
        sheet.show(supportFragmentManager, LanguageBottomSheet.TAG)
    }

    private fun saveLanguageToServer(langCode: String) {
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
                Log.e("LoginActivity", "Failed to save language to server", e)
            }
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("584907103450-5cts5qmgpv6bm8cgifn0nejr2jurjnpb.apps.googleusercontent.com")
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun loginAsGuest() {
        val deviceId = session.deviceId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("device_id", deviceId)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/auth/guest")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    throw Exception("Server error: ${response.code}")
                }

                val user = JSONObject(responseBody)
                val serverLang = user.optString("language", "")

                fetchPlanLimits()
                withContext(Dispatchers.Main) {
                    session.saveUser(
                        userId = user.getLong("id"),
                        authType = "guest",
                        displayName = user.optString("display_name", "Guest")
                    )
                    session.plan = user.optString("plan", "free")
                    if (serverLang.isNotEmpty() && serverLang != LocaleHelper.getCurrentLanguageCode()) {
                        session.language = serverLang
                        LocaleHelper.setLocale(serverLang)
                        // setLocale triggers activity recreation — don't navigate yet,
                        // the recreated LoginActivity will see isLoggedIn=true and go to Home
                        return@withContext
                    }
                    navigateToHome()
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Guest login failed", e)
                withContext(Dispatchers.Main) {
                    // Allow offline guest login as fallback
                    session.saveUser(
                        userId = -1L,
                        authType = "guest",
                        displayName = "Guest"
                    )
                    navigateToHome()
                }
            }
        }
    }

    private fun loginWithGoogle(googleId: String, email: String, displayName: String, photoUrl: String) {
        val deviceId = session.deviceId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("google_id", googleId)
                    put("email", email)
                    put("display_name", displayName)
                    put("photo_url", photoUrl)
                    put("device_id", deviceId)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/auth/google")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    throw Exception("Server error: ${response.code}")
                }

                val user = JSONObject(responseBody)
                val serverLang = user.optString("language", "")

                fetchPlanLimits()
                withContext(Dispatchers.Main) {
                    session.saveUser(
                        userId = user.getLong("id"),
                        authType = "google",
                        displayName = user.optString("display_name", displayName),
                        email = user.optString("email", email),
                        photoUrl = user.optString("photo_url", photoUrl)
                    )
                    session.plan = user.optString("plan", "free")
                    if (serverLang.isNotEmpty() && serverLang != LocaleHelper.getCurrentLanguageCode()) {
                        session.language = serverLang
                        LocaleHelper.setLocale(serverLang)
                        return@withContext
                    }
                    navigateToHome()
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Google login server sync failed", e)
                withContext(Dispatchers.Main) {
                    // Save locally even if server fails
                    session.saveUser(
                        userId = -1L,
                        authType = "google",
                        displayName = displayName,
                        email = email,
                        photoUrl = photoUrl
                    )
                    navigateToHome()
                }
            }
        }
    }

    private suspend fun fetchPlanLimits() {
        try {
            val request = Request.Builder()
                .url("${SessionManager.BASE_URL}/api/plan-limits")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return
            if (!response.isSuccessful) return
            val json = JSONObject(body)
            val free = json.optJSONObject("free") ?: return
            val plus = json.optJSONObject("plus") ?: return
            val pro  = json.optJSONObject("pro")  ?: return
            session.savePlanLimits(
                freeScans   = free.optInt("scans_per_day", 5),
                plusScans   = plus.optInt("scans_per_day", 50),
                proScans    = pro.optInt("scans_per_day", 200),
                freeJournal = free.optInt("max_journal", 20),
                freeQueue   = free.optInt("max_queue", 3),
                plusQueue   = plus.optInt("max_queue", 20),
                proQueue    = pro.optInt("max_queue", -1),
                freePins    = free.optInt("max_pins", 20),
                freeAudio   = free.optInt("audio_enabled", 0) != 0,
                freeShare   = free.optInt("share_enabled", 0) != 0
            )
        } catch (e: Exception) {
            Log.e("LoginActivity", "Failed to fetch plan limits", e)
        }
    }

    private fun navigateToHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
