package com.example.recognizeai

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.example.recognizeai.databinding.ActivityAnalyzingBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AnalyzingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyzingBinding
    private val handler = Handler(Looper.getMainLooper())
    private var apiJob: Job? = null
    private var apiResult: Intent? = null
    private var apiError: String? = null
    private var animationsFinished = false
    private var apiFinished = false
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    private val locationReady = CompletableDeferred<Unit>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            getActiveLocation()
        } else {
            Log.d("AnalyzingActivity", "Location permission denied")
            locationReady.complete(Unit)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyzingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCapturedImage()
        startScanAnimation()
        startDataNodeAnimations()
        startPercentAnimation()
        setupClickListeners()

        fetchCurrentLocation()

        val photoUriString = intent.getStringExtra("photo_uri")
        if (photoUriString != null) {
            analyzeWithServer(Uri.parse(photoUriString))
        } else {
            apiFinished = true
        }
    }

    private fun startDataNodeAnimations() {
        val nodes = listOf(
            binding.dataNode1,
            binding.dataNode2,
            binding.dataNode3,
            binding.dataNode4
        )

        nodes.forEachIndexed { index, node ->
            node.translationY = 20f
            node.scaleX = 0.8f
            node.scaleY = 0.8f

            val fadeIn = ObjectAnimator.ofFloat(node, View.ALPHA, 0f, 1f)
            val slideUp = ObjectAnimator.ofFloat(node, View.TRANSLATION_Y, 20f, 0f)
            val scaleX = ObjectAnimator.ofFloat(node, View.SCALE_X, 0.8f, 1f)
            val scaleY = ObjectAnimator.ofFloat(node, View.SCALE_Y, 0.8f, 1f)

            val nodeAnim = AnimatorSet().apply {
                playTogether(fadeIn, slideUp, scaleX, scaleY)
                duration = 500
                startDelay = (600 + index * 800).toLong()
                interpolator = AccelerateDecelerateInterpolator()
            }
            nodeAnim.start()

            handler.postDelayed({
                if (!isFinishing) {
                    val pulseX = ObjectAnimator.ofFloat(node, View.SCALE_X, 1f, 1.1f, 1f)
                    val pulseY = ObjectAnimator.ofFloat(node, View.SCALE_Y, 1f, 1.1f, 1f)
                    AnimatorSet().apply {
                        playTogether(pulseX, pulseY)
                        duration = 400
                        start()
                    }
                }
            }, (1200 + index * 800).toLong())
        }
    }

    private var percentAnimator: ValueAnimator? = null
    private var waitingAnimator: ObjectAnimator? = null
    private var hasNavigated = false

    private fun startPercentAnimation() {
        val animator = ValueAnimator.ofInt(0, 90)
        percentAnimator = animator
        animator.duration = 4000
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { animation ->
            if (hasNavigated) return@addUpdateListener
            val value = animation.animatedValue as Int
            binding.tvPercent.text = "$value%"

            when {
                value < 25 -> binding.tvStatusSubtitle.text = "Scanning image structure\u2026"
                value < 50 -> binding.tvStatusSubtitle.text = "Matching visual patterns\u2026"
                value < 75 -> binding.tvStatusSubtitle.text = "Identifying historical data\u2026"
                else -> binding.tvStatusSubtitle.text = "Compiling discovery report\u2026"
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (hasNavigated) return
                if (apiFinished) {
                    completeAndNavigate()
                } else {
                    // Hold at 90% — show pulsing "waiting" state
                    animationsFinished = true
                    binding.tvStatusSubtitle.text = "Waiting for server response\u2026"
                    waitingAnimator = ObjectAnimator.ofFloat(binding.tvStatusSubtitle, View.ALPHA, 1f, 0.3f).apply {
                        duration = 800
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            }
        })
        animator.start()
    }

    private fun completeAndNavigate() {
        if (hasNavigated) return
        hasNavigated = true
        percentAnimator?.cancel()
        waitingAnimator?.cancel()
        waitingAnimator = null
        binding.tvStatusSubtitle.alpha = 1f
        animationsFinished = true
        apiFinished = true

        if (apiResult != null) {
            binding.tvPercent.text = "100%"
            binding.tvStatusSubtitle.text = "Analysis complete"
            startActivity(apiResult!!)
            finish()
        } else {
            // No data from server — don't show empty template page
            val errMsg = apiError ?: "No response from server"
            binding.tvPercent.text = "!"
            binding.tvStatusSubtitle.text = errMsg
            Toast.makeText(this, "Analysis failed: $errMsg", Toast.LENGTH_LONG).show()
            Log.e("AnalyzingActivity", "Navigate failed — apiError: $errMsg")
            // Go back after 3 seconds so user can read the error
            handler.postDelayed({
                if (!isFinishing) finish()
            }, 3000)
        }
    }

    private fun tryNavigate() {
        if (animationsFinished && apiFinished) {
            completeAndNavigate()
        }
    }

    private fun fetchCurrentLocation() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            // Request permission at runtime — user hasn't been asked yet
            Log.d("AnalyzingActivity", "Requesting location permission...")
            requestLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }

        getActiveLocation()
    }

    @SuppressLint("MissingPermission")
    private fun getActiveLocation() {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(this)
            val cts = CancellationTokenSource()

            // Use getCurrentLocation for a fresh GPS fix (lastLocation can be null)
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        currentLatitude = location.latitude
                        currentLongitude = location.longitude
                        Log.d("AnalyzingActivity", "GPS fix: ${location.latitude}, ${location.longitude}")
                    } else {
                        Log.d("AnalyzingActivity", "getCurrentLocation returned null, trying lastLocation...")
                        // Fallback to lastLocation
                        try {
                            fusedClient.lastLocation.addOnSuccessListener { last ->
                                if (last != null) {
                                    currentLatitude = last.latitude
                                    currentLongitude = last.longitude
                                    Log.d("AnalyzingActivity", "lastLocation: ${last.latitude}, ${last.longitude}")
                                } else {
                                    Log.d("AnalyzingActivity", "lastLocation also null")
                                }
                                locationReady.complete(Unit)
                            }.addOnFailureListener {
                                locationReady.complete(Unit)
                            }
                        } catch (_: SecurityException) {
                            locationReady.complete(Unit)
                        }
                        return@addOnSuccessListener
                    }
                    locationReady.complete(Unit)
                }
                .addOnFailureListener { e ->
                    Log.e("AnalyzingActivity", "getCurrentLocation failed", e)
                    locationReady.complete(Unit)
                }
        } catch (e: Exception) {
            Log.e("AnalyzingActivity", "Failed to get location", e)
            locationReady.complete(Unit)
        }
    }

    private fun compressImage(photoUri: Uri): Pair<ByteArray, Uri>? {
        // Read EXIF orientation before decoding
        val rotation = try {
            contentResolver.openInputStream(photoUri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            Log.e("AnalyzingActivity", "Failed to read EXIF", e)
            0f
        }

        val inputStream = contentResolver.openInputStream(photoUri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (original == null) return null

        // Apply EXIF rotation
        val rotated = if (rotation != 0f) {
            val matrix = Matrix()
            matrix.postRotate(rotation)
            val rotBmp = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            original.recycle()
            rotBmp
        } else {
            original
        }

        val maxEdge = 1200
        val scale = if (rotated.width > rotated.height) {
            maxEdge.toFloat() / rotated.width
        } else {
            maxEdge.toFloat() / rotated.height
        }

        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt(),
                (rotated.height * scale).toInt(),
                true
            )
        } else {
            rotated
        }

        val dir = File(cacheDir, "compressed_images")
        dir.mkdirs()
        val file = File(dir, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
        }
        if (bitmap !== rotated) bitmap.recycle()
        rotated.recycle()

        val compressedUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        return Pair(file.readBytes(), compressedUri)
    }

    private fun analyzeWithServer(photoUri: Uri) {
        apiJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val analysisLang = SessionManager(this@AnalyzingActivity).language
                Log.d("AnalyzingActivity", "Analysis language: $analysisLang")

                // Load and compress image first (outside timeout)
                val compressed = compressImage(photoUri)
                val imageBytes: ByteArray
                val displayUri: Uri

                if (compressed != null) {
                    imageBytes = compressed.first
                    displayUri = compressed.second
                    Log.d("AnalyzingActivity", "Compressed image: ${imageBytes.size / 1024}KB")
                } else {
                    val raw = contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
                    if (raw == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AnalyzingActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                            apiFinished = true
                            tryNavigate()
                        }
                        return@launch
                    }
                    imageBytes = raw
                    displayUri = photoUri
                }

                // Wait up to 5 seconds for location (don't block API too long)
                withTimeoutOrNull(5000L) { locationReady.await() }
                Log.d("AnalyzingActivity", "Upload lat=$currentLatitude lng=$currentLongitude")

                // Wrap the network call in a 45-second overall timeout
                val json = withTimeoutOrNull(45000L) {
                    val mediaType = "image/jpeg".toMediaType()
                    val session = SessionManager(this@AnalyzingActivity)
                    val builder = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "image",
                            "photo.jpg",
                            imageBytes.toRequestBody(mediaType)
                        )
                        .addFormDataPart("user_id", session.userId.toString())
                        .addFormDataPart("device_id", session.deviceId)
                        .addFormDataPart("language", analysisLang)

                    currentLatitude?.let { builder.addFormDataPart("latitude", it.toString()) }
                    currentLongitude?.let { builder.addFormDataPart("longitude", it.toString()) }

                    val requestBody = builder.build()

                    val request = Request.Builder()
                        .url("${SessionManager.BASE_URL}/api/analyze")
                        .post(requestBody)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""
                    Log.d("AnalyzingActivity", "Server response: $responseBody")

                    if (!response.isSuccessful) {
                        // Parse server error for user-friendly message
                        val errorMsg = try {
                            val errJson = JSONObject(responseBody)
                            errJson.optString("message", "Server error: ${response.code}")
                        } catch (_: Exception) {
                            "Server error: ${response.code}"
                        }
                        throw Exception(errorMsg)
                    }

                    JSONObject(responseBody)
                }

                if (json == null) {
                    Log.e("AnalyzingActivity", "Analysis timed out after 45 seconds")
                    withContext(Dispatchers.Main) {
                        apiError = "Request timed out"
                        apiFinished = true
                        tryNavigate()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    apiResult = Intent(this@AnalyzingActivity, LandmarkDetailActivity::class.java).apply {
                        putExtra("photo_uri", displayUri.toString())
                        putExtra("server_id", json.optLong("id", -1L))
                        putExtra("name", json.optString("name", "Unknown Landmark"))
                        putExtra("location", json.optString("location", "Unknown Location"))
                        putExtra("year_built", json.optString("year_built", "Unknown"))
                        putExtra("status", json.optString("status", "Landmark"))
                        putExtra("architect", json.optString("architect", "Unknown"))
                        putExtra("capacity", json.optString("capacity", "N/A"))
                        putExtra("narrative_p1", json.optString("narrative_p1", ""))
                        putExtra("narrative_quote", json.optString("narrative_quote", ""))
                        putExtra("narrative_p2", json.optString("narrative_p2", ""))
                        putExtra("nearby1_name", json.optString("nearby1_name", ""))
                        putExtra("nearby1_category", json.optString("nearby1_category", ""))
                        putExtra("nearby2_name", json.optString("nearby2_name", ""))
                        putExtra("nearby2_category", json.optString("nearby2_category", ""))
                        putExtra("nearby3_name", json.optString("nearby3_name", ""))
                        putExtra("nearby3_category", json.optString("nearby3_category", ""))
                        putExtra("language", analysisLang)
                    }
                    // Save to local storage for offline/guest access
                    val prefs = getSharedPreferences("recognizeai_landmarks", MODE_PRIVATE)
                    val existing = JSONArray(prefs.getString("landmarks", "[]"))
                    val entry = JSONObject().apply {
                        put("server_id", json.optLong("id", -1L))
                        put("photo_uri", displayUri.toString())
                        put("name", json.optString("name", "Unknown"))
                        put("location", json.optString("location", ""))
                        put("year_built", json.optString("year_built", ""))
                        put("status", json.optString("status", ""))
                        put("architect", json.optString("architect", ""))
                        put("capacity", json.optString("capacity", ""))
                        put("narrative_p1", json.optString("narrative_p1", ""))
                        put("narrative_quote", json.optString("narrative_quote", ""))
                        put("narrative_p2", json.optString("narrative_p2", ""))
                        put("nearby1_name", json.optString("nearby1_name", ""))
                        put("nearby1_category", json.optString("nearby1_category", ""))
                        put("nearby2_name", json.optString("nearby2_name", ""))
                        put("nearby2_category", json.optString("nearby2_category", ""))
                        put("nearby3_name", json.optString("nearby3_name", ""))
                        put("nearby3_category", json.optString("nearby3_category", ""))
                        put("language", analysisLang)
                        put("is_saved", false)
                        put("rating", 0)
                        put("created_at", java.time.Instant.now().toString())
                        currentLatitude?.let { put("latitude", it) }
                        currentLongitude?.let { put("longitude", it) }
                    }
                    existing.put(entry)
                    prefs.edit().putString("landmarks", existing.toString()).apply()
                    Log.d("AnalyzingActivity", "LOCAL SAVE: ${entry.optString("name")} (server_id=${entry.optLong("server_id")}, total=${existing.length()})")

                    apiFinished = true
                    tryNavigate()
                }

            } catch (e: Exception) {
                Log.e("AnalyzingActivity", "Server API error", e)
                withContext(Dispatchers.Main) {
                    if (!NetworkUtils.isOnline(this@AnalyzingActivity)) {
                        // Offline — save photo to pending queue
                        val originalUri = intent.getStringExtra("photo_uri") ?: ""
                        val pendingManager = PendingAnalysisManager(this@AnalyzingActivity)
                        pendingManager.addToPendingQueue(
                            photoUri = originalUri,
                            lat = currentLatitude,
                            lng = currentLongitude,
                            createdAt = java.time.Instant.now().toString()
                        )
                        // Stop animations
                        percentAnimator?.cancel()
                        waitingAnimator?.cancel()
                        hasNavigated = true

                        // Show styled no-internet dialog
                        showNoInternetDialog()
                    } else {
                        apiError = e.message ?: "Unknown error"
                        apiFinished = true
                        tryNavigate()
                    }
                }
            }
        }
    }

    private fun showNoInternetDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_no_internet)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(false)

        dialog.findViewById<TextView>(R.id.btnGoToSaved).setOnClickListener {
            dialog.dismiss()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SAVED)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(mainIntent)
            finish()
        }

        dialog.show()
    }

    private fun loadCapturedImage() {
        val photoUriString = intent.getStringExtra("photo_uri")

        if (photoUriString != null) {
            Glide.with(this)
                .load(Uri.parse(photoUriString))
                .centerCrop()
                .into(binding.imgCaptured)
        } else {
            Glide.with(this)
                .load("https://lh3.googleusercontent.com/aida-public/AB6AXuA4lvi2KwB33CRmJFwjY-Ae0wK56AAyyrjno0Ngpu1tkNjFCC8slul18FTkwL34TmmTokinOFQp_IduKP60L05cbht7YH9WfJrUoT-PukOM996hjuQxGg1N04ru66ZACGKlWbGyTt_Vm-MzRC4oJpzWgM6bwUaqCYdsnGT5ptUoJheRNSwpJf-w0LUbhWeMBh_wlqXwtGFe57sztHytwEluy_LnEHKQJxTc2eI5yrydJQJGaySIE9x5SI60b6b-SyXtpdZmtFMb9QY")
                .centerCrop()
                .into(binding.imgCaptured)
        }
    }

    private fun startScanAnimation() {
        val scanBeam = binding.scanBeam
        scanBeam.post {
            val parent = scanBeam.parent as? android.view.View ?: return@post
            val animator = ObjectAnimator.ofFloat(scanBeam, "translationY", 0f, parent.height.toFloat())
            animator.duration = 3500
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE
            animator.interpolator = LinearInterpolator()
            animator.start()
        }
    }

    private fun setupClickListeners() {
        binding.btnCancel.setOnClickListener {
            apiJob?.cancel()
            handler.removeCallbacksAndMessages(null)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        apiJob?.cancel()
        percentAnimator?.cancel()
        waitingAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
    }
}
