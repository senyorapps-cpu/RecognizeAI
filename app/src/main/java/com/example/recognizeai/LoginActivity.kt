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

class LoginActivity : AppCompatActivity() {

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

                withContext(Dispatchers.Main) {
                    session.saveUser(
                        userId = user.getLong("id"),
                        authType = "guest",
                        displayName = user.optString("display_name", "Guest")
                    )
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

                withContext(Dispatchers.Main) {
                    session.saveUser(
                        userId = user.getLong("id"),
                        authType = "google",
                        displayName = user.optString("display_name", displayName),
                        email = user.optString("email", email),
                        photoUrl = user.optString("photo_url", photoUrl)
                    )
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

    private fun navigateToHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
